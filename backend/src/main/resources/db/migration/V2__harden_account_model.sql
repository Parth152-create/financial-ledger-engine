-- =============================================================================
-- V2__harden_account_model.sql
-- Financial Ledger Engine - Account Model Hardening
-- 1. Account type: USER_CHECKING, SYSTEM_CLEARING
-- 2. Account lifecycle status: ACTIVE, FROZEN, CLOSED
-- 3. Public account number: VARCHAR(32) NOT NULL UNIQUE
-- 4. Database-level currency format validation: ^[A-Z]{3}$
-- 5. System clearing accounts: user_id is optional for SYSTEM_CLEARING accounts
-- =============================================================================

-- 1. Account Type
ALTER TABLE accounts
    ADD COLUMN account_type VARCHAR(32) NOT NULL DEFAULT 'USER_CHECKING';

ALTER TABLE accounts
    ADD CONSTRAINT chk_accounts_account_type
    CHECK (account_type IN ('USER_CHECKING', 'SYSTEM_CLEARING'));

-- 2. Account Status
ALTER TABLE accounts
    ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE';

ALTER TABLE accounts
    ADD CONSTRAINT chk_accounts_status
    CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED'));

-- 3. Public Account Number
ALTER TABLE accounts
    ADD COLUMN account_number VARCHAR(32);

-- Deterministically backfill existing accounts with unique public account numbers
UPDATE accounts
SET account_number = 'ACCT-' || UPPER(SUBSTR(REPLACE(id::text, '-', ''), 1, 16))
WHERE account_number IS NULL;

ALTER TABLE accounts
    ALTER COLUMN account_number SET NOT NULL;

ALTER TABLE accounts
    ADD CONSTRAINT uk_accounts_account_number UNIQUE (account_number);

-- 4. Currency Format Validation (ISO 4217 3-letter uppercase alphabetic code)
ALTER TABLE accounts
    ADD CONSTRAINT chk_accounts_currency_format
    CHECK (currency ~ '^[A-Z]{3}$');

-- 5. System Clearing Account Support
-- user_id is mandatory for USER_CHECKING accounts, but optional for SYSTEM_CLEARING accounts
ALTER TABLE accounts
    ALTER COLUMN user_id DROP NOT NULL;

ALTER TABLE accounts
    ADD CONSTRAINT chk_accounts_user_requirement
    CHECK ((account_type = 'USER_CHECKING' AND user_id IS NOT NULL) OR (account_type = 'SYSTEM_CLEARING'));

-- Indexes for efficient queries
CREATE INDEX idx_accounts_account_type ON accounts(account_type);
CREATE INDEX idx_accounts_status ON accounts(status);
