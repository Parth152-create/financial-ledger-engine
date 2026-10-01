package com.parth.ledger.policy;

/**
 * Domain exception thrown when a requested financial mutation violates an applicable financial policy.
 */
public class PolicyViolationException extends RuntimeException {

    private final PolicyErrorCode errorCode;

    public PolicyViolationException(PolicyErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public PolicyErrorCode getErrorCode() {
        return errorCode;
    }
}
