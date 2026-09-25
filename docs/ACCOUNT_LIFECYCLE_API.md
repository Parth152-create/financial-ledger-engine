# V10 Account Lifecycle & Administrative Operations API Specification

## 1. Overview & Architecture

The **Account Lifecycle & Administrative Operations** (V10) milestone introduces state-machine controls and administrative boundaries for checking accounts within the Financial Ledger Engine.

Accounts transition through three explicit lifecycle states:
- `ACTIVE`: The standard operational state. The account can initiate and receive transfers, process deposits, and execute withdrawals.
- `FROZEN`: An administrative lock state. The account is suspended from all debits and credits (outgoing transfers, incoming transfers, deposits, withdrawals). Account balance remains unchanged and is preserved. Statements, transaction histories, and reconciliation checks remain fully accessible in read-only mode.
- `CLOSED`: The terminal lifecycle state. The account is permanently shut down. It cannot be reopened or transitioned to any other state. All financial transactions are permanently blocked. Closure is strictly contingent upon a zero-balance invariant (`balance == 0.0000`).

```
                ┌────────────────────────────────┐
                │                                │
                │             ACTIVE             │◄─────────┐
                │   (Normal Financial Engine)    │          │
                │                                │          │
                └───────┬────────────────▲───────┘          │
                        │                │                  │
         POST /freeze   │                │ POST /unfreeze   │
         (ROLE_ADMIN)   │                │ (ROLE_ADMIN)     │
                        ▼                │                  │
                ┌────────────────────────┴───────┐          │
                │                                │          │
                │             FROZEN             │          │
                │     (Suspended Operations)     │          │
                │                                │          │
                └───────┬────────────────────────┘          │
                        │                                   │
         POST /close    │                POST /close        │
         (Owner only,   │                (Owner only,       │
         balance == 0)  │                balance == 0)      │
                        │                                   │
                        ▼                                   │
                ┌────────────────────────────────┐          │
                │                                │          │
                │             CLOSED             │──────────┘
                │    (Terminal State, Zero-Bal)  │ (STRICTLY PROHIBITED
                │                                │  TERMINAL STATE)
                └────────────────────────────────┘
```

---

## 2. Authentication & Authorization Model

The authorization model enforces minimal, explicit role assignment using Spring Security:

### 2.1 Role Representation
- **Standard User**: Authenticated OAuth2/OIDC user assigned default `ROLE_USER`.
- **Administrator**: Authenticated user having email listed in `ledger.security.admin-emails` (configured via environment variable `LEDGER_ADMIN_EMAILS`, defaulting to `admin@ledger.com`). Evaluated at token verification/login and assigned `ROLE_ADMIN`.

### 2.2 Permissions Matrix

| Endpoint | Method | Permitted Principal | Conditions / Invariants |
|:---|:---|:---|:---|
| `/api/v1/accounts/{accountId}/freeze` | `POST` | `ROLE_ADMIN` | Target must be `USER_CHECKING`. Idempotent on already `FROZEN`. |
| `/api/v1/accounts/{accountId}/unfreeze` | `POST` | `ROLE_ADMIN` | Target must be `USER_CHECKING` and `FROZEN`. Rejects `ACTIVE` (422). |
| `/api/v1/accounts/{accountId}/close` | `POST` | Account Owner (`ROLE_USER` or `ROLE_ADMIN`) | Caller must own the account. Balance must be `0.0000`. Idempotent on already `CLOSED`. |

---

## 3. Account Ownership & Anti-Enumeration

1. **Owner-Level Account Closure (`POST /close`)**:
   - The authenticated user must be the registered owner of the account (`account.getUser().getId().equals(currentUser.getId())`).
   - If an account does not exist or belongs to another user, the API responds with **`404 Not Found`** (`AccountNotFoundException`). This prevents malicious probing or enumeration of foreign account UUIDs.

2. **Administrative Operations (`POST /freeze`, `POST /unfreeze`)**:
   - Authorized administrators can target any `USER_CHECKING` account across the engine.
   - If the account ID does not exist, the API returns **`404 Not Found`**.
   - If a caller lacks `ROLE_ADMIN`, Spring Security returns **`403 Forbidden`** (or **`401 Unauthorized`** if unauthenticated).

