package com.parth.ledger.audit.dto;

import com.parth.ledger.audit.AuditEvent;
import com.parth.ledger.audit.AuditEntityType;
import com.parth.ledger.audit.AuditEventType;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Public response DTO for operational audit events.
 * Intentionally excludes internal operational data (such as IP addresses and User-Agents)
 * to prevent unauthorized information disclosure.
 */
public record AuditEventResponseDto(
        UUID id,
        AuditEventType eventType,
        AuditEntityType entityType,
        UUID entityId,
        Instant createdAt,
        Map<String, Object> metadata
) {
    public static AuditEventResponseDto from(AuditEvent event) {
        if (event == null) {
            return null;
        }
        return new AuditEventResponseDto(
                event.getId(),
                event.getEventType(),
                event.getEntityType(),
                event.getEntityId(),
                event.getCreatedAt(),
                event.getMetadata()
        );
    }
}
