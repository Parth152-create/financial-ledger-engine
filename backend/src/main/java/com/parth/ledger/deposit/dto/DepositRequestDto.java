package com.parth.ledger.deposit.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Request payload for depositing funds into a user account.
 * Client supplies strictly destination accountId, amount, currency, and optional description.
 * System accounts, types, statuses, initiating user, and idempotency keys cannot be supplied in body.
 */
public record DepositRequestDto(
        @NotNull(message = "accountId is required")
        UUID accountId,

        @NotNull(message = "amount is required")
        @DecimalMin(value = "0.0001", inclusive = true, message = "amount must be greater than zero")
        @Digits(integer = 15, fraction = 4, message = "amount precision cannot exceed 4 decimal places")
        BigDecimal amount,

        @NotNull(message = "currency is required")
        @NotBlank(message = "currency is required")
        @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be a 3-character ISO code")
        String currency,

        @Size(max = 255, message = "description cannot exceed 255 characters")
        String description
) {
    public DepositRequestDto(UUID accountId, BigDecimal amount, String currency) {
        this(accountId, amount, currency, null);
    }
}
