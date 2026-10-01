# V2.5 Transactional Outbox Pattern Architecture

## 1. Executive Summary

In V2.5 of the Financial Ledger Engine, the **Transactional Outbox Pattern** guarantees reliable domain and integration event publishing without sacrificing transactional atomicity or ledger consistency.

The fundamental invariant of this pattern is:

$$\text{Financial State Change} + \text{Outbox Event} \xrightarrow[\text{PostgreSQL Transaction}]{\text{Atomic Persistence}} \text{Commit}$$

- If the financial transaction **rolls back**, the outbox event **never exists**.
- If the financial transaction **commits**, the corresponding outbox event is **guaranteed to exist**.
- The outbox is **not a financial source of truth**; financial tables (`transactions`, `ledger_entries`, `accounts`) remain authoritative.
- The outbox processor does **not** participate in the original financial transaction.

---

## 2. Architecture & Data Flow

### 2.1 Request & Mutation Flow

```
HTTP Client / API Request
         │
         ▼
[ Authentication & Authorization ]
         │
         ▼
[ Validation & Idempotency Key Verification ]
         │
         ▼
BEGIN PostgreSQL Transaction
         │
         ├─► Lock Accounts (Deterministic ID Order: SELECT ... FOR UPDATE)
         ├─► Verify Account Status & Balance Constraints
         ├─► Mutate Account Balances (Snapshot)
         ├─► Insert Transaction Record
         ├─► Insert Balanced Ledger Entries (Immutable Historical Truth)
         ├─► Insert Audit Event (Security & Regulatory Trail)
         └─► Insert Outbox Event (JSONB Payload, Status: PENDING)
         │
COMMIT PostgreSQL Transaction
         │
         ▼
HTTP Response (200 OK / 201 Created)
```

### 2.2 Asynchronous Event Delivery Flow

```
Scheduled / Background Worker
         │
         ▼
BEGIN Outbox Worker Transaction
         │
         ├─► SELECT * FROM outbox_events
         │   WHERE status = 'PENDING' AND available_at <= NOW()
         │   ORDER BY created_at ASC
         │   LIMIT :batchSize
         │   FOR UPDATE SKIP LOCKED;
         │
         ├─► For each locked event:
         │     ├─► Mark status = 'PROCESSING'
         │     ├─► Dispatch to OutboxEventHandler(s)
         │     ├─► On Success:
         │     │     status = 'PROCESSED', processed_at = NOW()
         │     └─► On Failure:
         │           IF attempt_count >= max_attempts:
         │             status = 'FAILED', last_error = sanitized(error)
         │           ELSE:
         │             status = 'PENDING',
         │             available_at = NOW() + backoff(attempt),
         │             last_error = sanitized(error)
         │
COMMIT Outbox Worker Transaction
```

---

## 3. Database Schema & Migration (`V14__create_outbox_events_schema.sql`)

The `outbox_events` table is created via Flyway migration `V14`.

### 3.1 DDL Specification

