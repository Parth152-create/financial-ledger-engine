# Withdrawal API Documentation

The Withdrawal API provides an atomic, concurrency-safe, double-entry financial operation to withdraw funds from an authenticated user's checking account to the platform-owned `SYSTEM_CLEARING` account.

> **CORE FINANCIAL PRINCIPLE**:
> A withdrawal moves funds from a `USER_CHECKING` account to `SYSTEM_CLEARING`; it decreases the user's account balance and increases the system clearing account balance. Balances are never directly mutated without corresponding immutable double-entry ledger entries.

---

## Endpoint Specification

### `POST /api/v1/withdrawals`

Executes a withdrawal from the authenticated user's `USER_CHECKING` account into the platform `SYSTEM_CLEARING` account within a single authoritative PostgreSQL database transaction.

### Authentication & Authorization

- Requires user authentication via session/OAuth2. Unauthenticated requests are rejected with `401 Unauthorized`.
- The source account must belong to the authenticated user and have type `USER_CHECKING`.
- If the source account does not exist, belongs to another user, or is the platform `SYSTEM_CLEARING` account, the request is rejected with `404 Not Found` (providing uniform protection against account enumeration).

### Headers

| Header | Type | Required | Description |
|---|---|---|---|
| `Content-Type` | `string` | **Yes** | Must be `application/json` |
| `Idempotency-Key` | `string` | **Yes** | Unique client-generated transaction identifier |

> **IMPORTANT**: The `Idempotency-Key` must be supplied via the HTTP request header. It is intentionally excluded from the request body.

---

## Request Body

```json
{
  "accountId": "2057aa37-194a-4448-8d91-1e8e8b431937",
  "amount": 100.0000,
  "currency": "USD",
  "description": "ATM cash withdrawal"
}
```

### Field Constraints

| Field | Type | Constraints | Description |
|---|---|---|---|
| `accountId` | `UUID` | Required, Not Null | ID of the source user checking account |
| `amount` | `BigDecimal` | Required, > 0.0000, scale ≤ 4 | Withdrawal amount in currency; max 4 decimal digits |
| `currency` | `string` | Required, 3-letter ISO code (`USD`) | Currency code; must match source account and clearing account |
| `description` | `string` | Optional, max 255 chars | Human-readable memo or reference |

### Disallowed Client Input

The client is **not** permitted to provide:
- `destinationAccount` (always deterministically bound to `SYSTEM_CLEARING`)
- `transactionType` (always `WITHDRAWAL`)
- `initiatedByUser` / `userId` (always resolved from the authenticated security principal)
- `balance` / `accountStatus` / `accountType`
- `idempotencyKey` in the JSON request body (header-only)

---

## Responses

### `201 Created` — Successful New Withdrawal

Returned when a withdrawal is processed and committed for the first time.

```json
{
  "transactionId": "78d7fd9e-9a9b-401b-96ff-5a4398a7d858",
  "transactionType": "WITHDRAWAL",
  "status": "COMPLETED",
  "sourceAccountId": "2057aa37-194a-4448-8d91-1e8e8b431937",
  "destinationAccountId": "00000000-0000-0000-0000-000000000001",
  "amount": 100.0000,
  "currency": "USD",
  "description": "ATM cash withdrawal",
  "idempotencyKey": "wdr-basic-001",
  "initiatedByUserId": "c0a80123-9999-0000-0000-000000000001",
  "createdAt": "2026-09-24T09:40:10.123456Z",
  "completedAt": "2026-09-24T09:40:10.145678Z"
}
```

### `200 OK` — Idempotent Replay

Returned when an identical retry is submitted with the same `Idempotency-Key` and matching parameters. Returns the identical transaction response without creating duplicate financial effects.

### Error Responses

| Status Code | Reason | Cause |
|---|---|---|
| `400 Bad Request` | Validation Error | Missing required fields, amount ≤ 0, scale > 4, invalid ISO currency code format |
| `400 Bad Request` | Currency Mismatch | Request currency does not match source account or clearing account currency |
| `400 Bad Request` | Missing Header | Missing or blank `Idempotency-Key` header |
| `400 Bad Request` | Malformed JSON | Syntax error in JSON payload or malformed UUID |
| `401 Unauthorized` | Unauthenticated | No active session or missing authentication |
| `404 Not Found` | Not Found / Unauthorized | Source account does not exist, belongs to another user, or is `SYSTEM_CLEARING` |
| `409 Conflict` | Idempotency Conflict | Same `Idempotency-Key` reused with different amount, currency, account, or description |
| `422 Unprocessable Entity` | Insufficient Balance | Source account balance is less than requested withdrawal amount |
| `422 Unprocessable Entity` | Ineligible Account Status | Source account is `FROZEN` or `CLOSED` |
| `500 Internal Server Error` | Unbalanced Ledger | Critical accounting invariant failure (total debits ≠ total credits) |

