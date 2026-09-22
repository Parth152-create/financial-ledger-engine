package com.parth.ledger.transaction.exception;

public class UnbalancedLedgerException extends RuntimeException {

    public UnbalancedLedgerException(String message) {
        super(message);
    }
}
