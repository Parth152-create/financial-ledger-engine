package com.parth.ledger.observability.logging;

import java.util.UUID;

/**
 * Utility functions for masking and sanitizing sensitive operational and financial data.
 * Prevents account numbers, internal account UUIDs, and credentials from leaking into log streams.
 */
public final class MaskingUtils {

    private MaskingUtils() {
        // Utility class
    }

    /**
     * Masks an account number, retaining only the trailing 4 characters.
     * Example: ACCT-111122223333 -> •••• 3333
     */
    public static String maskAccountNumber(String accountNumber) {
        if (accountNumber == null || accountNumber.isBlank()) {
            return "•••• ----";
        }
        String clean = accountNumber.trim();
        if (clean.length() <= 4) {
            return "•••• " + clean;
        }
        return "•••• " + clean.substring(clean.length() - 4);
    }

    /**
     * Masks an account UUID, exposing only the first 4 and last 4 characters.
     * Example: 71be1228-fbad-40ae-9709-c271f20efac8 -> 71be...fac8
     */
    public static String maskAccountId(UUID accountId) {
        if (accountId == null) {
            return "••••";
        }
        String idStr = accountId.toString();
        if (idStr.equals("00000000-0000-0000-0000-000000000001")) {
            return "[SYSTEM_CLEARING]";
        }
        if (idStr.equals("00000000-0000-0000-0000-000000000002")) {
            return "[SYSTEM_TREASURY]";
        }
        return idStr.substring(0, 4) + "..." + idStr.substring(idStr.length() - 4);
    }

    /**
     * Masks an email address for safe operational logging.
     * Example: alice@example.com -> a***@example.com
     */
    public static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return "";
        }
        int atIndex = email.indexOf('@');
        if (atIndex <= 1) {
            return "***" + email.substring(Math.max(0, atIndex));
        }
        return email.charAt(0) + "***" + email.substring(atIndex);
    }
}
