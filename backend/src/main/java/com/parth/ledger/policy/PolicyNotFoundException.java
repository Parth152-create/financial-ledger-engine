package com.parth.ledger.policy;

/**
 * Thrown when an administrative request references a financial policy ID that does not exist.
 */
public class PolicyNotFoundException extends RuntimeException {

    public PolicyNotFoundException(String message) {
        super(message);
    }
}
