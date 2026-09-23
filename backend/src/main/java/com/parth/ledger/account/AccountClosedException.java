package com.parth.ledger.account;

/**
 * Thrown when a transfer or operation is attempted on a CLOSED account.
 */
public class AccountClosedException extends AccountStatusException {
    public AccountClosedException(String message) {
        super(message);
    }
}
