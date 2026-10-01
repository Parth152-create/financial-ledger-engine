package com.parth.ledger.policy;

/**
 * Standardized domain error codes for financial policy violations.
 */
public enum PolicyErrorCode {
    POLICY_TRANSACTION_LIMIT_EXCEEDED,
    POLICY_DAILY_AMOUNT_LIMIT_EXCEEDED,
    POLICY_DAILY_COUNT_LIMIT_EXCEEDED,
    POLICY_BALANCE_LIMIT_EXCEEDED
}
