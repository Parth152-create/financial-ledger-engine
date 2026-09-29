-- =============================================================================
-- V11__create_transaction_reversals_schema.sql
-- Financial Ledger Engine - Transaction Reversals / Compensating Transactions
-- 1. Add reverses_transaction_id column and foreign key to transactions
-- 2. Partial unique index enforcing at most one reversal per original transaction
-- 3. Update chk_transactions_transaction_type to include 'REVERSAL'
-- 4. Constraint enforcing bidirectional relationship between REVERSAL type and reverses_transaction_id
-- 5. Constraint preventing self-reversal
-- 6. Update chk_audit_events_event_type to include 'TRANSACTION_REVERSED'
-- 7. Partial unique index enforcing single TRANSACTION_REVERSED event per original transaction
-- =============================================================================

-- 1. Add reverses_transaction_id referencing original transaction
ALTER TABLE transactions
    ADD COLUMN reverses_transaction_id UUID;

ALTER TABLE transactions
    ADD CONSTRAINT fk_transactions_reverses_transaction
    FOREIGN KEY (reverses_transaction_id) REFERENCES transactions(id)
    ON DELETE CASCADE;

-- 2. Business invariant: At most one reversal transaction per original transaction
CREATE UNIQUE INDEX uk_transactions_reverses_transaction_id
    ON transactions(reverses_transaction_id)
    WHERE reverses_transaction_id IS NOT NULL;

-- 3. Extend transaction_type check constraint to support REVERSAL
ALTER TABLE transactions DROP CONSTRAINT chk_transactions_transaction_type;
ALTER TABLE transactions ADD CONSTRAINT chk_transactions_transaction_type
    CHECK (transaction_type IN ('TRANSFER', 'DEPOSIT', 'WITHDRAWAL', 'SYSTEM_FUNDING', 'REVERSAL'));

-- 4. Invariant: Only REVERSAL transactions have reverses_transaction_id, and every REVERSAL must have reverses_transaction_id
ALTER TABLE transactions
    ADD CONSTRAINT chk_transactions_reversal_reference
    CHECK (
        (transaction_type = 'REVERSAL' AND reverses_transaction_id IS NOT NULL)
        OR
        (transaction_type != 'REVERSAL' AND reverses_transaction_id IS NULL)
    );

-- 5. Invariant: A transaction cannot reverse itself
ALTER TABLE transactions
    ADD CONSTRAINT chk_transactions_no_self_reversal
    CHECK (reverses_transaction_id IS NULL OR reverses_transaction_id != id);

-- 6. Extend audit_events event_type constraint to support TRANSACTION_REVERSED
ALTER TABLE audit_events DROP CONSTRAINT chk_audit_events_event_type;
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
            'TRANSACTION_REVERSED'
        )
    );

-- 7. Business invariant: Exactly one TRANSACTION_REVERSED audit event per original transaction
CREATE UNIQUE INDEX uk_audit_events_transaction_reversed
    ON audit_events(entity_id, event_type)
    WHERE entity_type = 'TRANSACTION'
      AND event_type = 'TRANSACTION_REVERSED';