3. **Protection of `SYSTEM_CLEARING` Accounts**:
   - `SYSTEM_CLEARING` accounts (`account_type = 'SYSTEM_CLEARING'`) are immutable infrastructure accounts.
   - Any attempt by an admin to freeze or unfreeze `SYSTEM_CLEARING` is rejected with **`400 Bad Request`** (`InvalidAccountTypeException: Cannot manage lifecycle of SYSTEM_CLEARING account`).
   - Any close request for `SYSTEM_CLEARING` by a user returns **`404 Not Found`** because user ownership does not match `NULL`.
   - Database-level triggers further prevent `SYSTEM_CLEARING` status mutations.

---

## 4. API Endpoints

### 4.1 Freeze Account

Suspends an active checking account from participating in debits and credits.

- **URL**: `POST /api/v1/accounts/{accountId}/freeze`
- **Security**: Requires `ROLE_ADMIN`.
- **Idempotency**: State-transition idempotent. Freezing an already `FROZEN` account returns `200 OK` with the current representation.

#### State Transition Logic:
- `ACTIVE` $\rightarrow$ `FROZEN`: Updates status to `FROZEN`, commits, and returns `200 OK`.
- `FROZEN` $\rightarrow$ `FROZEN`: No-op idempotent return `200 OK`.
- `CLOSED` $\rightarrow$ `FROZEN`: Rejected with `422 Unprocessable Entity` (`AccountClosedException: Account is CLOSED and cannot be modified`).
- `SYSTEM_CLEARING`: Rejected with `400 Bad Request` (`InvalidAccountTypeException`).

#### Example Request:
```http
POST /api/v1/accounts/68c85770-ff61-4fa3-80a2-aa59c2c62c26/freeze HTTP/1.1
Host: localhost:8080
Authorization: Bearer <ADMIN_JWT>
```

#### Example Response (`200 OK`):
```json
{
  "id": "68c85770-ff61-4fa3-80a2-aa59c2c62c26",
  "accountNumber": "ACCT-20260924-A1B2C3",
  "currency": "USD",
  "balance": 1500.0000,
  "accountType": "USER_CHECKING",
  "status": "FROZEN",
  "createdAt": "2026-09-24T10:15:30Z",
  "updatedAt": "2026-09-24T18:30:00Z"
}
```

---

### 4.2 Unfreeze Account

Restores a frozen checking account back to active status, re-enabling financial operations.

- **URL**: `POST /api/v1/accounts/{accountId}/unfreeze`
- **Security**: Requires `ROLE_ADMIN`.
- **Idempotency**: Strict transition requirement. Unfreezing an already `ACTIVE` account is invalid and rejected.

#### State Transition Logic:
- `FROZEN` $\rightarrow$ `ACTIVE`: Updates status to `ACTIVE`, commits, and returns `200 OK`.
- `ACTIVE` $\rightarrow$ `ACTIVE`: Rejected with `422 Unprocessable Entity` (`AccountStatusException: Account is already ACTIVE`).
- `CLOSED` $\rightarrow$ `ACTIVE`: Rejected with `422 Unprocessable Entity` (`AccountClosedException: Account is CLOSED and cannot be reopened`).
- `SYSTEM_CLEARING`: Rejected with `400 Bad Request` (`InvalidAccountTypeException`).

#### Example Request:
```http
POST /api/v1/accounts/68c85770-ff61-4fa3-80a2-aa59c2c62c26/unfreeze HTTP/1.1
Host: localhost:8080
Authorization: Bearer <ADMIN_JWT>
```

#### Example Response (`200 OK`):
```json
{
  "id": "68c85770-ff61-4fa3-80a2-aa59c2c62c26",
  "accountNumber": "ACCT-20260924-A1B2C3",
  "currency": "USD",
  "balance": 1500.0000,
  "accountType": "USER_CHECKING",
  "status": "ACTIVE",
  "createdAt": "2026-09-24T10:15:30Z",
  "updatedAt": "2026-09-24T18:32:00Z"
}
```

---

### 4.3 Close Account