| Column | Type | Constraints / Defaults | Description |
|---|---|---|---|
| `id` | `UUID` | `PRIMARY KEY` | Unique event identifier |
| `aggregate_type` | `VARCHAR(32)` | `NOT NULL`, `CHECK IN ('TRANSACTION', 'ACCOUNT')` | Domain aggregate boundary |
| `aggregate_id` | `UUID` | `NOT NULL` | ID of the aggregate (transaction or account) |
| `event_type` | `VARCHAR(64)` | `NOT NULL`, `CHECK IN (...)` | Explicit domain event name |
| `payload` | `JSONB` | `NOT NULL` | Immutable, self-contained event payload |
| `status` | `VARCHAR(32)` | `NOT NULL DEFAULT 'PENDING'` | Lifecycle state: `PENDING`, `PROCESSING`, `PROCESSED`, `FAILED` |
| `attempt_count` | `INTEGER` | `NOT NULL DEFAULT 0`, `CHECK >= 0` | Retry execution counter |
| `available_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT NOW()` | Visibility timestamp for processor polling |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT NOW()` | Database-controlled creation timestamp |
| `processed_at` | `TIMESTAMPTZ` | `NULL` | Populated upon successful completion |
| `last_error` | `TEXT` | `NULL` | Sanitized operational failure information |

### 3.2 Constraints & Uniqueness

1. **State Validation Constraints**:
   - `chk_outbox_events_status`: Ensures status is one of `'PENDING'`, `'PROCESSING'`, `'PROCESSED'`, or `'FAILED'`.
   - `chk_outbox_events_attempt_count`: Ensures `attempt_count >= 0`.
   - `chk_outbox_events_processed_at`: Ensures `processed_at IS NOT NULL` if and only if `status = 'PROCESSED'`.
2. **Partial Uniqueness Constraints**:
   - `uk_outbox_events_transaction_event`: `UNIQUE (aggregate_id, event_type) WHERE aggregate_type = 'TRANSACTION'`. Guarantees a financial transaction can only emit a single event of a given type.
   - `uk_outbox_events_account_created`: `UNIQUE (aggregate_id) WHERE event_type = 'ACCOUNT_CREATED'`. Guarantees an account can only emit a single creation event.
   - `uk_outbox_events_account_closed`: `UNIQUE (aggregate_id) WHERE event_type = 'ACCOUNT_CLOSED'`. Guarantees an account can only emit a single closure event.

### 3.3 Indexes

- `idx_outbox_events_status_available`: `(status, available_at)` composite index for sub-millisecond polling by the outbox processor.
- `idx_outbox_events_created_at`: `(created_at ASC)` for chronological scanning and pagination.
- `idx_outbox_events_aggregate`: `(aggregate_type, aggregate_id)` for tracing events per aggregate.
- `idx_outbox_events_event_type`: `(event_type)` for filtering.

---

## 4. Domain Event Types & Payloads

The platform defines 8 distinct domain event types across 2 aggregates:

### 4.1 Event Catalog

| Aggregate Type | Event Type | Triggering Operation |
|---|---|---|
| `TRANSACTION` | `TRANSFER_COMPLETED` | Successful inter-account transfer commit |
| `TRANSACTION` | `DEPOSIT_COMPLETED` | Successful account deposit commit |
| `TRANSACTION` | `WITHDRAWAL_COMPLETED` | Successful account withdrawal commit |
| `TRANSACTION` | `TRANSACTION_REVERSED` | Successful transaction reversal commit |
| `ACCOUNT` | `ACCOUNT_CREATED` | New account onboarding commit |
| `ACCOUNT` | `ACCOUNT_FROZEN` | Account state transitioned to FROZEN |
| `ACCOUNT` | `ACCOUNT_UNFROZEN` | Account state transitioned back to ACTIVE |
| `ACCOUNT` | `ACCOUNT_CLOSED` | Account state transitioned to CLOSED |

### 4.2 Payload Security & Contract

Outbox payloads are serialized JSONB documents designed for external/integration consumers.
Under **no circumstance** are the following exposed in outbox payloads:
- Passwords, password hashes, or salt
- Session IDs, auth tokens, or CSRF tokens
- Raw idempotency keys
- Internal security secrets

#### Sample `TRANSFER_COMPLETED` Payload:
```json
{
  "eventId": "3c907314-fca1-44be-8311-6679549f6b4a",
  "eventType": "TRANSFER_COMPLETED",
  "aggregateId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
  "aggregateType": "TRANSACTION",
  "occurredAt": "2026-10-01T12:00:00.000Z",
  "transactionId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
  "sourceAccountId": "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d",
  "destinationAccountId": "b2c3d4e5-f6a7-8b9c-0d1e-2f3a4b5c6d7e",
  "amount": "1500.0000",
  "currency": "INR",
  "reference": "Monthly Rent"
}
```

---

## 5. Concurrency Control & Processing Semantics

### 5.1 Concurrency via `FOR UPDATE SKIP LOCKED`

To support horizontal scalability across multiple application instances and parallel background workers without distributed locks (e.g., Redlock or ZooKeeper), the processor uses native PostgreSQL row-level locks:

```sql
SELECT * FROM outbox_events
WHERE status = 'PENDING'
  AND available_at <= NOW()
