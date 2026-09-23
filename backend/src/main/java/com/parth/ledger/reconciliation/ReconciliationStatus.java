package com.parth.ledger.reconciliation;

/**
 * Status of account reconciliation.
 */
public enum ReconciliationStatus {
    /**
     * Account balance snapshot perfectly matches the balance derived from immutable ledger entries.
     */
    CONSISTENT,

    /**
     * A divergence was detected between the account balance snapshot and the ledger-derived balance.
     */
    DISCREPANCY
}
