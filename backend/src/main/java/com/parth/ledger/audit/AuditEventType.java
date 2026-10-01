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
    TRANSACTION_REVERSED,

    // Policy configuration events
    POLICY_CREATED,
    POLICY_UPDATED,
    POLICY_DELETED,

    // Financial transaction policy rejection events
    TRANSFER_REJECTED_POLICY,
    DEPOSIT_REJECTED_POLICY,
    WITHDRAWAL_REJECTED_POLICY
}