Permanently closes an account owned by the authenticated caller. Requires zero balance.

- **URL**: `POST /api/v1/accounts/{accountId}/close`
- **Security**: Requires authenticated owner (`ROLE_USER` or `ROLE_ADMIN`).
- **Idempotency**: Closing an already `CLOSED` account is treated as an idempotent no-op and returns `200 OK`.

#### State Transition Logic:
- `ACTIVE` with `balance == 0.0000` $\rightarrow$ `CLOSED`: Updates status to `CLOSED`, commits, and returns `200 OK`.
- `FROZEN` with `balance == 0.0000` $\rightarrow$ `CLOSED`: Updates status to `CLOSED`, commits, and returns `200 OK`.
- `ACTIVE` with `balance != 0.0000` $\rightarrow$ Rejected with `422 Unprocessable Entity` (`AccountStatusException: Cannot close account with non-zero balance: X`).
- `FROZEN` with `balance != 0.0000` $\rightarrow$ Rejected with `422 Unprocessable Entity` (`AccountStatusException: Cannot close account with non-zero balance: X`).
- `CLOSED` $\rightarrow$ `CLOSED`: No-op idempotent return `200 OK`.
- Non-owned account / `SYSTEM_CLEARING`: Returns `404 Not Found`.

#### Example Request:
```http
POST /api/v1/accounts/68c85770-ff61-4fa3-80a2-aa59c2c62c26/close HTTP/1.1
Host: localhost:8080
Authorization: Bearer <USER_JWT>
```

#### Example Response (`200 OK`):
```json
{
  "id": "68c85770-ff61-4fa3-80a2-aa59c2c62c26",
  "accountNumber": "ACCT-20260924-A1B2C3",
  "currency": "USD",
  "balance": 0.0000,
  "accountType": "USER_CHECKING",
  "status": "CLOSED",
  "createdAt": "2026-09-24T10:15:30Z",
  "updatedAt": "2026-09-24T18:35:00Z"
}
```

---

## 5. Concurrency Guarantees & Pessimistic Locking

All state-changing lifecycle operations are executed within isolated Spring `@Transactional` boundaries protected by PostgreSQL pessimistic row-level write locks (`SELECT ... FOR UPDATE` via `AccountRepository.findByIdForUpdate(accountId)`).

### 5.1 Mutual Exclusion Against Financial Engines
The row-level lock serializes lifecycle transitions against all financial mutation engines:
- **Transfer Engine**: Acquires locks on source and destination accounts in deterministic UUID order.
- **Deposit Engine**: Acquires locks on `SYSTEM_CLEARING` and destination account in deterministic UUID order.
- **Withdrawal Engine**: Acquires locks on source account and `SYSTEM_CLEARING` in deterministic UUID order.

### 5.2 Deterministic Race Condition Resolutions

| Concurrent Operations | Execution Serialization Scenario | Deterministic Outcome |
|:---|:---|:---|
| **Freeze + Transfer** | `freeze` acquires lock first | Account transitions to `FROZEN`. Transfer wakes up, reads `FROZEN`, and is rejected with `422 Unprocessable Entity`. |
| | `transfer` acquires lock first | Transfer executes and updates balance. Freeze wakes up, transitions account to `FROZEN`. Both succeed sequentially. |
| **Freeze + Deposit** | `freeze` acquires lock first | Account transitions to `FROZEN`. Deposit wakes up, reads `FROZEN`, and is rejected with `422 Unprocessable Entity`. |
| | `deposit` acquires lock first | Deposit succeeds and credits balance. Freeze wakes up and freezes account. |
| **Freeze + Withdrawal** | `freeze` acquires lock first | Account transitions to `FROZEN`. Withdrawal wakes up, reads `FROZEN`, and is rejected with `422 Unprocessable Entity`. |
| | `withdrawal` acquires lock first | Withdrawal succeeds and debits balance. Freeze wakes up and freezes account. |
| **Close + Transfer** | `close` acquires lock first (zero bal) | Account closed. Transfer wakes up, reads `CLOSED`, and is rejected with `422 Unprocessable Entity`. |
| | `transfer` acquires lock first | Transfer succeeds, increasing balance $> 0$. Close wakes up, detects `balance > 0`, and is rejected with `422 Unprocessable Entity`. |
| **Close + Deposit** | `close` acquires lock first | Account closed. Deposit wakes up, reads `CLOSED`, and is rejected with `422 Unprocessable Entity`. |
| | `deposit` acquires lock first | Deposit succeeds, increasing balance $> 0$. Close wakes up, detects `balance > 0`, and is rejected with `422 Unprocessable Entity`. |
| **Close + Withdrawal (Exact Balance)** | `close` acquires lock first (INR 100.00 bal) | Close evaluates balance (INR 100.00 != 0) and is rejected with `422`. Withdrawal wakes up and debits INR 100.00 to reach 0. |
| | `withdrawal` acquires lock first (INR 100.00 bal) | Withdrawal debits INR 100.00 to balance 0. Close wakes up, observes balance == 0, and closes successfully. |
| **Concurrent Freeze + Unfreeze** | Either order | Serialized under row lock; end state deterministically matches the last committed transaction. |
| **Concurrent Close + Freeze** | `close` first (bal 0) | Account closed. Freeze wakes up, rejects `CLOSED` account with `422`. |
| | `freeze` first (bal 0) | Account frozen. Close wakes up, allows closing `FROZEN` account with zero balance, resulting in `CLOSED`. |

