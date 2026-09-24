-- =============================================================================
-- V5__account_lifecycle_integrity.sql
-- Financial Ledger Engine - Account Lifecycle Integrity
-- 1. Zero-balance constraint on CLOSED accounts: CHECK (status != 'CLOSED' OR balance = 0)
-- 2. Lifecycle integrity trigger:
--    a. CLOSED accounts cannot be reopened (CLOSED -> ACTIVE, CLOSED -> FROZEN)
--    b. CLOSED accounts cannot have their balance changed
--    c. SYSTEM_CLEARING cannot transition away from ACTIVE
-- =============================================================================

-- 1. Zero-balance constraint on closure
ALTER TABLE accounts
    ADD CONSTRAINT chk_accounts_closed_zero_balance
    CHECK (status != 'CLOSED' OR balance = 0);

-- 2. Lifecycle integrity trigger
CREATE OR REPLACE FUNCTION enforce_account_lifecycle_integrity()
RETURNS TRIGGER AS $$
BEGIN
    -- 1. CLOSED accounts cannot be reopened
    IF OLD.status = 'CLOSED' AND NEW.status != 'CLOSED' THEN
        RAISE EXCEPTION 'Closed account % is terminal and cannot be reopened', OLD.id;
    END IF;

    -- 2. CLOSED accounts cannot have their balance changed
    IF OLD.status = 'CLOSED' AND NEW.balance != OLD.balance THEN
        RAISE EXCEPTION 'Cannot modify balance of closed account %', OLD.id;
    END IF;

    -- 3. SYSTEM_CLEARING cannot transition away from ACTIVE
    IF (OLD.account_type = 'SYSTEM_CLEARING' OR NEW.account_type = 'SYSTEM_CLEARING') AND NEW.status != 'ACTIVE' THEN
        RAISE EXCEPTION 'System clearing account % cannot transition away from ACTIVE', OLD.id;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_accounts_lifecycle_integrity
BEFORE UPDATE ON accounts
FOR EACH ROW
EXECUTE FUNCTION enforce_account_lifecycle_integrity();
