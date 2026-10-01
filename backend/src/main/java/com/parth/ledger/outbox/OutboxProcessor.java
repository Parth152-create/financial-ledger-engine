package com.parth.ledger.outbox;

import com.parth.ledger.observability.logging.MaskingUtils;
import com.parth.ledger.observability.metrics.LedgerMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Production-grade background processor for the Transactional Outbox pattern.
 *
 * Concurrency Safety:
 * Employs PostgreSQL row-level locking (FOR UPDATE SKIP LOCKED) to guarantee that multiple
 * application instances or worker threads can execute concurrently without processing duplicate events
 * or blocking each other.
 *
 * Processing Semantics:
 * Guarantees AT-LEAST-ONCE event processing. Downstream handlers must be idempotent.
 * Normal transient failures return to PENDING with exponential backoff.
 * Events exceeding max attempts transition to terminal FAILED state.
 */
@Component
public class OutboxProcessor {

    private static final Logger log = LoggerFactory.getLogger(OutboxProcessor.class);
    private static final int MAX_ERROR_LENGTH = 500;

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxEventDispatcher outboxEventDispatcher;
    private final OutboxProperties properties;
    private final LedgerMetrics ledgerMetrics;
    private final PlatformTransactionManager transactionManager;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    public OutboxProcessor(OutboxEventRepository outboxEventRepository,
                           OutboxEventDispatcher outboxEventDispatcher,
                           OutboxProperties properties,
                           @Autowired(required = false) LedgerMetrics ledgerMetrics,
                           @Autowired(required = false) PlatformTransactionManager transactionManager) {
        this.outboxEventRepository = outboxEventRepository;
        this.outboxEventDispatcher = outboxEventDispatcher;
        this.properties = properties != null ? properties : OutboxProperties.defaults();
        this.ledgerMetrics = ledgerMetrics;
        this.transactionManager = transactionManager;
        this.transactionTemplate = transactionManager != null ? new TransactionTemplate(transactionManager) : null;
    }

    public OutboxProcessor(OutboxEventRepository outboxEventRepository,
                           OutboxEventDispatcher outboxEventDispatcher,
                           OutboxProperties properties,
                           LedgerMetrics ledgerMetrics) {
        this(outboxEventRepository, outboxEventDispatcher, properties, ledgerMetrics, null);
    }

    /**
     * Periodic scheduled poller.
     * Triggered every pollIntervalMs (default 1000ms).
     */
    @Scheduled(fixedDelayString = "${ledger.outbox.poll-interval-ms:1000}")
    public void scheduleProcessing() {
        if (!properties.enabled()) {
            return;
        }
        try {
            processEligibleEvents();
        } catch (Throwable t) {
            log.error("Unhandled error during scheduled outbox processing: {}", t.getMessage(), t);
        }
    }

    /**
     * Claims and processes a batch of eligible PENDING outbox events.
     *
     * @return Number of events claimed and processed.
     */
    @Transactional
    public int processEligibleEvents() {
        if (!properties.enabled()) {
            return 0;
        }
        return processBatch(properties.batchSize());
    }

    /**
     * Claims up to batchSize eligible events using FOR UPDATE SKIP LOCKED and processes them.
     *
     * @param batchSize Maximum events to process in this batch.
     * @return Number of events processed.
     */
    @Transactional
    public int processBatch(int batchSize) {
        if (transactionTemplate != null) {
            return transactionTemplate.execute(status -> doProcessBatch(batchSize));
        }
        return doProcessBatch(batchSize);
    }

    private int doProcessBatch(int batchSize) {
        int effectiveLimit = batchSize > 0 ? batchSize : properties.batchSize();
        List<OutboxEvent> events = outboxEventRepository.claimPendingEventsForUpdate(effectiveLimit);

        if (events.isEmpty()) {
            return 0;
        }

        log.debug("Claimed {} outbox event(s) for processing", events.size());

        for (OutboxEvent event : events) {
            processEventUnderLock(event);
        }

        outboxEventRepository.saveAll(events);
        return events.size();
    }

    /**
     * Processes a single event by ID under a row lock.
     *
     * @param eventId The UUID of the outbox event to process.
     * @return true if an eligible event was found, locked, and processed; false otherwise.
     */
    @Transactional
    public boolean processSingleEvent(UUID eventId) {
        if (transactionTemplate != null) {
            Boolean result = transactionTemplate.execute(status -> doProcessSingleEvent(eventId));
            return Boolean.TRUE.equals(result);
        }
        return doProcessSingleEvent(eventId);
    }

    private boolean doProcessSingleEvent(UUID eventId) {
        if (eventId == null) {
            return false;
        }
        Optional<OutboxEvent> eventOpt = outboxEventRepository.findPendingEventForUpdateById(eventId);
        if (eventOpt.isEmpty()) {
            return false;
        }

        OutboxEvent event = eventOpt.get();
        processEventUnderLock(event);
        outboxEventRepository.save(event);
        return true;
    }

