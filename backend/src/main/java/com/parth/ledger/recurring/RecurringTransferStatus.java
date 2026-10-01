package com.parth.ledger.recurring;

/**
 * Controlled lifecycle states for recurring transfer schedules.
 * Transitions:
 * - ACTIVE -> PAUSED
 * - PAUSED -> ACTIVE
 * - ACTIVE -> CANCELLED (Terminal)
 * - PAUSED -> CANCELLED (Terminal)
 * - ACTIVE -> COMPLETED (Terminal, upon reaching end date)
 */
public enum RecurringTransferStatus {
    ACTIVE,
    PAUSED,
    COMPLETED,
    CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED;
    }
}
