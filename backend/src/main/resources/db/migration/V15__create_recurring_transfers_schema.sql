-- =============================================================================
-- V15__create_recurring_transfers_schema.sql
-- Financial Ledger Engine - Recurring Transfers & Execution History
-- 1. Create recurring_transfers table with strict constraints and indexes
-- 2. Create recurring_transfer_executions table with strict uniqueness and indexes
-- 3. Extend audit_events event_type and entity_type check constraints
-- =============================================================================

-- 1. Create recurring_transfers table
CREATE TABLE recurring_transfers (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    source_account_id UUID NOT NULL,
    destination_account_id UUID NOT NULL,
    amount NUMERIC(19, 4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    frequency VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    next_execution_at TIMESTAMP WITH TIME ZONE NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE,
    execution_count INT NOT NULL DEFAULT 0,
    last_executed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_recurring_transfers_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,

    CONSTRAINT fk_recurring_transfers_source_account
        FOREIGN KEY (source_account_id) REFERENCES accounts(id),

    CONSTRAINT fk_recurring_transfers_dest_account
        FOREIGN KEY (destination_account_id) REFERENCES accounts(id),

    CONSTRAINT chk_recurring_transfers_amount CHECK (
        amount > 0
    ),

    CONSTRAINT chk_recurring_transfers_currency CHECK (
        currency = 'INR'
    ),

    CONSTRAINT chk_recurring_transfers_different_accounts CHECK (
        source_account_id != destination_account_id
    ),

    CONSTRAINT chk_recurring_transfers_frequency CHECK (
        frequency IN ('DAILY', 'WEEKLY', 'MONTHLY')
    ),

    CONSTRAINT chk_recurring_transfers_status CHECK (
        status IN ('ACTIVE', 'PAUSED', 'COMPLETED', 'CANCELLED')
    ),

    CONSTRAINT chk_recurring_transfers_execution_count CHECK (
        execution_count >= 0
    ),

    CONSTRAINT chk_recurring_transfers_dates CHECK (
        end_date IS NULL OR end_date >= start_date
    )
);

-- Performance and scheduling indexes for recurring_transfers
CREATE INDEX idx_recurring_transfers_user_id
    ON recurring_transfers(user_id);

CREATE INDEX idx_recurring_transfers_status_next_execution
    ON recurring_transfers(status, next_execution_at);

CREATE INDEX idx_recurring_transfers_source_account
    ON recurring_transfers(source_account_id);

CREATE INDEX idx_recurring_transfers_dest_account
    ON recurring_transfers(destination_account_id);

CREATE INDEX idx_recurring_transfers_created_at
    ON recurring_transfers(created_at DESC);

-- 2. Create recurring_transfer_executions table
CREATE TABLE recurring_transfer_executions (
    id UUID PRIMARY KEY,
    recurring_transfer_id UUID NOT NULL,
    execution_key VARCHAR(64) NOT NULL,
    scheduled_for TIMESTAMP WITH TIME ZONE NOT NULL,
    transaction_id UUID,
    status VARCHAR(32) NOT NULL,
    failure_reason TEXT,
    executed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_recurring_transfer_executions_schedule
        FOREIGN KEY (recurring_transfer_id) REFERENCES recurring_transfers(id) ON DELETE CASCADE,

    CONSTRAINT fk_recurring_transfer_executions_transaction
        FOREIGN KEY (transaction_id) REFERENCES transactions(id),

    CONSTRAINT chk_recurring_transfer_executions_status CHECK (
        status IN ('SUCCESS', 'FAILED')
    ),

    CONSTRAINT chk_recurring_transfer_executions_success CHECK (
        (status = 'SUCCESS' AND transaction_id IS NOT NULL)
        OR
        (status = 'FAILED')
    ),

    CONSTRAINT uk_recurring_transfer_executions_slot UNIQUE (
        recurring_transfer_id, execution_key
    )
);

-- Ensure transaction_id is unique across executions where present
CREATE UNIQUE INDEX uk_recurring_transfer_executions_tx
    ON recurring_transfer_executions(transaction_id)
    WHERE transaction_id IS NOT NULL;

CREATE INDEX idx_recurring_transfer_executions_schedule_scheduled
    ON recurring_transfer_executions(recurring_transfer_id, scheduled_for DESC);

CREATE INDEX idx_recurring_transfer_executions_created_at
    ON recurring_transfer_executions(created_at DESC);

-- 3. Extend audit_events check constraints for recurring transfers
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
            'WITHDRAWAL_REJECTED_POLICY',
            'RECURRING_TRANSFER_CREATED',
            'RECURRING_TRANSFER_PAUSED',
            'RECURRING_TRANSFER_RESUMED',
            'RECURRING_TRANSFER_CANCELLED'
        )
    );

ALTER TABLE audit_events DROP CONSTRAINT IF EXISTS chk_audit_events_entity_type;
ALTER TABLE audit_events ADD CONSTRAINT chk_audit_events_entity_type
    CHECK (
        entity_type IN ('USER', 'ACCOUNT', 'TRANSACTION', 'SYSTEM', 'POLICY', 'RECURRING_TRANSFER')
    );
