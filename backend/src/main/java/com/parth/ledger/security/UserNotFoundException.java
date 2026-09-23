package com.parth.ledger.security;

/**
 * Exception thrown when an authenticated principal cannot be mapped
 * to a persistent application User in the database.
 */
public class UserNotFoundException extends RuntimeException {

    public UserNotFoundException(String message) {
        super(message);
    }

    public UserNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
