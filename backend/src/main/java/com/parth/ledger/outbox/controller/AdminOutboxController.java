package com.parth.ledger.outbox.controller;

import com.parth.ledger.outbox.OutboxAggregateType;
import com.parth.ledger.outbox.OutboxEvent;
import com.parth.ledger.outbox.OutboxEventRepository;
import com.parth.ledger.outbox.OutboxEventType;
import com.parth.ledger.outbox.OutboxStatus;
import com.parth.ledger.outbox.dto.OutboxEventSummaryResponseDto;
import com.parth.ledger.outbox.dto.OutboxPageResponseDto;
import com.parth.ledger.outbox.specification.OutboxEventSpecifications;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.UUID;

/**
 * Administrative read-only REST controller for operational visibility into outbox events.
 * Strictly restricted to users possessing administrative authority (ROLE_ADMIN).
 */
@RestController
@RequestMapping("/api/v1/admin/outbox")
public class AdminOutboxController {

    private final OutboxEventRepository outboxEventRepository;

    public AdminOutboxController(OutboxEventRepository outboxEventRepository) {
        this.outboxEventRepository = outboxEventRepository;
    }

    /**
     * Retrieves a paginated list of operational outbox events with optional status and eventType filters.
     * Raw payloads are excluded to prevent sensitive internal data disclosure.
     *
     * @param statusStr    Optional filter by status (PENDING, PROCESSING, PROCESSED, FAILED).
     * @param eventTypeStr Optional filter by event type (e.g. TRANSFER_COMPLETED).
     * @param aggregateTypeStr Optional filter by aggregate type (e.g. TRANSACTION, ACCOUNT).
     * @param aggregateId  Optional filter by aggregate UUID.
     * @param page         Zero-based page index (default 0).
     * @param size         Page size limit (default 20, max 100).
     * @return Paginated summary of outbox events.
     */
    @GetMapping
    public ResponseEntity<OutboxPageResponseDto> getOutboxEvents(
            @RequestParam(name = "status", required = false) String statusStr,
            @RequestParam(name = "eventType", required = false) String eventTypeStr,
            @RequestParam(name = "aggregateType", required = false) String aggregateTypeStr,
            @RequestParam(name = "aggregateId", required = false) UUID aggregateId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size
    ) {
        if (page < 0) {
            throw new IllegalArgumentException("Page index must not be negative: " + page);
        }
        if (size < 1 || size > 100) {
            throw new IllegalArgumentException("Page size must be between 1 and 100: " + size);
        }

        OutboxStatus status = null;
        if (statusStr != null && !statusStr.isBlank()) {
            try {
                status = OutboxStatus.valueOf(statusStr.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid outbox status: " + statusStr);
            }
        }

        OutboxEventType eventType = null;
        if (eventTypeStr != null && !eventTypeStr.isBlank()) {
            try {
                eventType = OutboxEventType.valueOf(eventTypeStr.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid outbox event type: " + eventTypeStr);
            }
        }

        OutboxAggregateType aggregateType = null;
        if (aggregateTypeStr != null && !aggregateTypeStr.isBlank()) {
            try {
                aggregateType = OutboxAggregateType.valueOf(aggregateTypeStr.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid aggregate type: " + aggregateTypeStr);
            }
        }

        Specification<OutboxEvent> spec = OutboxEventSpecifications.withFilters(
                status,
                eventType,
                aggregateType,
                aggregateId
        );

        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        Page<OutboxEvent> resultPage = outboxEventRepository.findAll(spec, pageRequest);

        return ResponseEntity.ok(OutboxPageResponseDto.from(resultPage.map(OutboxEventSummaryResponseDto::from)));
    }
}
