package com.parth.ledger.audit;

/**
 * Controlled domain event types recorded in the operational audit trail.
 * Represents meaningful business, authentication, account, and financial actions.
 */
public enum AuditEventType {
    // Authentication events
    AUTH_SIGNUP,
    AUTH_LOGIN,
    AUTH_LOGOUT,
    PASSWORD_CHANGED,

    // Account lifecycle events
    ACCOUNT_CREATED,
    ACCOUNT_FROZEN,
    ACCOUNT_UNFROZEN,
    ACCOUNT_CLOSED,

    // Financial transaction completion and reversal events
    TRANSFER_COMPLETED,
    DEPOSIT_COMPLETED,
    WITHDRAWAL_COMPLETED,
    TRANSACTION_REVERSED
}
