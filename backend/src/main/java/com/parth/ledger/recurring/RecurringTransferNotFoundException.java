package com.parth.ledger.recurring;

public class RecurringTransferNotFoundException extends RuntimeException {
    public RecurringTransferNotFoundException(String message) {
        super(message);
    }
}
