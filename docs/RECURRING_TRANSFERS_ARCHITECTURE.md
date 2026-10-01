# V2.6 Recurring Transfers Architecture

## 1. Executive Summary

In V2.6 of the Financial Ledger Engine, **Recurring Transfers** introduces automated scheduled transfer capabilities without introducing secondary financial paths or compromising core ledger invariants.

The fundamental architectural invariant is:

$$\text{A recurring transfer is } \mathbf{SCHEDULING\ METADATA} \text{, NOT a second financial execution engine.}$$

Every recurring transfer occurrence **MUST** execute exclusively through the authoritative `TransferService.executeTransfer(...)` method:
- Double-entry bookkeeping remains strictly balanced (sum of debits equals sum of credits).
- Immutable ledger entries remain historical truth; account balance snapshots remain cached views.
- Pessimistic row-level locking with deterministic account ID ordering prevents concurrency deadlocks.
- The Financial Policy Engine continuously enforces transaction, daily amount, and count quotas.
- Security audit events and Transactional Outbox integration events are recorded atomically.
- Platform currency is strictly **INR**.

---

## 2. Architecture & System Flow

### 2.1 Scheduling Lifecycle vs. Financial Execution

```
+-----------------------------------------------------------------------------------+
|                           SCHEDULING METADATA LIFECYCLE                          |
+-----------------------------------------------------------------------------------+
  User creates schedule (sourceAccount, destAccount, amount, frequency, startDate)
                             │
                             ▼
  RecurringTransferService.createRecurringTransfer(...)
  - Validates ownership, account status (ACTIVE), account type (USER_CHECKING), amount > 0, currency == INR
  - Calculates initial nextExecutionAt via RecurringScheduleCalculator
  - Persists RecurringTransfer (status = ACTIVE)
  - Emits RECURRING_TRANSFER_CREATED audit event
  - [DOES NOT execute any financial transfer or mutate balances]
                             │
            ┌────────────────┴────────────────┐
            ▼                                 ▼
      Pause Schedule                    Resume Schedule
      (ACTIVE -> PAUSED)                (PAUSED -> ACTIVE)
            │                                 │
            └────────────────┬────────────────┘
                             ▼
                       Cancel Schedule
                  (ACTIVE/PAUSED -> CANCELLED)
```

```
+-----------------------------------------------------------------------------------+
|                        SCHEDULED EXECUTION WORKER FLOW                            |
+-----------------------------------------------------------------------------------+
  RecurringTransferProcessor (@Scheduled poller)
                             │
                             ▼
  BEGIN Outer Scheduler Transaction
  - SELECT * FROM recurring_transfers
    WHERE status = 'ACTIVE' AND next_execution_at <= NOW()
    ORDER BY next_execution_at ASC
    LIMIT :batchSize
    FOR UPDATE SKIP LOCKED;
                             │
  For each claimed schedule:
    - Derive deterministic execution identity:
      UUID = nameUUIDFromBytes("RECURRING:" + scheduleId + ":" + slotInstantMillis)
    - Establish SecurityContext for schedule owner
                             │
                             ▼
  TransferService.executeTransfer(idempotencyKey, request)  [REQUIRES_NEW Transaction]
    ├─► Lock accounts deterministically (FOR UPDATE)
    ├─► Verify available balance & policy quotas
    ├─► Mutate snapshot balances
    ├─► Persist transaction record
    ├─► Persist balanced double-entry ledger entries
    ├─► Persist audit event & outbox event
    └─► Commit or Rollback financial transaction independently
                             │
            ┌────────────────┴────────────────┐
            ▼                                 ▼
       On Success                        On Failure (e.g. InsufficientBalance)
  - Insert SUCCESS execution record - Insert FAILED execution record
  - Increment executionCount        - Increment failureCount
  - Advance nextExecutionAt         - Advance nextExecutionAt (catch-up convergence)
  - Transition COMPLETED if past end- Transition CANCELLED if account closed
                             │
                             ▼
  COMMIT Outer Scheduler Transaction (persists schedule state & execution history)
```

---

## 3. Calendar Arithmetic, Anchor Day Preservation, & Convergence

### 3.1 Frequency Arithmetic Rules

