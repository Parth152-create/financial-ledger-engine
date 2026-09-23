package com.parth.ledger.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Request payload for creating a new user checking account.
 * Client supplies strictly the account currency; all other attributes
 * (balance, status, accountType, accountNumber, ownership) are system-assigned.
 *
 * @param currency ISO 4217 3-letter uppercase alphabetic currency code (e.g., "USD").
 */
public record CreateAccountRequestDto(
        @NotNull(message = "Currency is required")
        @NotBlank(message = "Currency is required")
        @Pattern(regexp = "^[A-Z]{3}$", message = "Currency must be exactly 3 uppercase alphabetic characters (ISO 4217)")
        String currency
) {
}