    private void processEventUnderLock(OutboxEvent event) {
        long startTime = System.currentTimeMillis();
        event.setStatus(OutboxStatus.PROCESSING);

        try {
            outboxEventDispatcher.dispatch(event);

            event.setStatus(OutboxStatus.PROCESSED);
            event.setProcessedAt(Instant.now());
            event.setLastError(null);

            long duration = System.currentTimeMillis() - startTime;
            String eventTypeName = event.getEventType().name();
            if (ledgerMetrics != null) {
                if (TransactionSynchronizationManager.isSynchronizationActive()) {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            ledgerMetrics.recordOutboxEventProcessed(eventTypeName, "SUCCESS");
                            ledgerMetrics.recordOutboxProcessingDuration(eventTypeName, "SUCCESS", duration);
                        }
                    });
                } else {
                    ledgerMetrics.recordOutboxEventProcessed(eventTypeName, "SUCCESS");
                    ledgerMetrics.recordOutboxProcessingDuration(eventTypeName, "SUCCESS", duration);
                }
            }

            log.info("Outbox event successfully processed: id={}, eventType={}, aggregateType={}, aggregateId={}",
                    event.getId(),
                    event.getEventType(),
                    event.getAggregateType(),
                    MaskingUtils.maskAccountId(event.getAggregateId()));

        } catch (Exception ex) {
            long duration = System.currentTimeMillis() - startTime;
            handleProcessingFailure(event, ex, duration);
        }
    }

    private void handleProcessingFailure(OutboxEvent event, Exception ex, long duration) {
        int nextAttempt = event.getAttemptCount() + 1;
        event.setAttemptCount(nextAttempt);

        String sanitizedError = sanitizeErrorMessage(ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
        event.setLastError(sanitizedError);
        String eventTypeName = event.getEventType().name();

        if (nextAttempt >= properties.maxAttempts()) {
            event.setStatus(OutboxStatus.FAILED);
            log.error("Outbox event reached maximum attempts ({}) and marked FAILED: id={}, eventType={}, aggregateId={}, error={}",
                    properties.maxAttempts(),
                    event.getId(),
                    event.getEventType(),
                    MaskingUtils.maskAccountId(event.getAggregateId()),
                    sanitizedError);

            if (ledgerMetrics != null) {
                if (TransactionSynchronizationManager.isSynchronizationActive()) {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            ledgerMetrics.recordOutboxEventProcessed(eventTypeName, "FAILED");
                            ledgerMetrics.recordOutboxEventFailed(eventTypeName);
                            ledgerMetrics.recordOutboxProcessingDuration(eventTypeName, "FAILED", duration);
                        }
                    });
                } else {
                    ledgerMetrics.recordOutboxEventProcessed(eventTypeName, "FAILED");
                    ledgerMetrics.recordOutboxEventFailed(eventTypeName);
                    ledgerMetrics.recordOutboxProcessingDuration(eventTypeName, "FAILED", duration);
                }
            }
        } else {
            event.setStatus(OutboxStatus.PENDING);
            long delaySeconds = calculateBackoffDelay(nextAttempt);
            event.setAvailableAt(Instant.now().plusSeconds(delaySeconds));

            log.warn("Outbox event processing failed (attempt {}/{}). Scheduled retry in {}s: id={}, eventType={}, aggregateId={}, error={}",
                    nextAttempt,
                    properties.maxAttempts(),
                    delaySeconds,
                    event.getId(),
                    event.getEventType(),
                    MaskingUtils.maskAccountId(event.getAggregateId()),
                    sanitizedError);

            if (ledgerMetrics != null) {
                if (TransactionSynchronizationManager.isSynchronizationActive()) {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            ledgerMetrics.recordOutboxEventProcessed(eventTypeName, "RETRY");
                            ledgerMetrics.recordOutboxEventRetried(eventTypeName);
                            ledgerMetrics.recordOutboxProcessingDuration(eventTypeName, "FAILED", duration);
                        }
                    });
                } else {
                    ledgerMetrics.recordOutboxEventProcessed(eventTypeName, "RETRY");
                    ledgerMetrics.recordOutboxEventRetried(eventTypeName);
                    ledgerMetrics.recordOutboxProcessingDuration(eventTypeName, "FAILED", duration);
                }
            }
        }
    }

    /**
     * Calculates exponential backoff delay in seconds: baseDelay * 2^(attempt - 1), capped at maxDelay.
     *
     * @param attempt The 1-based attempt number.
     * @return Backoff delay in seconds.
     */
    public long calculateBackoffDelay(int attempt) {
        long base = properties.baseDelaySeconds();
        long max = properties.maxDelaySeconds();
        int exponent = Math.max(0, attempt - 1);
        if (exponent >= 30) {
            return max;
        }
        long delay = (long) (base * Math.pow(2, exponent));
        return Math.min(delay, max);
    }

    /**
     * Sanitizes error message to prevent leaking secrets, credentials, or oversized dumps.
     */
    public static String sanitizeErrorMessage(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return "Unknown processing error";
        }
        String clean = rawMessage.trim().replaceAll("[\r\n\t]+", " ");
        if (clean.length() > MAX_ERROR_LENGTH) {
            clean = clean.substring(0, MAX_ERROR_LENGTH) + "...";
        }
        return clean;
    }
}
