package com.parth.ledger.outbox;

import com.parth.ledger.observability.logging.MaskingUtils;
import com.parth.ledger.observability.metrics.LedgerMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Service orchestrating the transactional creation and persistence of outbox events.
 *
 * All operations within recordEvent participate in the caller's active PostgreSQL database transaction.
 * If the financial or account mutation rolls back, the outbox event is rolled back atomically.
 * If the transaction commits, the outbox event is guaranteed to persist.
 */
@Service
public class OutboxService {

    private static final Logger log = LoggerFactory.getLogger(OutboxService.class);

    private final OutboxEventRepository outboxEventRepository;
    private final LedgerMetrics ledgerMetrics;

    @Autowired
    public OutboxService(OutboxEventRepository outboxEventRepository,
                         @Autowired(required = false) LedgerMetrics ledgerMetrics) {
        this.outboxEventRepository = outboxEventRepository;
        this.ledgerMetrics = ledgerMetrics;
    }

    public OutboxService(OutboxEventRepository outboxEventRepository) {
        this(outboxEventRepository, null);
    }

    /**
     * Persists an outbox event atomically within the caller's active database transaction.
     *
     * @param aggregateType The aggregate type (e.g. TRANSACTION, ACCOUNT).
     * @param aggregateId   The aggregate UUID identifier.
     * @param eventType     The explicit outbox event type.
     * @param payload       The event payload map (must be self-contained and free of secrets).
     * @return The persisted OutboxEvent entity.
     */
    @Transactional
    public OutboxEvent recordEvent(OutboxAggregateType aggregateType,
                                   UUID aggregateId,
                                   OutboxEventType eventType,
                                   Map<String, Object> payload) {
        Objects.requireNonNull(aggregateType, "aggregateType must not be null");
        Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");

        UUID eventId = UUID.randomUUID();
        Instant now = Instant.now();

        Map<String, Object> enrichedPayload = new HashMap<>(payload != null ? payload : Map.of());
        enrichedPayload.put("eventId", eventId.toString());
        enrichedPayload.put("eventType", eventType.name());
        if (!enrichedPayload.containsKey("occurredAt")) {
            enrichedPayload.put("occurredAt", now.toString());
        }

        OutboxEvent event = new OutboxEvent(
                eventId,
                aggregateType,
                aggregateId,
                eventType,
                enrichedPayload,
                OutboxStatus.PENDING,
                0,
                now,
                null,
                null
        );

        OutboxEvent saved = outboxEventRepository.save(event);
        log.debug("Persisted transactional outbox event: id={}, type={}, aggregateType={}, aggregateId={}",
                saved.getId(), saved.getEventType(), saved.getAggregateType(),
                MaskingUtils.maskAccountId(saved.getAggregateId()));

        // Increment created metric ONLY after the enclosing PostgreSQL transaction successfully commits
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    if (ledgerMetrics != null) {
                        ledgerMetrics.recordOutboxEventCreated(eventType.name());
                    }
                }
            });
        } else {
            if (ledgerMetrics != null) {
                ledgerMetrics.recordOutboxEventCreated(eventType.name());
            }
        }

        return saved;
    }
}
