package com.parth.ledger.policy;

/**
 * Types of financial policies supported by the Policy Engine.
 */
public enum PolicyType {
    /**
     * Maximum amount allowed for a single financial transaction.
     */
    MAX_TRANSACTION_AMOUNT,

    /**
     * Maximum cumulative amount allowed per account per calendar day.
     */
    DAILY_TRANSACTION_AMOUNT,

    /**
     * Maximum number of applicable financial transactions per account per calendar day.
     */
    DAILY_TRANSACTION_COUNT,

    /**
     * Maximum balance an account may hold.
     */
    ACCOUNT_BALANCE_LIMIT
}
