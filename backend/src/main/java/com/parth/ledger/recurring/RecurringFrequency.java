package com.parth.ledger.recurring;

/**
 * Supported frequencies for recurring transfers.
 * Arbitrary cron expressions are strictly forbidden to ensure deterministic calendar arithmetic.
 */
public enum RecurringFrequency {
    DAILY,
    WEEKLY,
    MONTHLY
}
