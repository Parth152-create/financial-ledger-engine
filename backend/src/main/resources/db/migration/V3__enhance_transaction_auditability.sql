-- =============================================================================
-- V3__enhance_transaction_auditability.sql
-- Financial Ledger Engine - Transaction & Ledger Model Hardening
-- 1. Transaction type: TRANSFER, DEPOSIT, WITHDRAWAL
-- 2. Initiated-by user: UUID FK -> users(id)
-- 3. Transaction description: VARCHAR(255) NULL
-- 4. Ledger entry currency: VARCHAR(3) NOT NULL, format ^[A-Z]{3}$
-- =============================================================================

-- 1. Transaction Type
ALTER TABLE transactions
    ADD COLUMN transaction_type VARCHAR(32);

-- Backfill existing transactions as TRANSFER
UPDATE transactions
SET transaction_type = 'TRANSFER'
WHERE transaction_type IS NULL;

ALTER TABLE transactions
    ALTER COLUMN transaction_type SET NOT NULL,
    ALTER COLUMN transaction_type SET DEFAULT 'TRANSFER';

ALTER TABLE transactions
    ADD CONSTRAINT chk_transactions_transaction_type
    CHECK (transaction_type IN ('TRANSFER', 'DEPOSIT', 'WITHDRAWAL'));

CREATE INDEX idx_transactions_transaction_type ON transactions(transaction_type);

-- 2. Initiated-By User
ALTER TABLE transactions
    ADD COLUMN initiated_by_user_id UUID;

-- Backfill existing transactions using source_account.user_id
UPDATE transactions t
SET initiated_by_user_id = a.user_id
FROM accounts a
WHERE t.source_account_id = a.id
  AND t.initiated_by_user_id IS NULL;

ALTER TABLE transactions
    ADD CONSTRAINT fk_transactions_initiated_by_user
    FOREIGN KEY (initiated_by_user_id) REFERENCES users(id);

-- Enforce that USER_CHECKING transfers must have an authenticated initiating user,
-- while allowing future platform/system clearing operations to be nullable without fake users.
ALTER TABLE transactions
    ADD CONSTRAINT chk_transactions_initiated_by_user
    CHECK (transaction_type != 'TRANSFER' OR initiated_by_user_id IS NOT NULL);

CREATE INDEX idx_transactions_initiated_by_user_id ON transactions(initiated_by_user_id);

-- 3. Transaction Description (optional human-readable reference)
ALTER TABLE transactions
    ADD COLUMN description VARCHAR(255);

-- 4. Ledger Entry Currency
ALTER TABLE ledger_entries
    ADD COLUMN currency VARCHAR(3);

-- Backfill existing ledger entries from associated transaction currency
UPDATE ledger_entries le
SET currency = t.currency
FROM transactions t
WHERE le.transaction_id = t.id
  AND le.currency IS NULL;

ALTER TABLE ledger_entries
    ALTER COLUMN currency SET NOT NULL;

ALTER TABLE ledger_entries
    ADD CONSTRAINT chk_ledger_entries_currency_format
    CHECK (currency ~ '^[A-Z]{3}$');
