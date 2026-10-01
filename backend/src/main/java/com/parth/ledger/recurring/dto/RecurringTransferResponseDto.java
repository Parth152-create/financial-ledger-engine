package com.parth.ledger.recurring.dto;

import com.parth.ledger.recurring.RecurringFrequency;
import com.parth.ledger.recurring.RecurringTransfer;
import com.parth.ledger.recurring.RecurringTransferStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Public representation of a recurring transfer schedule.
 */
public record RecurringTransferResponseDto(
        UUID id,
        UUID userId,
        UUID sourceAccountId,
        UUID destinationAccountId,
        BigDecimal amount,
        String currency,
        RecurringFrequency frequency,
        RecurringTransferStatus status,
        Instant nextExecutionAt,
        LocalDate startDate,
        LocalDate endDate,
        int executionCount,
        int failureCount,
        Instant lastExecutedAt,
        Instant createdAt,
        Instant updatedAt
) {
    public static RecurringTransferResponseDto from(RecurringTransfer entity, int failureCount) {
        if (entity == null) {
            return null;
        }
        return new RecurringTransferResponseDto(
                entity.getId(),
                entity.getUser().getId(),
                entity.getSourceAccount().getId(),
                entity.getDestinationAccount().getId(),
                entity.getAmount(),
                entity.getCurrency(),
                entity.getFrequency(),
                entity.getStatus(),
                entity.getNextExecutionAt(),
                entity.getStartDate(),
                entity.getEndDate(),
                entity.getExecutionCount(),
                failureCount,
                entity.getLastExecutedAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    public static RecurringTransferResponseDto from(RecurringTransfer entity) {
        return from(entity, 0);
    }
}
