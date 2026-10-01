package com.parth.ledger.outbox.dto;

import com.parth.ledger.outbox.OutboxAggregateType;
import com.parth.ledger.outbox.OutboxEvent;
import com.parth.ledger.outbox.OutboxEventType;
import com.parth.ledger.outbox.OutboxStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Public administrative response summary for outbox events.
 * Intentionally excludes raw payloads to prevent unauthorized exposure of payload structures.
 */
public record OutboxEventSummaryResponseDto(
        UUID id,
        OutboxAggregateType aggregateType,
        UUID aggregateId,
        OutboxEventType eventType,
        OutboxStatus status,
        int attemptCount,
        Instant createdAt,
        Instant availableAt,
        Instant processedAt,
        String lastError
) {
    public static OutboxEventSummaryResponseDto from(OutboxEvent event) {
        if (event == null) {
            return null;
        }
        return new OutboxEventSummaryResponseDto(
                event.getId(),
                event.getAggregateType(),
                event.getAggregateId(),
                event.getEventType(),
                event.getStatus(),
                event.getAttemptCount(),
                event.getCreatedAt(),
                event.getAvailableAt(),
                event.getProcessedAt(),
                event.getLastError()
        );
    }
}
