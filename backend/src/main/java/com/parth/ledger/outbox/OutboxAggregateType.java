package com.parth.ledger.outbox;

/**
 * Controlled aggregate classifications for outbox events.
 */
public enum OutboxAggregateType {
    TRANSACTION,
    ACCOUNT
}
