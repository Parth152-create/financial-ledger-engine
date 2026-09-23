# Transaction & Ledger Model Hardening (V3)

This document describes the hardened database and domain model for financial transactions and ledger entries in the Financial Ledger Engine.

---

## 1. Architectural Overview

- **PostgreSQL = Financial Source of Truth**: All transactions and ledger entries are transactionally written and constrained in PostgreSQL.
- **Double-Entry Bookkeeping**: Every transaction continues to write balanced debits and credits (`totalDebits == totalCredits`).
- **Immutable Ledger**: Ledger entries are append-only historical records.
- **Explicit Auditability**: The actor initiating a transaction, the nature of the transaction (`TransactionType`), an optional human-readable reference (`description`), and autonomous entry currency are now first-class schema citizens.

---

## 2. Transaction Types (`TransactionType`)

The domain model introduces the `TransactionType` enum:

| Transaction Type | Description | Current Support |
|---|---|---|
| `TRANSFER` | Peer-to-peer balance transfer between two retail `USER_CHECKING` accounts. | **Fully Implemented** |
| `DEPOSIT` | Inflow from an external payment method or platform `SYSTEM_CLEARING` account to a `USER_CHECKING` account. | *Schema-level capability only; service implementation deferred to future milestones.* |
| `WITHDRAWAL` | Outflow from a `USER_CHECKING` account to an external destination via a platform `SYSTEM_CLEARING` account. | *Schema-level capability only; service implementation deferred to future milestones.* |

### Database Constraint:
```sql
ALTER TABLE transactions
    ADD CONSTRAINT chk_transactions_transaction_type
    CHECK (transaction_type IN ('TRANSFER', 'DEPOSIT', 'WITHDRAWAL'));
```

---

## 3. Initiated-By User (`initiated_by_user_id`)

### Purpose
Previously, the actor responsible for initiating a transfer had to be inferred from the source account's owner (`source_account.user_id`). The V3 hardening introduces an explicit foreign key:

`initiated_by_user_id UUID REFERENCES users(id)`

### Nullability & Platform Clearing Semantics
A key architectural consideration was determining the nullability of `initiated_by_user_id`:
1. **Retail Transfers**: For standard user-to-user transfers (`TRANSFER`), this column must **never be null**. The authenticated caller is recorded directly.
2. **Platform & System Operations**: Future operations involving `SYSTEM_CLEARING` accounts (such as automated platform sweeps or batch settlements) may not have an associated human application user.
3. **No Synthetic Users**: To avoid polluting the `users` table with fake accounts (e.g. `system@internal`), the column is conditionally constrained:
   ```sql
   ALTER TABLE transactions
       ADD CONSTRAINT chk_transactions_initiated_by_user
       CHECK ((transaction_type = 'TRANSFER' AND initiated_by_user_id IS NOT NULL) OR (transaction_type IN ('DEPOSIT', 'WITHDRAWAL')));
   ```
   This guarantees that all retail transfers require an authenticated user, while future system clearing operations remain flexible without fake users.

### Migration Backfill
Existing development and test transactions are backfilled deterministically from the source account's user:
```sql
UPDATE transactions t
SET initiated_by_user_id = a.user_id
FROM accounts a
WHERE t.source_account_id = a.id
  AND t.initiated_by_user_id IS NULL;
```

---

## 4. Transaction Description (`description`)

- **Field**: `description VARCHAR(255) NULL`
- **Purpose**: Optional human-readable memo, reference, or description for the transaction (e.g., "Consulting Invoice #2026-09").
- **Constraints**: Nullable, maximum length 255 characters. No full-text search indexing is added to keep transaction writes low-overhead.

---

## 5. Ledger Entry Currency (`ledger_entries.currency`)

### Historical Self-Description
Previously, the currency of a ledger entry had to be joined from the associated `transactions` row or `accounts` row. In V3, each immutable ledger entry explicitly records its own currency:

- **Field**: `currency VARCHAR(3) NOT NULL`
- **Constraint**: `CHECK (currency ~ '^[A-Z]{3}$')`

### Rationale:
1. **Autonomous Immutability**: Ledger entries represent permanent financial history. Their currency must remain self-describing even if accounts change or future multi-entity topologies evolve.
2. **Double-Entry Invariant**: Debit and credit entries for a transaction must share the same currency.

### Scope Clarification (No FX / Multi-Currency):
Multi-currency transfers and foreign exchange (FX) conversions remain strictly out of scope. The fundamental invariant remains:
$$\text{source account currency} == \text{destination account currency} == \text{transaction currency} == \text{ledger entry currency}$$

---

## 6. Flyway Migration Summary (`V3__enhance_transaction_auditability.sql`)

1. **Columns Added**:
   - `transactions.transaction_type VARCHAR(32) NOT NULL DEFAULT 'TRANSFER'`
   - `transactions.initiated_by_user_id UUID`
   - `transactions.description VARCHAR(255) NULL`
   - `ledger_entries.currency VARCHAR(3) NOT NULL`
2. **Backfill**:
   - Backfilled legacy `transactions.transaction_type` to `'TRANSFER'`.
   - Backfilled legacy `transactions.initiated_by_user_id` from `accounts.user_id`.
   - Backfilled legacy `ledger_entries.currency` from `transactions.currency`.
3. **Constraints**:
   - `chk_transactions_transaction_type`: Restricted to `('TRANSFER', 'DEPOSIT', 'WITHDRAWAL')`.
   - `fk_transactions_initiated_by_user`: Foreign key referencing `users(id)`.
   - `chk_transactions_initiated_by_user`: Mandates non-null user for `TRANSFER`.
   - `chk_ledger_entries_currency_format`: Enforces ISO 3-letter regex `^[A-Z]{3}$`.
4. **Indexes**:
   - `idx_transactions_transaction_type ON transactions(transaction_type)`
   - `idx_transactions_initiated_by_user_id ON transactions(initiated_by_user_id)`
