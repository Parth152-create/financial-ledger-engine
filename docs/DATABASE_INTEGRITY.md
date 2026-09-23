# Database Integrity, Indexing & Ledger Immutability (V4)

This document describes the database integrity guarantees, ledger immutability enforcement, and index strategy implemented in V4 of the Financial Ledger Engine.

---

## 1. Ledger Immutability & Append-Only Architecture

In double-entry bookkeeping, the general ledger is the immutable, authoritative record of all historical financial movements. Once written, a ledger entry must **never** be altered or deleted.

### PostgreSQL-Level Trigger Enforcement
While application-level safeguards (such as JPA entities without mutation setters and `@Column(updatable = false)`) provide initial defenses, the database itself must serve as the final, inviolable barrier against tampering.

V4 installs a PostgreSQL trigger function that intercepts all `UPDATE` and `DELETE` attempts against `ledger_entries`:

```sql
CREATE OR REPLACE FUNCTION prevent_ledger_entry_modification()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Ledger entries are immutable: % operations are not allowed on ledger_entries', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ledger_entries_immutable
BEFORE UPDATE OR DELETE ON ledger_entries
FOR EACH ROW
EXECUTE FUNCTION prevent_ledger_entry_modification();
```

### Why Database-Level Enforcement Is Mandatory
1. **Defense in Depth**: Application code changes, new microservices, background batch jobs, or migration scripts could inadvertently execute mutation statements.
2. **Protection Against Direct SQL / DBA Accidents**: Database administrators, analytics tools, or operational scripts cannot accidentally overwrite financial history.
3. **ORM Bypass Resilience**: Application-level checks only apply when mutation requests flow through specific entity methods. Direct HQL, JPQL bulk updates, or native queries bypass Java entity logic, but cannot bypass a database trigger.
4. **Regulatory & Audit Compliance**: Financial regulatory standards (SOX, PCI-DSS, GAAP) require cryptographic or database-enforced non-repudiation of financial ledger lines.

### Financial Corrections via Compensating Transactions
Because ledger records cannot be modified or deleted:
- Financial errors, duplicate charges, or disputes **must never** be corrected by modifying past records.
- All corrections must be achieved through **compensating transactions** (e.g., refund transfers or ledger reversals).
- A reversal transaction introduces new balanced debit and credit entries with an audit trail linking back to the original transaction, preserving a transparent, unforgeable audit trail.

---

## 2. Index Strategy & Audit

Every index in the database introduces a write-overhead penalty during high-throughput inserts. In V4, every table was audited to ensure that each index directly supports an active query pattern or a planned query pattern for transaction history and account statements, while eliminating redundant indexes.

### Audit Summary Table

| Table | Index Name | Columns | Type | Purpose | Status |
|---|---|---|---|---|---|
| `users` | `users_pkey` | `(id)` | Unique B-Tree | Primary key lookup | **Retained** (V1) |
| `users` | `uk_users_email` | `(email)` | Unique B-Tree | User lookup & unique constraint by email | **Retained** (V1) |
| `accounts` | `accounts_pkey` | `(id)` | Unique B-Tree | Primary key & pessimistic lock (`findByIdForUpdate`) | **Retained** (V1) |
| `accounts` | `uk_accounts_account_number` | `(account_number)` | Unique B-Tree | Public account identifier resolution & locking | **Retained** (V2) |
| `accounts` | `idx_accounts_user_id` | `(user_id)` | B-Tree | Foreign key checks & `findByUserId` | **Retained** (V1) |
| `accounts` | `idx_accounts_account_type` | `(account_type)` | B-Tree | System clearing vs retail user account filtering | **Retained** (V2) |
| `accounts` | `idx_accounts_status` | `(status)` | B-Tree | Filtering active, frozen, or closed accounts | **Retained** (V2) |
| `transactions` | `transactions_pkey` | `(id)` | Unique B-Tree | Primary key lookup | **Retained** (V1) |
| `transactions` | `uk_transactions_idempotency_key` | `(idempotency_key)` | Unique B-Tree | Enforce idempotency invariant & retry lookup | **Retained** (V1) |
| `transactions` | `idx_transactions_status` | `(status)` | B-Tree | Transaction lifecycle queries (e.g. pending recovery) | **Retained** (V1) |
| `transactions` | `idx_transactions_created_at` | `(created_at)` | B-Tree | System-wide chronological audits & reporting | **Retained** (V1) |
| `transactions` | `idx_transactions_transaction_type` | `(transaction_type)` | B-Tree | Filtering transfers vs deposits/withdrawals | **Retained** (V3) |
| `transactions` | `idx_transactions_initiated_by_user_id` | `(initiated_by_user_id)` | B-Tree | Foreign key validation & user audit trails | **Retained** (V3) |
| `transactions` | `idx_transactions_source_account_id` | `(source_account_id)` | B-Tree | Replaced by composite index | **Dropped (Redundant)** |
| `transactions` | `idx_transactions_destination_account_id` | `(destination_account_id)` | B-Tree | Replaced by composite index | **Dropped (Redundant)** |
| `transactions` | `idx_transactions_source_account_id_created_at` | `(source_account_id, created_at)` | Composite B-Tree | Foreign key checks + chronological outgoing transfer history | **Added (V4)** |
| `transactions` | `idx_transactions_destination_account_id_created_at` | `(destination_account_id, created_at)` | Composite B-Tree | Foreign key checks + chronological incoming transfer history | **Added (V4)** |
| `ledger_entries` | `ledger_entries_pkey` | `(id)` | Unique B-Tree | Primary key lookup | **Retained** (V1) |
| `ledger_entries` | `idx_ledger_entries_transaction_id` | `(transaction_id)` | B-Tree | Foreign key validation & `findByTransactionId` | **Retained** (V1) |
| `ledger_entries` | `idx_ledger_entries_created_at` | `(created_at)` | B-Tree | System-wide ledger timeline & global reconciliation | **Retained** (V1) |
| `ledger_entries` | `idx_ledger_entries_account_id` | `(account_id)` | B-Tree | Replaced by composite index | **Dropped (Redundant)** |
| `ledger_entries` | `idx_ledger_entries_account_id_created_at` | `(account_id, created_at)` | Composite B-Tree | Foreign key checks + account statement & balance aggregation | **Added (V4)** |

