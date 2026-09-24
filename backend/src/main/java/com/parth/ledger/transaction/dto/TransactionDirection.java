package com.parth.ledger.transaction.dto;

/**
 * Account-relative direction of a financial transaction.
 *
 * DEBIT: Funds left the requested account (e.g. source of transfer).
 * CREDIT: Funds entered the requested account (e.g. destination of transfer or deposit).
 */
public enum TransactionDirection {
    DEBIT,
    CREDIT
}