ORDER BY created_at ASC
LIMIT :limit
FOR UPDATE SKIP LOCKED;
```

**Guarantees**:
- **Zero Lock Contention**: Concurrent workers attempting to claim records simultaneously skip locked rows immediately.
- **Zero Blocking**: Workers do not wait on each other; throughput scales linearly with worker concurrency.
- **Deadlock Immunity**: Rows are locked in consistent primary key / chronological order.

### 5.2 Delivery Semantics: Strictly AT-LEAST-ONCE

The Transactional Outbox pattern provides **AT-LEAST-ONCE** delivery guarantees.
Downstream consumers must be idempotent (e.g., using `eventId` or `transactionId` + `eventType` as a deduplication key).

Reasons duplicate delivery can theoretically occur:
1. Downstream consumer succeeds, but the database connection fails before `outbox_events` can commit status `PROCESSED`.
2. Process crash or hard network partition during execution.

### 5.3 Retry Policy & Exponential Backoff

When an event handler throws an exception during processing:
1. `attempt_count` is incremented.
2. The error message is sanitized (truncated to 500 characters, secrets stripped).
3. If `attempt_count < max_attempts` (default: 5):
   - Status remains `PENDING`.
   - `available_at` is scheduled into the future using exponential backoff:
     $$\text{delay} = \min(\text{baseDelayMs} \times 2^{\text{attempt}-1}, \text{maxDelayMs})$$
4. If `attempt_count >= max_attempts`:
   - Status transitions to terminal `FAILED`.
   - Alerting metrics are emitted (`ledger_outbox_events_failed_total`).

---

## 6. Observability & Operational Metrics

All Micrometer metrics adhere strictly to bounded low-cardinality label conventions:

| Metric Name | Type | Tags | Description |
|---|---|---|---|
| `ledger.outbox.events.created` | Counter | `event_type` | Total outbox events committed in DB transactions |
| `ledger.outbox.events.processed` | Counter | `event_type` | Total events successfully dispatched and completed |
| `ledger.outbox.events.retried` | Counter | `event_type` | Total transient event retry transitions |
| `ledger.outbox.events.failed` | Counter | `event_type` | Total events transitioned to terminal FAILED state |
| `ledger.outbox.processing.duration` | Timer | `event_type`, `outcome` | Latency distribution of event execution (`outcome=success\|failure`) |

**Cardinality Guarantee**: High-cardinality attributes (UUIDs, transaction IDs, account numbers, timestamps, exception messages, payloads) are **never** used as metric tags.

---

## 7. Administrative Operational Endpoint

### `GET /api/v1/admin/outbox`

Provides administrative visibility into outbox state, lag, and dead-lettered failures.

- **Security**: Restricted to `ROLE_ADMIN`. Unauthorized requests yield `401 Unauthorized`; non-admin authenticated users receive `403 Forbidden`.
- **Pagination & Filtering**:
  - `status`: Optional filter (`PENDING`, `PROCESSING`, `PROCESSED`, `FAILED`).
  - `eventType`: Optional filter (`TRANSFER_COMPLETED`, etc.).
  - `page`: 0-indexed page number (default: 0).
  - `size`: Page size between 1 and 100 (default: 20).
- **Data Protection**: Returns lightweight metadata summaries (`id`, `aggregateType`, `aggregateId`, `eventType`, `status`, `attemptCount`, `availableAt`, `createdAt`, `processedAt`, `lastError`). **Raw payloads are omitted** to prevent accidental credential or sensitive data leakage in administrative dashboards.

---

## 8. Separation of Concerns & Financial Integrity

1. **Reconciliation Independence**:
   - The reconciliation engine validates financial consistency by comparing account snapshot balances against immutable double-entry ledger entries (`ledger_entries`).
   - The outbox is **never** part of reconciliation.
   - Outbox processing failures have zero impact on ledger balance consistency.
2. **Transaction Rollback Isolation**:
   - Outbox events are written within the same transaction as financial mutations.
   - If an account balance check, policy check, or constraint fails, the rollback aborts both the financial records and the outbox event.
   - No orphan outbox events can exist.
