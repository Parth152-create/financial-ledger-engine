package com.parth.ledger.recurring.dto;

import com.parth.ledger.recurring.RecurringFrequency;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Inbound payload for creating a recurring transfer schedule.
 */
public record CreateRecurringTransferRequestDto(
        @NotNull(message = "Source account ID is required")
        UUID sourceAccountId,

        @NotNull(message = "Destination account ID is required")
        UUID destinationAccountId,

        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.0001", message = "Amount must be greater than zero")
        BigDecimal amount,

        @NotBlank(message = "Currency is required")
        String currency,

        @NotNull(message = "Frequency is required")
        RecurringFrequency frequency,

        @NotNull(message = "Start date is required")
        LocalDate startDate,

        LocalDate endDate
) {
}
