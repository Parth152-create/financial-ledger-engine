package com.parth.ledger.outbox;

/**
 * Lifecycle status of an outbox event.
 */
public enum OutboxStatus {
    PENDING,
    PROCESSING,
    PROCESSED,
    FAILED
}