---

## 6. Financial Invariants & Reconciliation

1. **Zero Financial Mutation**:
   - `freeze`, `unfreeze`, and `close` **never** insert rows into `transactions` or `ledger_entries`.
   - `freeze`, `unfreeze`, and `close` **never** modify the `balance` column.

2. **Snapshot vs Ledger Consistency**:
   - At all points before, during, and after any lifecycle transition, the account snapshot balance remains strictly equal to the net ledger entries:
     $$\text{Account Balance} = \sum \text{Credits} - \sum \text{Debits}$$

3. **Continuous Auditing & Statements**:
   - `FROZEN` and `CLOSED` accounts remain completely auditable:
     - `GET /api/v1/accounts/{accountId}/statements` returns full historical statements and entry details.
     - `GET /api/v1/accounts/{accountId}/transactions` returns paginated transaction history.
     - `ReconciliationService.reconcileAccount(accountId)` verifies cryptographic balance integrity (`CONSISTENT`, `difference = 0.0000`).

---

## 7. Database Integrity & Defensive Constraints (V5 Migration)

Flyway migration `V5__account_lifecycle_integrity.sql` applies multi-layered database-level protections:

### 7.1 Check Constraint
```sql
ALTER TABLE accounts
    ADD CONSTRAINT chk_accounts_closed_zero_balance
    CHECK (status != 'CLOSED' OR balance = 0);
```
Guarantees that no `accounts` record can have status `CLOSED` while maintaining a balance different from `0.0000`.

### 7.2 Defensive Integrity Trigger
```sql
CREATE OR REPLACE FUNCTION enforce_account_lifecycle_integrity()
RETURNS TRIGGER AS $$
BEGIN
    -- 1. CLOSED is terminal: cannot transition away from CLOSED
    IF OLD.status = 'CLOSED' AND NEW.status != 'CLOSED' THEN
        RAISE EXCEPTION 'Closed account % is terminal and cannot be reopened', OLD.id;
    END IF;

    -- 2. Balance immutable once CLOSED
    IF OLD.status = 'CLOSED' AND NEW.balance != OLD.balance THEN
        RAISE EXCEPTION 'Cannot modify balance of closed account %', OLD.id;
    END IF;

    -- 3. SYSTEM_CLEARING cannot transition away from ACTIVE
    IF OLD.account_type = 'SYSTEM_CLEARING' AND NEW.status != 'ACTIVE' THEN
        RAISE EXCEPTION 'System clearing account % cannot transition away from ACTIVE', OLD.id;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_accounts_lifecycle_integrity
BEFORE UPDATE ON accounts
FOR EACH ROW
EXECUTE FUNCTION enforce_account_lifecycle_integrity();
```
Even in the event of direct SQL queries or bypasses of application-level services, the PostgreSQL database kernel enforces lifecycle invariants.
