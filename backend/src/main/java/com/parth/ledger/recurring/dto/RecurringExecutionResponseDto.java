package com.parth.ledger.recurring.dto;

import com.parth.ledger.recurring.RecurringExecutionStatus;
import com.parth.ledger.recurring.RecurringFailureSanitizer;
import com.parth.ledger.recurring.RecurringTransferExecution;

import java.time.Instant;
import java.util.UUID;

/**
 * Public representation of an execution attempt for a recurring transfer schedule slot.
 * Omit internal keys or raw idempotency details to preserve security.
 */
public record RecurringExecutionResponseDto(
        UUID id,
        UUID recurringTransferId,
        Instant scheduledFor,
        UUID transactionId,
        RecurringExecutionStatus status,
        String failureReason,
        Instant executedAt,
        Instant createdAt
) {
    public static RecurringExecutionResponseDto from(RecurringTransferExecution entity) {
        if (entity == null) {
            return null;
        }
        return new RecurringExecutionResponseDto(
                entity.getId(),
                entity.getRecurringTransfer().getId(),
                entity.getScheduledFor(),
                entity.getTransactionId(),
                entity.getStatus(),
                RecurringFailureSanitizer.sanitizeString(entity.getFailureReason()),
                entity.getExecutedAt(),
                entity.getCreatedAt()
        );
    }
}
