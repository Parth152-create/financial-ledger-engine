package com.parth.ledger.audit.controller;

import com.parth.ledger.audit.AuditEventService;
import com.parth.ledger.audit.dto.AuditEventPageResponseDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for retrieving operational audit events.
 * Provides authorized, paginated, and filtered access to audit activity.
 */
@RestController
@RequestMapping("/api/v1/audit-events")
public class AuditEventController {

    private final AuditEventService auditEventService;

    public AuditEventController(AuditEventService auditEventService) {
        this.auditEventService = auditEventService;
    }

    /**
     * Retrieves paginated operational audit events for the authenticated caller.
     * Non-admin callers only receive events within their domain ownership scope;
     * administrators can query events across the system.
     *
     * @param eventType  Optional filter by event type (e.g. TRANSFER_COMPLETED).
     * @param entityType Optional filter by entity type (e.g. TRANSACTION).
     * @param from       Optional ISO-8601 start timestamp (inclusive).
     * @param to         Optional ISO-8601 end timestamp (exclusive).
     * @param page       Zero-based page index (default: 0).
     * @param size       Page size limit (default: 20, max: 100).
     * @return 200 OK with AuditEventPageResponseDto.
     */
    @GetMapping
    public ResponseEntity<AuditEventPageResponseDto> getAuditEvents(
            @RequestParam(name = "eventType", required = false) String eventType,
            @RequestParam(name = "entityType", required = false) String entityType,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size
    ) {
        AuditEventPageResponseDto response = auditEventService.getAuditEvents(
                eventType,
                entityType,
                from,
                to,
                page,
                size
        );
        return ResponseEntity.ok(response);
    }
}
