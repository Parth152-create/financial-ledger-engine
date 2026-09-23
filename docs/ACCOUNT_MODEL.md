# Account Model & Database Hardening (V2)

This document describes the hardened database and domain model for accounts in the Financial Ledger Engine.

---

## 1. Architectural Principles

- **PostgreSQL = Financial Source of Truth**: All account states, lifecycle transitions, and balances are authoritatively stored and validated in PostgreSQL.
- **Double-Entry Ledger = Authoritative Financial History**: Balances and transactions remain strictly bound to immutable double-entry ledger entries.
- **Redis = Idempotency Optimization Only**: Caching and fast-path responses never bypass core financial rules.

---

## 2. Account Types (`AccountType`)

Accounts are classified by their functional role within the platform:

| Account Type | Description | Ownership | Transfer API Access (`/api/v1/transfers`) |
|---|---|---|---|
| `USER_CHECKING` | Standard retail customer checking account. | Requires non-null `user_id` mapped to an authenticated application user. | **Allowed**: Standard peer-to-peer user transfer flow. |
| `SYSTEM_CLEARING` | Platform-owned clearing account used to balance inflows/outflows. | Platform-owned (`user_id` is `NULL`). No fake user records are created. | **Disallowed**: Rejected with `400 Bad Request` (`InvalidAccountTypeException`). |

System clearing accounts will later serve as the platform counterpart for formal deposit transactions. Deposit APIs are not part of this milestone and will be implemented in subsequent phases.

---

## 3. Account Lifecycle Status (`AccountStatus`)

Every account progresses through lifecycle states governed by strict transfer invariants:

```text
    +-----------+
    |  ACTIVE   | <--- (Default state upon creation)
    +-----+-----+
          |
    +-----v-----+
    |  FROZEN   | <--- Suspended (investigation, compliance, risk)
    +-----+-----+
          |
    +-----v-----+
    |  CLOSED   | <--- Terminated (account closure)
    +-----------+
```

### Lifecycle Rules:

| Status | Debit Allowed? | Credit Allowed? | Transfer API Invariant |
|---|---|---|---|
| `ACTIVE` | **Yes** | **Yes** | Normal operations proceed subject to balance and currency checks. |
| `FROZEN` | **No** | **No** | Rejected with `422 Unprocessable Content` (`AccountFrozenException`). Non-mutating fail-fast. |
| `CLOSED` | **No** | **No** | Rejected with `422 Unprocessable Content` (`AccountClosedException`). Non-mutating fail-fast. |

All transfers involving a non-`ACTIVE` account fail before any financial mutations occur.

---

## 4. Public Account Number vs Internal UUID

The ledger engine cleanly separates internal database identifiers from public identifiers:

| Identifier | Column | Constraints | Purpose |
|---|---|---|---|
| **Internal ID** | `id UUID` | `PRIMARY KEY` | Authoritative internal foreign key across `transactions` and `ledger_entries`. Not exposed as a public banking identifier. |
| **Public Account Number** | `account_number VARCHAR(32)` | `NOT NULL UNIQUE` | Public, user-facing account identifier (format: `ACCT-` followed by hexadecimal/alphanumeric characters). |

### Public Account Number Invariants:
1. **Never encodes currency**: Currency is an independent attribute on the account, preventing schema coupling.
2. **Deterministic Migration Backfill**: Existing development and test accounts are deterministically backfilled during migration using:
   ```sql
   UPDATE accounts
   SET account_number = 'ACCT-' || UPPER(SUBSTR(REPLACE(id::text, '-', ''), 1, 16))
   WHERE account_number IS NULL;
   ```
3. **Database Uniqueness**: Enforced by unique constraint `uk_accounts_account_number`.

---

## 5. Database-Level Currency Format Constraint

In addition to application-level bean validation, PostgreSQL enforces the standard ISO 3-letter currency invariant:

```sql
ALTER TABLE accounts
    ADD CONSTRAINT chk_accounts_currency_format
    CHECK (currency ~ '^[A-Z]{3}$');
```

This guarantees at the database level that no malformed, lowercase, or non-alphabetic currency values (e.g., `usd`, `US`, `123`, `U$D`) can be persisted.

---

## 6. System Clearing Account Representation

### Clean Schema Design
In V1, `accounts.user_id` had a `NOT NULL` constraint referencing `users(id)`. Rather than creating synthetic fake user rows (e.g. `system@google.com`), the schema was adjusted with minimal, principled changes:
1. Make `user_id` nullable:
   ```sql
   ALTER TABLE accounts ALTER COLUMN user_id DROP NOT NULL;
   ```
2. Enforce strict conditional user presence via CHECK constraint:
   ```sql
   ALTER TABLE accounts
       ADD CONSTRAINT chk_accounts_user_requirement
       CHECK ((account_type = 'USER_CHECKING' AND user_id IS NOT NULL) OR (account_type = 'SYSTEM_CLEARING'));
   ```
This guarantees that `USER_CHECKING` accounts must always belong to an authenticated user, while `SYSTEM_CLEARING` accounts are platform-managed without fake user entities.

### Initial Seed Setup
The Flyway V2 migration seeds an initial platform-owned system clearing account for supported currency `USD`:
- ID: `00000000-0000-0000-0000-000000000001`
- Type: `SYSTEM_CLEARING`
- Status: `ACTIVE`
- Currency: `USD`
- Balance: `0.0000`
- Account Number: `ACCT-SYSTEM-CLEARING-01`

---

## 7. Flyway Migration Summary (`V2__harden_account_model.sql`)

The migration `V2__harden_account_model.sql` applies the following changes:

1. **Columns Added**:
   - `account_type VARCHAR(32) NOT NULL DEFAULT 'USER_CHECKING'`
   - `status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE'`
   - `account_number VARCHAR(32)`
2. **Backfill**:
   - Backfills existing accounts with `'ACCT-' || UPPER(SUBSTR(REPLACE(id::text, '-', ''), 1, 16))`
   - Sets `account_number` to `NOT NULL`
3. **Constraints Added**:
   - `chk_accounts_account_type`: `account_type IN ('USER_CHECKING', 'SYSTEM_CLEARING')`
   - `chk_accounts_status`: `status IN ('ACTIVE', 'FROZEN', 'CLOSED')`
   - `uk_accounts_account_number`: `UNIQUE (account_number)`
   - `chk_accounts_currency_format`: `CHECK (currency ~ '^[A-Z]{3}$')`
   - `chk_accounts_user_requirement`: `CHECK ((account_type = 'USER_CHECKING' AND user_id IS NOT NULL) OR (account_type = 'SYSTEM_CLEARING'))`
4. **Indexes Created**:
   - `idx_accounts_account_type ON accounts(account_type)`
   - `idx_accounts_status ON accounts(status)`
5. **System Account Seed**:
   - Seeds initial `SYSTEM_CLEARING` account for `USD`.
