package com.parth.ledger.account;

/**
 * Thrown when an account type is not permitted for an attempted operation
 * (e.g. attempting a user transfer involving a SYSTEM_CLEARING account).
 */
public class InvalidAccountTypeException extends RuntimeException {
    public InvalidAccountTypeException(String message) {
        super(message);
    }
}
