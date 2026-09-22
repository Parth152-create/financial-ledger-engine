# Transfer API Documentation

The Transfer API provides an atomic, concurrency-safe, double-entry financial transfer operation between two accounts.

---

## Endpoint Specification

### `POST /api/v1/transfers`

Executes a balance transfer between a source account and a destination account within a single authoritative PostgreSQL transaction.

### Headers

| Header | Type | Required | Description |
|---|---|---|---|
| `Content-Type` | `string` | **Yes** | Must be `application/json` |
| `Idempotency-Key` | `string` | **Yes** | Unique client-generated transaction identifier (e.g., UUID or unique reference string) |

> **IMPORTANT**: The `Idempotency-Key` must be supplied via the HTTP request header. It is intentionally excluded from the request body.

---

## Request Body

```json
{
  "sourceAccountId": "c0a80123-0000-0000-0000-000000000001",
  "destinationAccountId": "c0a80123-0000-0000-0000-000000000002",
  "amount": 100.0000,
  "currency": "USD"
}
```

### Field Constraints

| Field | Type | Constraints | Description |
|---|---|---|---|
| `sourceAccountId` | `UUID` | Required, Not Null | ID of the debited account |
| `destinationAccountId` | `UUID` | Required, Not Null | ID of the credited account (must differ from `sourceAccountId`) |
| `amount` | `BigDecimal` | Required, > 0.0000, max 4 decimal digits | Transfer amount in account currency |
| `currency` | `string` | Required, 3-letter ISO code (e.g. `USD`) | Currency code; must match both accounts |

---

## Success Response

### `200 OK`

```json
{
  "transactionId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
  "status": "COMPLETED",
  "sourceAccountId": "c0a80123-0000-0000-0000-000000000001",
  "destinationAccountId": "c0a80123-0000-0000-0000-000000000002",
  "amount": 100.0000,
  "currency": "USD",
  "createdAt": "2026-09-22T11:45:00.123456Z",
  "completedAt": "2026-09-22T11:45:00.145678Z"
}
```

---

## Idempotency & Retry Behavior

1. **First Request**:
   - The transfer executes atomically: accounts are locked deterministically, balance is verified, source account is debited, destination account is credited, transaction record (`PENDING` -> `COMPLETED`) is stored, balanced `DEBIT` and `CREDIT` ledger entries are persisted, and the transaction commits.
2. **Identical Retry** (`Idempotency-Key` matches, same payload):
   - The engine detects the previously completed transaction and immediately returns the stored result (`200 OK`) without re-executing balance mutations or generating redundant ledger entries.
   - **Financial Guarantee**: Idempotent financial effect under client retries.
3. **Idempotency Conflict** (`Idempotency-Key` reused with different parameters):
   - If an existing key is presented with a different `sourceAccountId`, `destinationAccountId`, `amount`, or `currency`, the request is rejected with `409 Conflict`.
4. **Concurrent Idempotent Requests**:
   - Deterministic row locking serializes concurrent execution on the involved accounts. Once the first request commits, the waiting thread detects the committed record and returns the existing transaction result without duplicate transfers. If independent accounts race with the same key, PostgreSQL's `UNIQUE(idempotency_key)` constraint rejects the duplicate with `409 Conflict`.

---

## Concurrency & Locking Strategy

- **PostgreSQL Row-Level Locking**: Accounts are locked exclusively using `SELECT ... FOR UPDATE` (`LockModeType.PESSIMISTIC_WRITE`) prior to modifying balances.
- **Deterministic Lock Ordering**:
  - To prevent circular-wait deadlocks between opposing transfers (e.g., concurrent `A -> B` and `B -> A`), account UUIDs are compared lexicographically:
    1. Lock `min(sourceAccountId, destinationAccountId)` first.
    2. Lock `max(sourceAccountId, destinationAccountId)` second.
  - Both transactions acquire locks in the exact same order, preventing deadlock cycles between the two accounts.

---

## Double-Entry Accounting Invariant

Every successful transfer produces exactly two ledger entries in the `ledger_entries` table:
1. `DEBIT` on `sourceAccountId` for `amount`
2. `CREDIT` on `destinationAccountId` for `amount`

Before commit, the service verifies `totalDebits == totalCredits`. If this accounting invariant fails, the entire transaction rolls back (`500 Internal Server Error`).

---

## Error Responses & HTTP Status Code Mapping

Error responses return a structured JSON body without stack traces:

```json
{
  "timestamp": "2026-09-22T11:45:00.123456Z",
  "status": 422,
  "error": "Unprocessable Content",
  "message": "Insufficient balance in source account c0a80123-0000-0000-0000-000000000001: available 50.0000, required 100.0000",
  "path": "/api/v1/transfers"
}
```

| HTTP Status | Condition / Exception | Description |
|---|---|---|
| `400 Bad Request` | `SameAccountTransferException` | Source and destination account IDs are identical |
| `400 Bad Request` | `CurrencyMismatchException` | Request currency or account currencies do not match |
| `400 Bad Request` | `InvalidAmountException` | Transfer amount is zero, negative, or invalid scale |
| `400 Bad Request` | `MissingRequestHeaderException` | `Idempotency-Key` header missing or blank |
| `400 Bad Request` | `MethodArgumentNotValidException` | Bean validation errors on request fields |
| `400 Bad Request` | `HttpMessageNotReadableException` | Malformed JSON request body |
| `404 Not Found` | `AccountNotFoundException` | Source or destination account UUID does not exist |
| `409 Conflict` | `IdempotencyConflictException` | Idempotency key reused with different transfer parameters |
| `409 Conflict` | `DataIntegrityViolationException` | Database unique constraint violation on `idempotency_key` |
| `422 Unprocessable Content` | `InsufficientBalanceException` | Source account balance is less than transfer amount |
| `500 Internal Server Error` | `UnbalancedLedgerException` | Total debits do not equal total credits |
| `500 Internal Server Error` | Unhandled `Exception` | Internal system error |
