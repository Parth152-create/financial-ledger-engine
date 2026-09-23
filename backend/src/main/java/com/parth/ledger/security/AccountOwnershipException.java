package com.parth.ledger.security;

/**
 * Exception thrown when an authenticated user attempts to perform an operation
 * on an account that they do not own.
 */
public class AccountOwnershipException extends RuntimeException {

    public AccountOwnershipException(String message) {
        super(message);
    }

    public AccountOwnershipException(String message, Throwable cause) {
        super(message, cause);
    }
}
