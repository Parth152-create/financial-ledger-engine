package com.parth.ledger.recurring;

import com.parth.ledger.account.AccountClosedException;
import com.parth.ledger.account.AccountFrozenException;
import com.parth.ledger.account.AccountNotFoundException;
import com.parth.ledger.account.AccountStatusException;
import com.parth.ledger.account.InvalidAccountTypeException;
import com.parth.ledger.policy.PolicyViolationException;
import com.parth.ledger.security.AccountOwnershipException;
import com.parth.ledger.security.ratelimit.RateLimitExceededException;
import com.parth.ledger.transaction.exception.CurrencyMismatchException;
import com.parth.ledger.transaction.exception.IdempotencyConflictException;
import com.parth.ledger.transaction.exception.InsufficientBalanceException;
import com.parth.ledger.transaction.exception.InvalidAmountException;
import com.parth.ledger.transaction.exception.SameAccountTransferException;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.regex.Pattern;

/**
 * Utility for sanitizing recurring transfer execution failure messages into stable, bounded,
 * user-safe categories.
 *
 * Guarantees that internal database errors, raw stack traces, account UUIDs, account numbers,
 * balances, or internal implementation details are never persisted, logged, or returned via API.
 */
public final class RecurringFailureSanitizer {

    public static final String INSUFFICIENT_BALANCE = "Insufficient balance in source account.";
    public static final String ACCOUNT_FROZEN = "Account is frozen.";
    public static final String ACCOUNT_CLOSED = "Account is closed.";
    public static final String POLICY_LIMIT_EXCEEDED = "Financial policy limit exceeded.";
    public static final String ACCOUNT_NOT_FOUND = "Account not found.";
    public static final String AUTHORIZATION_REJECTED = "Account ownership verification failed.";
    public static final String INVALID_ACCOUNT_STATE = "Account is not in active state.";
    public static final String INVALID_ACCOUNT_TYPE = "Invalid account type for transfer.";
    public static final String SAME_ACCOUNT_TRANSFER = "Source and destination accounts must be different.";
    public static final String INVALID_AMOUNT = "Invalid transfer amount.";
    public static final String CURRENCY_MISMATCH = "Currency mismatch.";
    public static final String IDEMPOTENCY_CONFLICT = "Idempotency conflict occurred during execution.";
    public static final String CONSTRAINT_VIOLATION = "Database constraint violation during transfer.";
    public static final String RATE_LIMIT_EXCEEDED = "Rate limit exceeded.";
    public static final String GENERIC_FAILURE = "Transfer could not be completed.";

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
    );

    private RecurringFailureSanitizer() {
        // Utility class
    }

    /**
     * Maps known execution exceptions to stable, user-safe failure reasons.
     * Unknown exceptions fallback to GENERIC_FAILURE.
     */
    public static String sanitize(Throwable ex) {
        if (ex == null) {
            return GENERIC_FAILURE;
        }
        if (ex instanceof InsufficientBalanceException) {
            return INSUFFICIENT_BALANCE;
        }
        if (ex instanceof AccountFrozenException) {
            return ACCOUNT_FROZEN;
        }
        if (ex instanceof AccountClosedException) {
            return ACCOUNT_CLOSED;
        }
        if (ex instanceof PolicyViolationException) {
            return POLICY_LIMIT_EXCEEDED;
        }
        if (ex instanceof AccountNotFoundException) {
            return ACCOUNT_NOT_FOUND;
        }
        if (ex instanceof AccountOwnershipException) {
            return AUTHORIZATION_REJECTED;
        }
        if (ex instanceof AccountStatusException) {
            return INVALID_ACCOUNT_STATE;
        }
        if (ex instanceof InvalidAccountTypeException) {
            return INVALID_ACCOUNT_TYPE;
        }
        if (ex instanceof SameAccountTransferException) {
            return SAME_ACCOUNT_TRANSFER;
        }
        if (ex instanceof InvalidAmountException) {
            return INVALID_AMOUNT;
        }
        if (ex instanceof CurrencyMismatchException) {
            return CURRENCY_MISMATCH;
        }
        if (ex instanceof IdempotencyConflictException) {
            return IDEMPOTENCY_CONFLICT;
        }
        if (ex instanceof DataIntegrityViolationException) {
            return CONSTRAINT_VIOLATION;
        }
        if (ex instanceof RateLimitExceededException) {
            return RATE_LIMIT_EXCEEDED;
        }
        return GENERIC_FAILURE;
    }

    /**
     * Defense-in-depth sanitization of raw string reasons.
     * Prevents UUIDs, balances, or class names from leaking into responses.
     */
    public static String sanitizeString(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        if (UUID_PATTERN.matcher(reason).find()
                || reason.contains("Exception")
                || reason.contains("org.")
                || reason.contains("com.parth")
                || reason.contains("available")
                || reason.contains("required")
                || reason.length() > 100) {
            return GENERIC_FAILURE;
        }
        return reason;
    }
}
