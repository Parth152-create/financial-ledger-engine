package com.parth.ledger.transaction.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

public record TransferRequestDto(
        @NotNull(message = "sourceAccountId is required")
        UUID sourceAccountId,

        @NotNull(message = "destinationAccountId is required")
        UUID destinationAccountId,

        @NotNull(message = "amount is required")
        @DecimalMin(value = "0.0001", inclusive = true, message = "amount must be greater than zero")
        @Digits(integer = 15, fraction = 4, message = "amount precision cannot exceed 4 decimal places")
        BigDecimal amount,

        @NotBlank(message = "currency is required")
        @Size(min = 3, max = 3, message = "currency must be a 3-character ISO code")
        String currency
) {
}
