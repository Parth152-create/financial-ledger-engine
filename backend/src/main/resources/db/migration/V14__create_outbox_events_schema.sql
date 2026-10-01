-- =============================================================================
-- V14__create_outbox_events_schema.sql
-- Financial Ledger Engine - Transactional Outbox Pattern
-- 1. Create outbox_events table with strict constraints
-- 2. Performance indexes tailored to processor query and administrative queries
-- 3. Unique indexes enforcing logical deduplication per aggregate
-- =============================================================================

CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(32) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    available_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    processed_at TIMESTAMP WITH TIME ZONE,
    last_error TEXT,

    CONSTRAINT chk_outbox_events_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'PROCESSED', 'FAILED')
    ),

    CONSTRAINT chk_outbox_events_attempt_count CHECK (
        attempt_count >= 0
    ),

    CONSTRAINT chk_outbox_events_aggregate_type CHECK (
        aggregate_type IN ('TRANSACTION', 'ACCOUNT')
    ),

    CONSTRAINT chk_outbox_events_event_type CHECK (
        event_type IN (
            'TRANSFER_COMPLETED',
            'DEPOSIT_COMPLETED',
            'WITHDRAWAL_COMPLETED',
            'TRANSACTION_REVERSED',
            'ACCOUNT_CREATED',
            'ACCOUNT_FROZEN',
            'ACCOUNT_UNFROZEN',
            'ACCOUNT_CLOSED'
        )
    ),

    CONSTRAINT chk_outbox_events_processed_at CHECK (
        (status = 'PROCESSED' AND processed_at IS NOT NULL)
        OR
        (status != 'PROCESSED')
    )
);

-- Indexes optimized for query and processor patterns
-- 1. Processor polling index: status + available_at + created_at
CREATE INDEX idx_outbox_events_status_available
    ON outbox_events(status, available_at, created_at);

-- 2. General chronological pagination (e.g. admin or global timeline)
CREATE INDEX idx_outbox_events_created_at
    ON outbox_events(created_at DESC);

-- 3. Aggregate lookup: aggregate_type + aggregate_id
CREATE INDEX idx_outbox_events_aggregate
    ON outbox_events(aggregate_type, aggregate_id);

-- 4. Event type filtering
CREATE INDEX idx_outbox_events_event_type
    ON outbox_events(event_type);

-- Unique constraints preventing duplicate logical events
-- 5. Business invariant: At most one completion outbox event per financial transaction
CREATE UNIQUE INDEX uk_outbox_events_transaction_event
    ON outbox_events(aggregate_id, event_type)
    WHERE aggregate_type = 'TRANSACTION';

-- 6. Business invariant: At most one creation outbox event per account
CREATE UNIQUE INDEX uk_outbox_events_account_created
    ON outbox_events(aggregate_id, event_type)
    WHERE aggregate_type = 'ACCOUNT' AND event_type = 'ACCOUNT_CREATED';

-- 7. Business invariant: At most one closure outbox event per account
CREATE UNIQUE INDEX uk_outbox_events_account_closed
    ON outbox_events(aggregate_id, event_type)
    WHERE aggregate_type = 'ACCOUNT' AND event_type = 'ACCOUNT_CLOSED';
