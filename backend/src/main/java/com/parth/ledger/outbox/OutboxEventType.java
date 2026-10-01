package com.parth.ledger.outbox;

/**
 * Controlled event types for the Transactional Outbox pattern.
 * Represents domain/integration events persisted atomically with financial or account state changes.
 */
public enum OutboxEventType {
    TRANSFER_COMPLETED,
    DEPOSIT_COMPLETED,
    WITHDRAWAL_COMPLETED,
    TRANSACTION_REVERSED,
    ACCOUNT_CREATED,
    ACCOUNT_FROZEN,
    ACCOUNT_UNFROZEN,
    ACCOUNT_CLOSED
}
