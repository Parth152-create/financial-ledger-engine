# Transaction Reversal API Documentation

The Transaction Reversal API provides an atomic, concurrency-safe, compensating double-entry accounting operation that inverts the financial effect of a previously completed transaction.

---

## Core Financial Accounting Principles

1. **History Is Immutable**:
   - Reversals never execute `UPDATE transactions SET status = 'REVERSED'` or `DELETE FROM transactions`.
   - Historical ledger entries are never modified or deleted.
   - A reversal is a **new financial transaction** (`transaction_type = 'REVERSAL'`) with its own unique `transactionId`, compensating ledger entries, and an authoritative reference (`reverses_transaction_id`) pointing to the original transaction.

2. **Compensating Double-Entry Inversion**:
   - The financial movement is inverted symmetrically:
     - The account that was credited in the original transaction is debited in the reversal.
     - The account that was debited in the original transaction is credited in the reversal.
   - For all operations (`TRANSFER`, `DEPOSIT`, `WITHDRAWAL`), the reversal creates balanced debit and credit ledger entries:
     $$\sum(\text{debits}) = \sum(\text{credits}) = \text{amount}$$

3. **Database-Enforced Uniqueness**:
   - PostgreSQL enforces at most one reversal transaction per original transaction via a partial unique index:
     ```sql
     CREATE UNIQUE INDEX uk_transactions_reverses_transaction_id
         ON transactions(reverses_transaction_id)
         WHERE reverses_transaction_id IS NOT NULL;
     ```

4. **Balance Safety**:
   - The account debited by the compensating transaction must possess sufficient balance to cover the reversal amount. Overdrafts are strictly prohibited.

---

## Endpoint Specification

### `POST /api/v1/transactions/{transactionId}/reversal`

Executes a compensating reversal for the specified transaction within a single authoritative PostgreSQL database transaction.

### Path Parameters

| Parameter | Type | Required | Description |
|---|---|---|---|
| `transactionId` | `UUID` | **Yes** | Unique identifier of the original transaction to reverse |

### Headers

| Header | Type | Required | Description |
|---|---|---|---|
| `Content-Type` | `string` | **Yes** | Must be `application/json` |
| `Idempotency-Key` | `string` | **Yes** | Client-generated unique UUID identifier for idempotent retry safety |

---

## Request Body

```json
{
  "reason": "Customer requested cancellation before settlement"
}
```

### Field Constraints

| Field | Type | Constraints | Description |
|---|---|---|---|
| `reason` | `string` | Optional, max 255 chars | Human-readable explanation for operational audit records |

---

## Success Response

### `200 OK`

```json
{
  "reversalTransactionId": "f7d3a2e1-4567-4890-a123-bcde45678901",
  "originalTransactionId": "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d",
  "status": "COMPLETED",
  "sourceAccountId": "9d9bfd39-7ece-475b-9980-961e3667a839",
  "destinationAccountId": "3193cba2-c520-43b8-8c32-d7b6e920fea9",
  "amount": 5000.0000,
  "currency": "INR",
  "reversalReason": "Customer requested cancellation before settlement",
  "createdAt": "2026-09-29T10:15:30.123456Z",
  "completedAt": "2026-09-29T10:15:30.145678Z"
}
```

### Response Field Descriptions

| Field | Type | Description |
|---|---|---|
| `reversalTransactionId` | `UUID` | Unique identifier of the newly created compensating transaction |
| `originalTransactionId` | `UUID` | Identifier of the original transaction that was compensated |
| `status` | `string` | Reversal transaction status (`COMPLETED`) |
| `sourceAccountId` | `UUID` | Account debited by the compensating transaction (original destination) |
| `destinationAccountId` | `UUID` | Account credited by the compensating transaction (original source) |
| `amount` | `BigDecimal` | Compensating amount in INR (scaled to 4 decimal places) |
| `currency` | `string` | Transaction currency (`INR`) |
| `reversalReason` | `string` | Optional reason provided in the request |
| `createdAt` | `Instant` | ISO-8601 timestamp when the reversal transaction was initiated |
| `completedAt` | `Instant` | ISO-8601 timestamp when the reversal transaction committed |

