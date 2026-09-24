# Deposit API Documentation

The Deposit API provides an atomic, concurrency-safe, double-entry financial funding operation to deposit funds into user checking accounts from the platform-owned `SYSTEM_CLEARING` account.

> **CORE FINANCIAL PRINCIPLE**:
> A deposit transfers funds from `SYSTEM_CLEARING` to a `USER_CHECKING` account; it does not create money by directly increasing the user account balance.

---

## Endpoint Specification

### `POST /api/v1/deposits`

Executes a deposit from the platform `SYSTEM_CLEARING` account into the authenticated user's `USER_CHECKING` account within a single authoritative PostgreSQL database transaction.

### Authentication

- Requires user authentication via session/OAuth2.
- The destination account must belong to the authenticated user.
- Unauthenticated requests are rejected with `401 Unauthorized`.
- Attempting to deposit into an account belonging to another user is rejected with `404 Not Found` (preventing account enumeration).

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
  "description": "Initial funding"
}
```

### Field Constraints

| Field | Type | Constraints | Description |
|---|---|---|---|
| `accountId` | `UUID` | Required, Not Null | ID of the destination user checking account |
| `amount` | `BigDecimal` | Required, > 0.0000, scale ≤ 4 | Deposit amount in currency; max 4 decimal digits |
| `currency` | `string` | Required, 3-letter ISO code (`USD`) | Currency code; must match destination and clearing account |
| `description` | `string` | Optional, max 255 chars | Human-readable memo or reference |

### Disallowed Client Input

The client is **not** permitted to provide:
- `sourceAccount` (always deterministically bound to `SYSTEM_CLEARING`)
- `transactionType` (always `DEPOSIT`)
- `initiatedByUser` / `userId` (always resolved from the authenticated security principal)
- `balance` / `accountStatus` / `accountType`
- `idempotencyKey` in the JSON request body (header-only)

---

## Responses

### `201 Created` — Successful New Deposit

Returned when a deposit is processed and committed for the first time.

```json
{
  "transactionId": "68d7fd9e-9a9b-401b-96ff-5a4398a7d857",
  "transactionType": "DEPOSIT",
  "status": "COMPLETED",
  "sourceAccountId": "00000000-0000-0000-0000-000000000001",
  "destinationAccountId": "2057aa37-194a-4448-8d91-1e8e8b431937",
  "amount": 100.0000,
  "currency": "USD",
  "description": "Initial funding",
  "idempotencyKey": "dep-basic-001",
  "initiatedByUserId": "c0a80123-9999-0000-0000-000000000001",
  "createdAt": "2026-09-24T08:10:10.123456Z",
  "completedAt": "2026-09-24T08:10:10.145678Z"
}
```

### `200 OK` — Idempotent Replay

Returned when an identical retry is submitted with the same `Idempotency-Key` and matching parameters. Returns the identical transaction response without creating duplicate financial effects.

### Error Responses

| Status Code | Reason | Cause |
|---|---|---|
| `400 Bad Request` | Validation Error | Missing required fields, amount ≤ 0, scale > 4, invalid ISO currency code format |
| `400 Bad Request` | Currency Mismatch | Request currency does not match destination account or clearing account currency |
| `400 Bad Request` | Invalid Account Type | Destination account is `SYSTEM_CLEARING` instead of `USER_CHECKING` |
| `400 Bad Request` | Missing Header | Missing or blank `Idempotency-Key` header |
| `401 Unauthorized` | Unauthenticated | No active session or missing authentication |
| `404 Not Found` | Not Found / Unauthorized | Destination account does not exist or does not belong to the authenticated user |
| `409 Conflict` | Idempotency Conflict | Same `Idempotency-Key` reused with different amount, currency, account, or description |
| `422 Unprocessable Entity` | Insufficient Balance | System clearing account balance is less than requested deposit amount |
| `422 Unprocessable Entity` | Ineligible Account Status | Destination account is `FROZEN` or `CLOSED` |

---

## Architectural Model & Double-Entry Bookkeeping

Deposits use double-entry bookkeeping:

```
SYSTEM_CLEARING (Platform Account)
      |
      | DEBIT X
      v
USER_CHECKING   (User Account)
        CREDIT X
```

For a deposit of amount `X`:
1. `SYSTEM_CLEARING`:
   - Balance decreases by `X`
   - Ledger Entry: `entry_type = DEBIT`, `amount = X`, `currency = deposit currency`
2. `USER_CHECKING`:
   - Balance increases by `X`
   - Ledger Entry: `entry_type = CREDIT`, `amount = X`, `currency = deposit currency`

### Invariants:
- `Total Debits == Total Credits` verified before commit.
- Both ledger entries are persisted in the append-only, PostgreSQL trigger-enforced immutable `ledger_entries` table.
- Account snapshot balance and cumulative ledger balance (`credits - debits`) remain 100% reconciled on both accounts.

---

## System Clearing Balance Semantics

- The `SYSTEM_CLEARING` account is a real accounting entity with real balance state.
- Before mutating balances, the engine verifies:
  $$\text{Clearing Balance} \ge \text{Deposit Amount}$$
- If the clearing balance is insufficient:
  - Deposit is rejected with `422 Unprocessable Entity` (`InsufficientBalanceException`).
  - No balances are mutated.
  - No transaction record is created.
  - No ledger entries are created.
  - The clearing balance never becomes negative (also enforced by DB constraint `chk_accounts_balance_non_negative`).

---

## Currency Restrictions

- Multi-currency deposits and FX conversions are not supported in V6.
- The deposit currency must match:
  1. The destination account currency
  2. The `SYSTEM_CLEARING` account currency (`USD`)
- Only `USD` deposits into `USD` accounts are currently permitted. Any currency mismatch is rejected before financial mutation.

---

## Concurrency & Deterministic Locking Strategy

- Concurrency is managed via pessimistic row-level locking (`SELECT ... FOR UPDATE` / `LockModeType.PESSIMISTIC_WRITE`).
- To prevent circular-wait deadlocks between concurrent operations on the same accounts:
  1. Determine `min(clearingId, destinationId)` and `max(clearingId, destinationId)` lexicographically by UUID.
  2. Always lock `min` first, then `max` second.
- Both accounts are locked before any balance checks or mutations occur.
- Post-lock idempotency re-checks ensure that concurrent identical requests serialize safely without duplicate financial mutation.

---

## Idempotency Architecture

1. **Redis Fast-Path Cache**:
   - Stores serialized `TransactionResponseDto` keyed by `ledger:idempotency:<key>` with configurable TTL (default 24h).
   - Fast return for cached retries.
   - Cache hit validates destination ownership before returning response.
   - **Fail-Open**: If Redis is offline or errors occur, the engine logs a warning and falls back transparently to authoritative PostgreSQL.
2. **PostgreSQL Authoritative Idempotency**:
   - Enforced by database constraint `uk_transactions_idempotency_key UNIQUE (idempotency_key)`.
   - Pre-lock and post-lock checks inspect `transactions` table.
   - Mismatched parameters result in `409 Conflict`.
   - Redis caching occurs exclusively within Spring transaction synchronization `afterCommit()`, ensuring Redis failures never roll back committed financial transactions.

---

## Failure & Rollback Semantics

The entire deposit operation runs inside an atomic Spring `@Transactional` database transaction:
- If an error or constraint violation occurs at any point before commit:
  - Database transaction rolls back completely.
  - Destination account balance remains unchanged.
  - System clearing balance remains unchanged.
  - Transaction row is not committed.
  - Ledger entries are not committed.
  - Redis cache is not populated.
  - The client may safely retry with the same `Idempotency-Key`.