---

## 3. Query Patterns: Current vs. Upcoming

### Current Queries Supported
1. **Transfer Execution**:
   - `SELECT ... FROM accounts WHERE id = :id FOR UPDATE` (uses `accounts_pkey`).
   - `SELECT ... FROM transactions WHERE idempotency_key = :key` (uses `uk_transactions_idempotency_key`).
   - `SELECT ... FROM accounts WHERE id = :id AND user_id = :userId` (uses `accounts_pkey` + user check).
2. **Reconciliation**:
   - `SELECT ... FROM accounts WHERE user_id = :userId` (uses `idx_accounts_user_id`).
   - `SELECT SUM(amount) FROM ledger_entries WHERE account_id = :id AND entry_type = :type` (uses `idx_ledger_entries_account_id_created_at` leading column).
3. **Transaction Lookup**:
   - `SELECT ... FROM ledger_entries WHERE transaction_id = :txId` (uses `idx_ledger_entries_transaction_id`).

### Upcoming Queries Supported (Prepared in V4)
1. **Account Statements**:
   - Pattern: `SELECT * FROM ledger_entries WHERE account_id = :accountId ORDER BY created_at DESC LIMIT :pageSize OFFSET :offset`
   - Supported by: `idx_ledger_entries_account_id_created_at`.
   - Optimization: Avoids an in-memory/on-disk sort step by scanning the index directly in reverse chronological order.
2. **Transaction History by Account**:
   - Outgoing: `SELECT * FROM transactions WHERE source_account_id = :accId ORDER BY created_at DESC` supported by `idx_transactions_source_account_id_created_at`.
   - Incoming: `SELECT * FROM transactions WHERE destination_account_id = :accId ORDER BY created_at DESC` supported by `idx_transactions_destination_account_id_created_at`.
   - Optimization: Combines foreign key indexing with index-ordered scanning for paginated API responses.

---

## 4. Foreign Key Coverage

PostgreSQL does **not** automatically index foreign key columns. Unindexed foreign keys cause table locks during deletes or cascade checks on the referenced table.

All foreign key relationships in the system are fully indexed:
- `accounts.user_id` -> `idx_accounts_user_id`
- `transactions.source_account_id` -> `idx_transactions_source_account_id_created_at` (leading column)
- `transactions.destination_account_id` -> `idx_transactions_destination_account_id_created_at` (leading column)
- `transactions.initiated_by_user_id` -> `idx_transactions_initiated_by_user_id`
- `ledger_entries.transaction_id` -> `idx_ledger_entries_transaction_id`
- `ledger_entries.account_id` -> `idx_ledger_entries_account_id_created_at` (leading column)

---

## 5. Distinction: Current vs. Future Scope

| Feature / Capability | Status in V4 |
|---|---|
| PostgreSQL-enforced ledger immutability trigger | **Active** |
| Rejection of `UPDATE` and `DELETE` on `ledger_entries` | **Active** |
| Optimized composite indexes on `ledger_entries` and `transactions` | **Active** |
| Removal of redundant single-column indexes | **Active** |
| Double-entry peer-to-peer transfers | **Active** |
| Account reconciliation against immutable ledger entries | **Active** |
| Deposit API (`DEPOSIT`) | *Deferred (future milestone)* |
| Withdrawal API (`WITHDRAWAL`) | *Deferred (future milestone)* |
| Account statement endpoints | *Deferred (future milestone - indexes prepared)* |
| Transaction history query endpoints | *Deferred (future milestone - indexes prepared)* |
