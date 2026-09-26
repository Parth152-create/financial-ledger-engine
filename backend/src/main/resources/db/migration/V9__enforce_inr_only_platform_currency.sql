-- =============================================================================
-- V9__enforce_inr_only_platform_currency.sql
-- Financial Ledger Engine - Enforce INR-Only Platform Currency
--
-- 1. Migrate existing non-INR accounts, transactions, and ledger entries to INR
-- 2. Update bootstrap funding transaction key to SYSTEM-BOOTSTRAP-FUNDING-INR-01
-- 3. Replace currency format regex constraints with strict INR-only constraints:
--    - accounts: CHECK (currency = 'INR')
--    - transactions: CHECK (currency = 'INR')
--    - ledger_entries: CHECK (currency = 'INR')
-- =============================================================================

-- 1. Migrate any existing accounts to INR
UPDATE accounts SET currency = 'INR' WHERE currency <> 'INR';

-- 2. Migrate any existing transactions to INR
UPDATE transactions SET currency = 'INR' WHERE currency <> 'INR';

-- 3. Migrate any existing ledger entries to INR (temporarily bypass immutability trigger for migration)
ALTER TABLE ledger_entries DISABLE TRIGGER trg_ledger_entries_immutable;
UPDATE ledger_entries SET currency = 'INR' WHERE currency <> 'INR';
ALTER TABLE ledger_entries ENABLE TRIGGER trg_ledger_entries_immutable;

-- 4. Update the bootstrap transaction idempotency key to INR
UPDATE transactions
SET idempotency_key = 'SYSTEM-BOOTSTRAP-FUNDING-INR-01',
    description = 'Initial platform treasury funding to system clearing'
WHERE id = '00000000-0000-0000-0000-000000000010';

-- 5. Enforce INR-only CHECK constraint on accounts
ALTER TABLE accounts DROP CONSTRAINT IF EXISTS chk_accounts_currency_format;
ALTER TABLE accounts DROP CONSTRAINT IF EXISTS chk_accounts_currency_inr;
ALTER TABLE accounts ADD CONSTRAINT chk_accounts_currency_inr
    CHECK (currency = 'INR');

-- 6. Enforce INR-only CHECK constraint on transactions
ALTER TABLE transactions DROP CONSTRAINT IF EXISTS chk_transactions_currency_format;
ALTER TABLE transactions DROP CONSTRAINT IF EXISTS chk_transactions_currency_inr;
ALTER TABLE transactions ADD CONSTRAINT chk_transactions_currency_inr
    CHECK (currency = 'INR');

-- 7. Enforce INR-only CHECK constraint on ledger_entries
ALTER TABLE ledger_entries DROP CONSTRAINT IF EXISTS chk_ledger_entries_currency_format;
ALTER TABLE ledger_entries DROP CONSTRAINT IF EXISTS chk_ledger_entries_currency_inr;
ALTER TABLE ledger_entries ADD CONSTRAINT chk_ledger_entries_currency_inr
    CHECK (currency = 'INR');
