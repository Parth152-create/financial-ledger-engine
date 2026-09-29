package com.parth.ledger.transaction.exception;

public class TransactionNotReversibleException extends RuntimeException {

    public TransactionNotReversibleException(String message) {
        super(message);
    }
}