---

## Architectural Model & Double-Entry Bookkeeping

Withdrawals use strict double-entry bookkeeping:

```
USER_CHECKING   (Source Account)
      |
      | DEBIT X
      v
SYSTEM_CLEARING (Destination Account)
        CREDIT X
```

For a withdrawal of amount `X`:
1. `USER_CHECKING`:
   - Balance decreases by `X`: $\text{balance} \leftarrow \text{balance} - X$
   - Ledger Entry: `entry_type = DEBIT`, `amount = X`, `currency = request currency`
2. `SYSTEM_CLEARING`:
   - Balance increases by `X`: $\text{balance} \leftarrow \text{balance} + X$
   - Ledger Entry: `entry_type = CREDIT`, `amount = X`, `currency = request currency`

### Invariants:
- `Total Debits == Total Credits == X` verified before commit.
- Both ledger entries are persisted in the append-only, PostgreSQL trigger-enforced immutable `ledger_entries` table.
- Account snapshot balance and cumulative ledger balance (`credits - debits`) remain 100% reconciled on both accounts.

---

## Balance Sufficiency & Account Lifecycle

- Prior to balance mutation, the engine verifies:
  $$\text{Source Checking Balance} \ge \text{Withdrawal Amount}$$
- If the source balance is insufficient:
  - Rejected with `422 Unprocessable Entity` (`InsufficientBalanceException`).
  - No balances are mutated.
  - No transaction record is created.
  - No ledger entries are created.
  - The source balance never becomes negative (enforced in application and DB constraint `chk_accounts_balance_non_negative`).
- `SYSTEM_CLEARING` is the recipient of funds; therefore, it does not require an outgoing sufficiency check.
- Source accounts in status `FROZEN` or `CLOSED` are rejected with `422 Unprocessable Entity`.

---

## Currency Restrictions

- Multi-currency withdrawals and FX conversions are not supported in V9.
- The withdrawal currency must match:
  1. The source account currency
  2. The `SYSTEM_CLEARING` account currency (`USD`)
- Only `USD` withdrawals from `USD` accounts are currently permitted. Any currency mismatch is rejected before financial mutation.

---

## Concurrency & Deterministic Locking Strategy

- Concurrency is managed via PostgreSQL pessimistic row-level locking (`SELECT ... FOR UPDATE` / `LockModeType.PESSIMISTIC_WRITE`).
- To prevent circular-wait deadlocks between concurrent operations on the same accounts:
  1. Determine `min(sourceId, clearingId)` and `max(sourceId, clearingId)` lexicographically by UUID.
  2. Always lock `min` first, then `max` second.
- Because deposits, transfers, and withdrawals all follow the identical lexicographical UUID locking order, concurrent operations across deposits, transfers, and withdrawals never deadlock.
- Post-lock idempotency re-checks ensure that concurrent duplicate requests serialize safely without duplicate financial mutation.

---

## Idempotency Architecture

1. **Redis Fast-Path Cache**:
   - Stores serialized `TransactionResponseDto` keyed by `ledger:idempotency:<key>` with configurable TTL (default 24h).
   - Fast return for cached retries.
   - Cache hit validates source account ownership before returning response.
   - **Fail-Open**: If Redis is offline or errors occur, the engine logs a warning and falls back transparently to authoritative PostgreSQL.
2. **PostgreSQL Authoritative Idempotency**:
   - Enforced by database constraint `uk_transactions_idempotency_key UNIQUE (idempotency_key)`.
   - Pre-lock and post-lock checks inspect `transactions` table.
   - Mismatched parameters result in `409 Conflict`.
   - Redis caching occurs exclusively within Spring transaction synchronization `afterCommit()`, ensuring Redis failures never roll back committed financial transactions.

---

## Failure & Rollback Semantics

The entire withdrawal operation runs inside an atomic Spring `@Transactional` database transaction:
- If an error, database constraint violation, or exception occurs at any point before commit:
  - Database transaction rolls back completely.
  - Source account balance remains untouched.
  - System clearing balance remains untouched.
  - Transaction row is not committed.
  - Ledger entries are not committed.
  - Redis cache is not populated.
  - The client may safely retry with the same `Idempotency-Key`.
