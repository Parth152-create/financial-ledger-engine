-- =============================================================================
-- V12__create_financial_policies_and_usage_schema.sql
-- Financial Ledger Engine - Transaction & Account Limits / Policy Engine
--
-- 1. Extend audit_events event_type constraint to support policy events:
--    - POLICY_CREATED, POLICY_UPDATED, POLICY_DELETED
--    - TRANSFER_REJECTED_POLICY, DEPOSIT_REJECTED_POLICY, WITHDRAWAL_REJECTED_POLICY
-- 2. Extend audit_events entity_type constraint to support 'POLICY'
-- 3. Create financial_policies table:
--    - Scoped to GLOBAL or ACCOUNT
--    - Constrained to INR currency
--    - NUMERIC(19,4) amount limits and positive integer count limits
--    - UNIQUE NULLS NOT DISTINCT constraint preventing duplicate definitions
-- 4. Create policy_usage_daily table:
--    - Row-level usage counters for daily amounts and transaction counts
--    - Unique constraint on (account_id, transaction_type, usage_date)
--    - PostgreSQL-authoritative transactional concurrency control
-- =============================================================================

-- 1. Extend audit_events event_type check constraint
ALTER TABLE audit_events DROP CONSTRAINT IF EXISTS chk_audit_events_event_type;
ALTER TABLE audit_events ADD CONSTRAINT chk_audit_events_event_type
    CHECK (
        event_type IN (
            'AUTH_SIGNUP',
            'AUTH_LOGIN',
            'AUTH_LOGOUT',
            'PASSWORD_CHANGED',
            'ACCOUNT_CREATED',
            'ACCOUNT_FROZEN',
            'ACCOUNT_UNFROZEN',
            'ACCOUNT_CLOSED',
            'TRANSFER_COMPLETED',
            'DEPOSIT_COMPLETED',
            'WITHDRAWAL_COMPLETED',
            'TRANSACTION_REVERSED',
            'POLICY_CREATED',
            'POLICY_UPDATED',
            'POLICY_DELETED',
            'TRANSFER_REJECTED_POLICY',
            'DEPOSIT_REJECTED_POLICY',
            'WITHDRAWAL_REJECTED_POLICY'
        )
    );

-- 2. Extend audit_events entity_type check constraint
ALTER TABLE audit_events DROP CONSTRAINT IF EXISTS chk_audit_events_entity_type;
ALTER TABLE audit_events ADD CONSTRAINT chk_audit_events_entity_type
    CHECK (
        entity_type IN ('USER', 'ACCOUNT', 'TRANSACTION', 'SYSTEM', 'POLICY')
    );

-- 3. Create financial_policies table
CREATE TABLE financial_policies (
    id UUID PRIMARY KEY,
    account_id UUID,
    policy_scope VARCHAR(16) NOT NULL,
    transaction_type VARCHAR(32),
    policy_type VARCHAR(64) NOT NULL,
    amount_limit NUMERIC(19, 4),
    count_limit INT,
    currency VARCHAR(3) NOT NULL DEFAULT 'INR',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_financial_policies_account FOREIGN KEY (account_id)
        REFERENCES accounts(id) ON DELETE CASCADE,

    CONSTRAINT chk_financial_policies_currency CHECK (currency = 'INR'),

    CONSTRAINT chk_financial_policies_scope CHECK (
        (policy_scope = 'GLOBAL' AND account_id IS NULL)
        OR
        (policy_scope = 'ACCOUNT' AND account_id IS NOT NULL)
    ),

    CONSTRAINT chk_financial_policies_policy_type CHECK (
        policy_type IN (
            'MAX_TRANSACTION_AMOUNT',
            'DAILY_TRANSACTION_AMOUNT',
            'DAILY_TRANSACTION_COUNT',
            'ACCOUNT_BALANCE_LIMIT'
        )
    ),

    CONSTRAINT chk_financial_policies_transaction_type CHECK (
        (policy_type = 'ACCOUNT_BALANCE_LIMIT' AND transaction_type IS NULL)
        OR
        (policy_type != 'ACCOUNT_BALANCE_LIMIT' AND transaction_type IN ('TRANSFER', 'DEPOSIT', 'WITHDRAWAL'))
    ),

    CONSTRAINT chk_financial_policies_limits CHECK (
        (policy_type IN ('MAX_TRANSACTION_AMOUNT', 'DAILY_TRANSACTION_AMOUNT', 'ACCOUNT_BALANCE_LIMIT')
            AND amount_limit IS NOT NULL AND amount_limit > 0 AND count_limit IS NULL)
        OR
        (policy_type = 'DAILY_TRANSACTION_COUNT'
            AND count_limit IS NOT NULL AND count_limit > 0 AND amount_limit IS NULL)
    ),

    CONSTRAINT uk_financial_policies UNIQUE NULLS NOT DISTINCT (
        account_id,
        policy_type,
        transaction_type
    )
);

CREATE INDEX idx_financial_policies_account_id ON financial_policies(account_id);
CREATE INDEX idx_financial_policies_scope_type_tx ON financial_policies(policy_scope, policy_type, transaction_type);
CREATE INDEX idx_financial_policies_enabled ON financial_policies(enabled);

-- 4. Create policy_usage_daily table
CREATE TABLE policy_usage_daily (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL,
    transaction_type VARCHAR(32) NOT NULL,
    usage_date DATE NOT NULL,
    amount_used NUMERIC(19, 4) NOT NULL DEFAULT 0.0000,
    transaction_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_policy_usage_daily_account FOREIGN KEY (account_id)
        REFERENCES accounts(id) ON DELETE CASCADE,

    CONSTRAINT chk_policy_usage_daily_transaction_type CHECK (
        transaction_type IN ('TRANSFER', 'DEPOSIT', 'WITHDRAWAL')
    ),

    CONSTRAINT chk_policy_usage_daily_amount_non_negative CHECK (amount_used >= 0),
    CONSTRAINT chk_policy_usage_daily_count_non_negative CHECK (transaction_count >= 0),

    CONSTRAINT uk_policy_usage_daily UNIQUE (account_id, transaction_type, usage_date)
);

CREATE INDEX idx_policy_usage_daily_lookup ON policy_usage_daily(account_id, transaction_type, usage_date);
