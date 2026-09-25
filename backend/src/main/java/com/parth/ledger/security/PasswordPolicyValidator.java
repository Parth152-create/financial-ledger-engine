package com.parth.ledger.security;

import org.springframework.stereotype.Component;

/**
 * Validates password strength according to standard security guidelines.
 * Requirements:
 * - 8 to 128 characters
 * - At least one letter (a-z, A-Z)
 * - At least one digit (0-9)
 */
@Component
public class PasswordPolicyValidator {

    private static final int MIN_LENGTH = 8;
    private static final int MAX_LENGTH = 128;

    public void validate(String password) {
        if (password == null || password.isBlank()) {
            throw new InvalidPasswordException("Password is required and cannot be blank");
        }
        if (password.length() < MIN_LENGTH || password.length() > MAX_LENGTH) {
            throw new InvalidPasswordException("Password must be between " + MIN_LENGTH + " and " + MAX_LENGTH + " characters");
        }
        boolean hasLetter = false;
        boolean hasDigit = false;
        for (char c : password.toCharArray()) {
            if (Character.isLetter(c)) {
                hasLetter = true;
            } else if (Character.isDigit(c)) {
                hasDigit = true;
            }
        }
        if (!hasLetter || !hasDigit) {
            throw new InvalidPasswordException("Password must contain at least one letter and at least one number");
        }
    }
}
