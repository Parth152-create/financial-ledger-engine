-- =============================================================================
-- V8__system_clearing_bootstrap_funding.sql
-- Financial Ledger Engine - System Clearing Accounting Model Hardening
--
-- 1. Extend account_type check constraint to support SYSTEM_TREASURY
-- 2. Extend user_requirement check constraint to allow NULL user_id for SYSTEM_TREASURY
-- 3. Scope balance non-negative constraint: USER_CHECKING and SYSTEM_CLEARING must be >= 0;
--    SYSTEM_TREASURY holds the counterbalancing equity/capital debit.
-- 4. Extend transaction_type check constraint to support SYSTEM_FUNDING
-- 5. Update lifecycle integrity trigger: SYSTEM_TREASURY cannot transition away from ACTIVE
-- 6. Formally seed SYSTEM_TREASURY, fund SYSTEM_CLEARING, and record double-entry ledger entries
-- =============================================================================

-- 1. Support SYSTEM_TREASURY account type
ALTER TABLE accounts DROP CONSTRAINT chk_accounts_account_type;
ALTER TABLE accounts ADD CONSTRAINT chk_accounts_account_type
    CHECK (account_type IN ('USER_CHECKING', 'SYSTEM_CLEARING', 'SYSTEM_TREASURY'));

-- 2. Allow SYSTEM_TREASURY to have NULL user_id
ALTER TABLE accounts DROP CONSTRAINT chk_accounts_user_requirement;
ALTER TABLE accounts ADD CONSTRAINT chk_accounts_user_requirement
    CHECK ((account_type = 'USER_CHECKING' AND user_id IS NOT NULL) OR (account_type IN ('SYSTEM_CLEARING', 'SYSTEM_TREASURY')));

-- 3. Scope balance non-negative constraint
-- USER_CHECKING and SYSTEM_CLEARING remain strictly non-negative.
-- Only SYSTEM_TREASURY carries the counterbalancing platform equity debit.
ALTER TABLE accounts DROP CONSTRAINT chk_accounts_balance_non_negative;
ALTER TABLE accounts ADD CONSTRAINT chk_accounts_balance_non_negative
    CHECK (account_type = 'SYSTEM_TREASURY' OR balance >= 0);

-- 4. Support SYSTEM_FUNDING transaction type
ALTER TABLE transactions DROP CONSTRAINT chk_transactions_transaction_type;
ALTER TABLE transactions ADD CONSTRAINT chk_transactions_transaction_type
    CHECK (transaction_type IN ('TRANSFER', 'DEPOSIT', 'WITHDRAWAL', 'SYSTEM_FUNDING'));

-- 5. Update lifecycle trigger: SYSTEM accounts cannot transition away from ACTIVE
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

    -- 3. SYSTEM accounts (CLEARING and TREASURY) cannot transition away from ACTIVE
    IF (OLD.account_type IN ('SYSTEM_CLEARING', 'SYSTEM_TREASURY') OR NEW.account_type IN ('SYSTEM_CLEARING', 'SYSTEM_TREASURY')) AND NEW.status != 'ACTIVE' THEN
        RAISE EXCEPTION 'System account % cannot transition away from ACTIVE', OLD.id;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- 6. Seed SYSTEM_TREASURY and bootstrap double-entry platform funding
DO $$
DECLARE
    treasury_id UUID := '00000000-0000-0000-0000-000000000002';
    clearing_id UUID := '00000000-0000-0000-0000-000000000001';
    tx_id UUID := '00000000-0000-0000-0000-000000000010';
    debit_entry_id UUID := '00000000-0000-0000-0000-000000000011';
    credit_entry_id UUID := '00000000-0000-0000-0000-000000000012';
    bootstrap_amount NUMERIC(19, 4) := 10000000.0000;
    idempotency_k VARCHAR(255) := 'SYSTEM-BOOTSTRAP-FUNDING-USD-01';
BEGIN
    -- Seed SYSTEM_TREASURY if not exists
    INSERT INTO accounts (id, user_id, currency, balance, version, created_at, updated_at, account_type, status, account_number)
    VALUES (
        treasury_id,
        NULL,
        'USD',
        0.0000,
        0,
        NOW(),
        NOW(),
        'SYSTEM_TREASURY',
        'ACTIVE',
        'ACCT-SYSTEM-TREASURY-01'
    )
    ON CONFLICT (id) DO NOTHING;

    -- Ensure SYSTEM_CLEARING exists (seeded in V2, but guaranteed here)
    INSERT INTO accounts (id, user_id, currency, balance, version, created_at, updated_at, account_type, status, account_number)
    VALUES (
        clearing_id,
        NULL,
        'USD',
        0.0000,
        0,
        NOW(),
        NOW(),
        'SYSTEM_CLEARING',
        'ACTIVE',
        'ACCT-SYSTEM-CLEARING-01'
    )
    ON CONFLICT (id) DO NOTHING;

    -- Execute double-entry bootstrap funding if transaction not already present
    IF NOT EXISTS (SELECT 1 FROM transactions WHERE idempotency_key = idempotency_k) THEN
        -- Mutate account balances atomically:
        -- SYSTEM_TREASURY debited (decreased by bootstrap_amount)
        UPDATE accounts
        SET balance = balance - bootstrap_amount, updated_at = NOW()
        WHERE id = treasury_id;

        -- SYSTEM_CLEARING credited (increased by bootstrap_amount)
        UPDATE accounts
        SET balance = balance + bootstrap_amount, updated_at = NOW()
        WHERE id = clearing_id;

        -- Insert COMPLETED transaction record
        INSERT INTO transactions (
            id, idempotency_key, amount, currency, status,
            source_account_id, destination_account_id,
            created_at, completed_at, transaction_type,
            initiated_by_user_id, description
        )
        VALUES (
            tx_id,
            idempotency_k,
            bootstrap_amount,
            'USD',
            'COMPLETED',
            treasury_id,
            clearing_id,
            NOW(),
            NOW(),
            'SYSTEM_FUNDING',
            NULL,
            'Initial platform treasury funding to system clearing'
        );

        -- Insert double-entry ledger entries:
        -- DEBIT on SYSTEM_TREASURY
        INSERT INTO ledger_entries (id, transaction_id, account_id, entry_type, amount, currency, created_at)
        VALUES (
            debit_entry_id,
            tx_id,
            treasury_id,
            'DEBIT',
            bootstrap_amount,
            'USD',
            NOW()
        );

        -- CREDIT on SYSTEM_CLEARING
        INSERT INTO ledger_entries (id, transaction_id, account_id, entry_type, amount, currency, created_at)
        VALUES (
            credit_entry_id,
            tx_id,
            clearing_id,
            'CREDIT',
            bootstrap_amount,
            'USD',
            NOW()
        );
    END IF;
END $$;
