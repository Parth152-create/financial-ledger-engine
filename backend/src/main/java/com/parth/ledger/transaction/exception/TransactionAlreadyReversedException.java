package com.parth.ledger.transaction.exception;

import java.util.UUID;

public class TransactionAlreadyReversedException extends RuntimeException {

    public TransactionAlreadyReversedException(String message) {
        super(message);
    }

    public TransactionAlreadyReversedException(UUID transactionId) {
        super("Transaction has already been reversed: " + transactionId);
    }
}
