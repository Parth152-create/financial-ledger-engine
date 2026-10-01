package com.parth.ledger.audit;

/**
 * Controlled entity classifications describing what domain entity an audit event concerns.
 */
public enum AuditEntityType {
    USER,
    ACCOUNT,
    TRANSACTION,
    SYSTEM,
    POLICY,
    RECURRING_TRANSFER
}
