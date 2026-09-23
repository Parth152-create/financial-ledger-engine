package com.parth.ledger.account;

/**
 * Thrown when a transfer or operation is attempted on a FROZEN account.
 */
public class AccountFrozenException extends AccountStatusException {
    public AccountFrozenException(String message) {
        super(message);
    }
}
