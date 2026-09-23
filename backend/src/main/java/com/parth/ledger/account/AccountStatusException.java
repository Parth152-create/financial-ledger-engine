package com.parth.ledger.account;

/**
 * Thrown when an operation cannot be performed because an account is not in the required status.
 */
public class AccountStatusException extends RuntimeException {
    public AccountStatusException(String message) {
        super(message);
    }
}