Calendar calculations are encapsulated in [`RecurringScheduleCalculator`](file:///Users/parth/IdeaProjects/financial-ledger-engine/backend/src/main/java/com/parth/ledger/recurring/RecurringScheduleCalculator.java):

- **DAILY**: `currentSlotDate + 1 day`
- **WEEKLY**: `currentSlotDate + 7 days`
- **MONTHLY**: Advances exactly 1 calendar month while **preserving the original anchor day of the month** without drift:
  - Last Valid Day of Month semantics: If the anchor day exceeds the maximum days in the target month (e.g. Jan 31 -> February), it safely clamps to the last valid day of that month (Feb 28 or Feb 29 in leap years).
  - Preserves anchor for subsequent months: Moving to March restores the anchor day to March 31, followed by April 30.

### 3.2 Missed Executions & Catch-up Convergence

If a node crashes, server is down, or processing is delayed across multiple scheduled cycles:
1. Exactly **one catch-up execution** is performed for the oldest due slot.
2. The schedule's `nextExecutionAt` then **converges directly to the next future scheduled occurrence** relative to current time:
   $$\text{nextOccurrence} = \text{computeNextOccurrence}(\text{anchorDate}, \text{now}, \text{frequency})$$
3. This prevents catastrophic cascading re-run storms where months of missed slots trigger hundreds of consecutive financial mutations in a tight loop.

### 3.3 End Date Semantics

- **Inclusive End Date**: A schedule whose candidate slot falls on or before `endDate` (at 23:59:59.999Z) remains eligible.
- **Terminal Completion**: Once the calculated next candidate execution date strictly exceeds `endDate`, the schedule state transitions atomically to `COMPLETED` and `nextExecutionAt` is set to `null`.

---

## 4. Concurrency Control & Idempotency Invariants

### 4.1 Skip-Locked Claim Mechanism

To ensure horizontally scalable, lock-contention-free multi-worker execution, eligible schedules are claimed via:

```sql
SELECT * FROM recurring_transfers
WHERE status = 'ACTIVE'
  AND next_execution_at <= :asOf
ORDER BY next_execution_at ASC
LIMIT :limit
FOR UPDATE SKIP LOCKED;
```

Any schedule row currently held by another worker is transparently skipped without deadlocks or waiting.

### 4.2 Deterministic Execution Identity

Every recurring execution slot derives an immutable, deterministic UUID based on RFC 4122 version 3 UUID name generation:

$$\text{Execution UUID} = \text{UUID.nameUUIDFromBytes}(\text{"RECURRING:"} + \text{scheduleId} + \text{":"} + \text{slotInstantMillis})$$

This deterministic UUID serves dual purposes:
1. **Financial Idempotency Key**: Passed to `TransferService.executeTransfer(...)`. If an execution retries after a partial timeout, PostgreSQL database uniqueness prevents duplicate financial entries.
2. **Execution Slot Uniqueness**: Enforced by database constraint `uk_recurring_transfer_executions_slot (recurring_transfer_id, execution_key)` in table `recurring_transfer_executions`.

### 4.3 Transaction Isolation (`REQUIRES_NEW`)

`TransferService.executeTransfer` uses `@Transactional(propagation = Propagation.REQUIRES_NEW)`. If financial validation fails (e.g., source account has insufficient funds or breaches a financial policy):
- The financial transaction rolls back cleanly (no partial balance mutations or broken ledger entries).
- The outer scheduler transaction is **NOT** marked rollback-only.
- The outer scheduler records the `FAILED` execution history entry, increments `failureCount`, advances `nextExecutionAt`, and commits safely.

---

## 5. Database Schema (`V15__create_recurring_transfers_schema.sql`)

### 5.1 `recurring_transfers` Table

| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | UUID | PRIMARY KEY | Unique schedule identifier |
| `user_id` | UUID | NOT NULL, FK -> `users` | Schedule owner |
| `source_account_id` | UUID | NOT NULL, FK -> `accounts` | Account debited |
| `destination_account_id`| UUID | NOT NULL, FK -> `accounts` | Account credited |
| `amount` | NUMERIC(19,4) | NOT NULL, CHECK > 0 | Transfer amount |
| `currency` | VARCHAR(3) | NOT NULL, CHECK = 'INR' | Platform currency |
| `frequency` | VARCHAR(20) | NOT NULL, CHECK IN ('DAILY','WEEKLY','MONTHLY') | Scheduling cadence |
| `start_date` | DATE | NOT NULL | Scheduled start date |
| `end_date` | DATE | NULL | Optional scheduled end date |
| `status` | VARCHAR(20) | NOT NULL, CHECK IN ('ACTIVE','PAUSED','COMPLETED','CANCELLED') | Lifecycle state |
| `next_execution_at` | TIMESTAMPTZ | NULL | Next eligible run time |
| `last_executed_at` | TIMESTAMPTZ | NULL | Timestamp of last execution |
| `execution_count` | INT | NOT NULL DEFAULT 0 | Count of successful runs |
| `failure_count` | INT | NOT NULL DEFAULT 0 | Count of failed runs |
| `created_at` | TIMESTAMPTZ | NOT NULL | Creation timestamp |
| `updated_at` | TIMESTAMPTZ | NOT NULL | Last update timestamp |

### 5.2 `recurring_transfer_executions` Table

| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | UUID | PRIMARY KEY | Execution record identifier |
| `recurring_transfer_id`| UUID | NOT NULL, FK -> `recurring_transfers` | Parent schedule |
| `execution_key` | VARCHAR(64) | NOT NULL | Deterministic slot key |
| `scheduled_for` | TIMESTAMPTZ | NOT NULL | Scheduled slot timestamp |
| `transaction_id` | UUID | NULL, FK -> `transactions`, UNIQUE | Created transaction if SUCCESS |
| `status` | VARCHAR(20) | NOT NULL, CHECK IN ('SUCCESS','FAILED') | Execution outcome |
| `failure_reason` | TEXT | NULL | Reason if FAILED |
| `executed_at` | TIMESTAMPTZ | NOT NULL | Execution attempt timestamp |

---

## 6. Observability & Security

### 6.1 Micrometer Metrics (`com.parth.ledger.observability.metrics.LedgerMetrics`)

All metrics are emitted via after-commit synchronization with strictly bounded enum tag keys:

- `ledger.recurring.created` (tags: `frequency`)
- `ledger.recurring.executed` (tags: `frequency`, `outcome=SUCCESS`)
- `ledger.recurring.failed` (tags: `frequency`, `outcome=FAILED`)
- `ledger.recurring.cancelled` (tags: `status`)
- `ledger.recurring.processing.duration` (Timer, tags: `frequency`, `outcome`)

### 6.2 Audit Trail Integration

The audit trail records explicit, tamper-evident security entries:
- `RECURRING_TRANSFER_CREATED`
- `RECURRING_TRANSFER_PAUSED`
- `RECURRING_TRANSFER_RESUMED`
- `RECURRING_TRANSFER_CANCELLED`

### 6.3 Identifier Masking

In adherence to V2.4 logging guidelines, all logs sanitize and mask sensitive identifiers:
- `MaskingUtils.maskAccountId(scheduleId)`
- Account numbers formatted with masked prefixes (e.g. `•••• 1234`).