---

## Transaction Detail Endpoint

### `GET /api/v1/transactions/{transactionId}`

Fetches full transaction details including reversal references.

### Response `200 OK`

```json
{
  "transactionId": "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d",
  "status": "COMPLETED",
  "sourceAccountId": "3193cba2-c520-43b8-8c32-d7b6e920fea9",
  "destinationAccountId": "9d9bfd39-7ece-475b-9980-961e3667a839",
  "amount": 5000.0000,
  "currency": "INR",
  "transactionType": "TRANSFER",
  "description": "Invoice payment #4092",
  "createdAt": "2026-09-29T10:00:00.000000Z",
  "completedAt": "2026-09-29T10:00:00.025000Z",
  "reversesTransactionId": null
}
```

---

## Idempotency Semantics

- **Same Idempotency-Key Replay**:
  - Replaying a request with the exact same `Idempotency-Key` returns the cached 200 OK response without re-executing accounting logic, without mutating balances again, and without inserting duplicate audit events.
- **Different Idempotency-Key Replay**:
  - Attempting to reverse an already reversed transaction using a *different* `Idempotency-Key` returns `409 Conflict` (`TransactionAlreadyReversedException`).
- **Concurrent Races**:
  - When concurrent threads attempt to reverse the same transaction simultaneously, pessimistic row-level locking (`SELECT ... FOR UPDATE`) and database partial unique indexing ensure exactly one winner succeeds while concurrent contenders receive `409 Conflict`.

---

## Operational Audit Trail Integration

Every successful reversal atomically writes a `TRANSACTION_REVERSED` event to the `audit_events` table within the same database transaction:

```json
{
  "eventId": "e9a8b7c6-d5e4-3210-fedc-ba9876543210",
  "actorUserId": "12345678-1234-1234-1234-123456789012",
  "actorRole": "USER",
  "eventType": "TRANSACTION_REVERSED",
  "entityType": "TRANSACTION",
  "entityId": "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d",
  "metadata": {
    "reversalTransactionId": "f7d3a2e1-4567-4890-a123-bcde45678901",
    "originalTransactionId": "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d",
    "amount": 5000.0,
    "currency": "INR",
    "transactionType": "TRANSFER",
    "reason": "Customer requested cancellation before settlement"
  },
  "occurredAt": "2026-09-29T10:15:30.130000Z"
}
```

---

## Error Handling Matrix

| HTTP Status | Error Name | Cause | Response Message |
|---|---|---|---|
| `400 Bad Request` | `MethodArgumentNotValidException` | Reason exceeds 255 chars or missing Idempotency-Key | Validation failure details |
| `401 Unauthorized` | `AuthenticationCredentialsNotFoundException` | Session expired or unauthenticated | Authentication required |
| `403 Forbidden` | `AccessDeniedException` | User neither owns the involved accounts nor holds `ADMIN` role | User is not authorized to reverse this transaction |
| `404 Not Found` | `TransactionNotFoundException` | `transactionId` does not match an existing transaction | Transaction not found with ID: {id} |
| `409 Conflict` | `TransactionAlreadyReversedException` | Transaction has already been reversed | Transaction {id} has already been reversed |
| `422 Unprocessable` | `TransactionNotReversibleException` | Transaction is not COMPLETED, is a REVERSAL, or is SYSTEM_FUNDING | Cannot reverse transaction: {reason} |
| `422 Unprocessable` | `InsufficientBalanceException` | Debited account lacks funds to compensate | Insufficient balance in account: {id} |
| `422 Unprocessable` | `AccountFrozenException` | Debited or credited account is FROZEN | Account is frozen: {id} |
| `422 Unprocessable` | `AccountClosedException` | Debited or credited account is CLOSED | Account is closed: {id} |
| `429 Too Many Requests` | `RateLimitExceededException` | User exceeds financial mutation rate limits | Financial transaction rate limit exceeded |
| `500 Internal Error` | Internal Server Error | Transient unhandled failure | An unexpected error occurred |
