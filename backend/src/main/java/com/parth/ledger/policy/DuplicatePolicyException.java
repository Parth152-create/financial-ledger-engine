package com.parth.ledger.policy;

/**
 * Exception thrown when attempting to define a policy that duplicates an existing scope, type, and transaction combination.
 */
public class DuplicatePolicyException extends RuntimeException {

    public DuplicatePolicyException(String message) {
        super(message);
    }
}
