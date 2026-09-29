-- =============================================================================
-- V10__create_audit_events_schema.sql
-- Financial Ledger Engine - Operational Audit Events Trail
-- 1. Create audit_events table with strict constraints
-- 2. Performance indexes tailored to access patterns
-- 3. Immutability trigger preventing UPDATE and DELETE
-- 4. Partial unique index enforcing single completion event per transaction
-- 5. Partial unique index enforcing single creation event per account
-- =============================================================================

CREATE TABLE audit_events (
    id UUID PRIMARY KEY,
    actor_user_id UUID,
    event_type VARCHAR(64) NOT NULL,
    entity_type VARCHAR(32) NOT NULL,
    entity_id UUID,
    metadata JSONB,
    ip_address VARCHAR(45),
    user_agent VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_audit_events_event_type CHECK (
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
            'WITHDRAWAL_COMPLETED'
        )
    ),

    CONSTRAINT chk_audit_events_entity_type CHECK (
        entity_type IN ('USER', 'ACCOUNT', 'TRANSACTION', 'SYSTEM')
    )
);

-- Indexes optimized for query patterns
-- 1. Actor timeline queries: actor_user_id + created_at DESC
CREATE INDEX idx_audit_events_actor_created_at
    ON audit_events(actor_user_id, created_at DESC)
    WHERE actor_user_id IS NOT NULL;

-- 2. Entity timeline queries: entity_type + entity_id + created_at DESC
CREATE INDEX idx_audit_events_entity_created_at
    ON audit_events(entity_type, entity_id, created_at DESC)
    WHERE entity_id IS NOT NULL;

-- 3. Event type filtering: event_type + created_at DESC
CREATE INDEX idx_audit_events_event_type_created_at
    ON audit_events(event_type, created_at DESC);

-- 4. General chronological pagination (e.g. admin or global timeline)
CREATE INDEX idx_audit_events_created_at
    ON audit_events(created_at DESC);

-- 5. Business invariant: Exactly one completion audit event per financial transaction
CREATE UNIQUE INDEX uk_audit_events_transaction_completion
    ON audit_events(entity_id, event_type)
    WHERE entity_type = 'TRANSACTION'
      AND event_type IN ('TRANSFER_COMPLETED', 'DEPOSIT_COMPLETED', 'WITHDRAWAL_COMPLETED');

-- 6. Business invariant: Exactly one creation audit event per account
CREATE UNIQUE INDEX uk_audit_events_account_creation
    ON audit_events(entity_id, event_type)
    WHERE entity_type = 'ACCOUNT' AND event_type = 'ACCOUNT_CREATED';

-- Database-level Immutability Trigger
CREATE OR REPLACE FUNCTION prevent_audit_event_modification()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Audit events are immutable: % operations are not allowed on audit_events', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_events_immutable
BEFORE UPDATE OR DELETE ON audit_events
FOR EACH ROW
EXECUTE FUNCTION prevent_audit_event_modification();
