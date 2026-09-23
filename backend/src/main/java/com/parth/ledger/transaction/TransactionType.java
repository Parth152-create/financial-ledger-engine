package com.parth.ledger.transaction;

/**
 * Supported financial transaction types.
 *
 * Persisted as STRING in the authoritative PostgreSQL database.
 */
public enum TransactionType {
    TRANSFER,
    DEPOSIT,
    WITHDRAWAL
}
