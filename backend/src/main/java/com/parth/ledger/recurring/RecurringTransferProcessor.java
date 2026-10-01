package com.parth.ledger.recurring;

import com.parth.ledger.account.AccountClosedException;
import com.parth.ledger.observability.logging.MaskingUtils;
import com.parth.ledger.observability.metrics.LedgerMetrics;
import com.parth.ledger.transaction.dto.TransferRequestDto;
import com.parth.ledger.transaction.dto.TransferResponseDto;
import com.parth.ledger.transaction.service.TransferService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Production-grade background processor for recurring transfers.
 *
 * Core Architectural Invariants:
 * 1. A recurring transfer is SCHEDULING METADATA, NOT a second financial execution engine.
 * 2. Every execution reuses TransferService.executeTransfer atomically.
 * 3. PostgreSQL row-level locking (FOR UPDATE SKIP LOCKED) prevents concurrent worker races.
 * 4. Deterministic execution identity (scheduleId + scheduledSlot) guarantees idempotent financial effect.
 * 5. Catch-up execution: if multiple periods were missed during downtime, executes one catch-up
 *    and converges directly to the next future occurrence without cascading re-runs.
 * 6. Committed-state metrics strictly adhere to afterCommit lifecycle synchronization.
 */
@Component
public class RecurringTransferProcessor {

    private static final Logger log = LoggerFactory.getLogger(RecurringTransferProcessor.class);
    private static final int MAX_ERROR_LENGTH = 500;

    private final RecurringTransferRepository recurringTransferRepository;
    private final RecurringTransferExecutionRepository executionRepository;
    private final TransferService transferService;
    private final RecurringScheduleCalculator scheduleCalculator;
    private final RecurringTransferProperties properties;
    private final LedgerMetrics ledgerMetrics;
    private final Clock clock;
    private final PlatformTransactionManager transactionManager;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    public RecurringTransferProcessor(RecurringTransferRepository recurringTransferRepository,
                                      RecurringTransferExecutionRepository executionRepository,
                                      TransferService transferService,
                                      RecurringScheduleCalculator scheduleCalculator,
                                      RecurringTransferProperties properties,
                                      @Autowired(required = false) LedgerMetrics ledgerMetrics,
                                      @Autowired(required = false) Clock clock,
                                      @Autowired(required = false) PlatformTransactionManager transactionManager) {
        this.recurringTransferRepository = recurringTransferRepository;
        this.executionRepository = executionRepository;
        this.transferService = transferService;
        this.scheduleCalculator = scheduleCalculator;
        this.properties = properties != null ? properties : RecurringTransferProperties.defaults();
        this.ledgerMetrics = ledgerMetrics;
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.transactionManager = transactionManager;
        this.transactionTemplate = transactionManager != null ? new TransactionTemplate(transactionManager) : null;
    }

    /**
     * Periodic scheduled poller.
     * Triggered every pollIntervalMs (default 5000ms).
     */
    @Scheduled(fixedDelayString = "${ledger.recurring-transfers.poll-interval-ms:5000}")
    public void scheduleProcessing() {
        if (!properties.enabled()) {
            return;
        }
        try {
            processEligibleSchedules();
        } catch (Throwable t) {
            log.error("Unhandled error during scheduled recurring transfer processing: {}", t.getMessage(), t);
        }
    }

    /**
     * Claims and processes a batch of eligible ACTIVE schedules due up to current time.
     *
     * @return Number of schedules claimed and processed.
     */
    @Transactional
    public int processEligibleSchedules() {
        if (!properties.enabled()) {
            return 0;
        }
        return processBatch(properties.batchSize());
    }

    /**
     * Claims up to batchSize eligible schedules using FOR UPDATE SKIP LOCKED and processes them.
     *
     * @param batchSize Maximum schedules to claim in this batch.
     * @return Number of schedules processed.
     */
    @Transactional
    public int processBatch(int batchSize) {
        if (transactionTemplate != null) {
            Integer result = transactionTemplate.execute(status -> doProcessBatch(batchSize));
            return result != null ? result : 0;
        }
        return doProcessBatch(batchSize);
    }

    private int doProcessBatch(int batchSize) {
        int effectiveLimit = batchSize > 0 ? batchSize : properties.batchSize();
        Instant now = clock.instant();

        List<RecurringTransfer> schedules = recurringTransferRepository.claimEligibleSchedulesForUpdate(now, effectiveLimit);
        if (schedules.isEmpty()) {
            return 0;
        }

        log.debug("Claimed {} recurring transfer schedule(s) for processing", schedules.size());

        for (RecurringTransfer schedule : schedules) {
            processScheduleUnderLock(schedule);
        }

        recurringTransferRepository.saveAll(schedules);
        return schedules.size();
    }

    /**
     * Processes a single eligible schedule by ID under a row lock.
     *
     * @param scheduleId The UUID of the schedule.
     * @return true if an eligible schedule was found, locked, and processed; false otherwise.
     */
    @Transactional
    public boolean processSingleSchedule(UUID scheduleId) {
        if (transactionTemplate != null) {
            Boolean result = transactionTemplate.execute(status -> doProcessSingleSchedule(scheduleId));
            return Boolean.TRUE.equals(result);
        }
        return doProcessSingleSchedule(scheduleId);
    }

    private boolean doProcessSingleSchedule(UUID scheduleId) {
        if (scheduleId == null) {
            return false;
        }
        Instant now = clock.instant();
        Optional<RecurringTransfer> scheduleOpt = recurringTransferRepository.findEligibleScheduleForUpdateById(scheduleId, now);
        if (scheduleOpt.isEmpty()) {
            return false;
        }

        RecurringTransfer schedule = scheduleOpt.get();
        processScheduleUnderLock(schedule);
        recurringTransferRepository.save(schedule);
        return true;
    }

    /**
     * Computes the deterministic execution identity UUID for a recurring transfer schedule and scheduled slot.
     */
    public static UUID computeExecutionIdentity(UUID scheduleId, Instant scheduledFor) {
        return UUID.nameUUIDFromBytes(
                ("RECURRING:" + scheduleId + ":" + scheduledFor.toEpochMilli()).getBytes(StandardCharsets.UTF_8)
        );
    }

    private void processScheduleUnderLock(RecurringTransfer schedule) {
        long startTime = System.currentTimeMillis();
        Instant scheduledFor = schedule.getNextExecutionAt();

        // Deterministic execution identity generated from schedule ID + scheduled slot instant
        UUID executionIdentity = computeExecutionIdentity(schedule.getId(), scheduledFor);
        String executionKey = executionIdentity.toString();
        String idempotencyKey = executionIdentity.toString();

        log.info("Processing recurring transfer slot: scheduleId={}, scheduledFor={}, frequency={}, amount={}",
                MaskingUtils.maskAccountId(schedule.getId()),
                scheduledFor,
                schedule.getFrequency(),
                schedule.getAmount());

        TransferRequestDto transferRequest = new TransferRequestDto(
                schedule.getSourceAccount().getId(),
                schedule.getDestinationAccount().getId(),
                schedule.getAmount(),
                schedule.getCurrency()
        );

        // Establish the SecurityContext with the schedule owner identity so TransferService
        // and Financial Policy Engine execute in the user's authorized context.
        SecurityContext originalContext = SecurityContextHolder.getContext();
        try {
            SecurityContext userContext = SecurityContextHolder.createEmptyContext();
            userContext.setAuthentication(new UsernamePasswordAuthenticationToken(
                    schedule.getUser().getEmail(),
                    "N/A",
                    Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"))
            ));
            SecurityContextHolder.setContext(userContext);

            // Execute through the existing authoritative TransferService path (REQUIRES_NEW transaction)
            TransferResponseDto response = transferService.executeTransfer(idempotencyKey, transferRequest);
            long duration = System.currentTimeMillis() - startTime;

            handleExecutionSuccess(schedule, executionKey, scheduledFor, response.transactionId(), duration);

        } catch (Exception ex) {
            long duration = System.currentTimeMillis() - startTime;
            handleExecutionFailure(schedule, executionKey, scheduledFor, ex, duration);
        } finally {
            SecurityContextHolder.setContext(originalContext);
        }
    }

    private void handleExecutionSuccess(RecurringTransfer schedule,
                                        String executionKey,
                                        Instant scheduledFor,
                                        UUID transactionId,
                                        long duration) {
        // Record successful execution attempt in execution history if not already present
        Optional<RecurringTransferExecution> existingExec = executionRepository
                .findByRecurringTransferIdAndExecutionKey(schedule.getId(), executionKey);

        if (existingExec.isEmpty()) {
            RecurringTransferExecution execution = new RecurringTransferExecution(
                    schedule,
                    executionKey,
                    scheduledFor,
                    transactionId,
                    RecurringExecutionStatus.SUCCESS,
                    null,
                    clock.instant()
            );
            executionRepository.save(execution);
        }

        // Update schedule metadata
        schedule.incrementExecutionCount();
        schedule.setLastExecutedAt(clock.instant());

        // Advance to next scheduled occurrence (or complete if past end date)
        Optional<Instant> nextOccurrence = scheduleCalculator.calculateNextOccurrence(
                schedule.getStartDate(),
                schedule.getEndDate(),
                schedule.getFrequency(),
                scheduledFor,
                clock.instant()
        );

        if (nextOccurrence.isPresent()) {
            schedule.setNextExecutionAt(nextOccurrence.get());
        } else {
            schedule.setStatus(RecurringTransferStatus.COMPLETED);
            log.info("Recurring transfer schedule {} reached end date; transitioned to COMPLETED",
                    MaskingUtils.maskAccountId(schedule.getId()));
        }

        // Observability metrics emitted after database commit
        String frequencyName = schedule.getFrequency().name();
        if (ledgerMetrics != null) {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        ledgerMetrics.recordRecurringTransferExecuted(frequencyName, "SUCCESS");
                        ledgerMetrics.recordRecurringProcessingDuration(frequencyName, "SUCCESS", duration);
                    }
                });
            } else {
                ledgerMetrics.recordRecurringTransferExecuted(frequencyName, "SUCCESS");
                ledgerMetrics.recordRecurringProcessingDuration(frequencyName, "SUCCESS", duration);
            }
        }

        log.info("Successfully executed recurring transfer slot: scheduleId={}, transactionId={}, nextExecutionAt={}",
                MaskingUtils.maskAccountId(schedule.getId()),
                MaskingUtils.maskAccountId(transactionId),
                schedule.getNextExecutionAt());
    }

    private void handleExecutionFailure(RecurringTransfer schedule,
                                        String executionKey,
                                        Instant scheduledFor,
                                        Exception ex,
                                        long duration) {
        String failureReason = RecurringFailureSanitizer.sanitize(ex);

        // Record failed execution attempt in execution history if not already present
        Optional<RecurringTransferExecution> existingExec = executionRepository
                .findByRecurringTransferIdAndExecutionKey(schedule.getId(), executionKey);

        if (existingExec.isEmpty()) {
            RecurringTransferExecution execution = new RecurringTransferExecution(
                    schedule,
                    executionKey,
                    scheduledFor,
                    null,
                    RecurringExecutionStatus.FAILED,
                    failureReason,
                    clock.instant()
            );
            executionRepository.save(execution);
        }

        // Terminal account conditions: if either account is closed, cancel the schedule
        if (ex instanceof AccountClosedException) {
            schedule.setStatus(RecurringTransferStatus.CANCELLED);
            log.warn("Recurring transfer schedule {} cancelled due to closed account",
                    MaskingUtils.maskAccountId(schedule.getId()));
        } else {
            // Normal business failures (e.g. insufficient funds, policy limits):
            // Schedule remains ACTIVE and advances to next occurrence
            Optional<Instant> nextOccurrence = scheduleCalculator.calculateNextOccurrence(
                    schedule.getStartDate(),
                    schedule.getEndDate(),
                    schedule.getFrequency(),
                    scheduledFor,
                    clock.instant()
            );

            if (nextOccurrence.isPresent()) {
                schedule.setNextExecutionAt(nextOccurrence.get());
            } else {
                schedule.setStatus(RecurringTransferStatus.COMPLETED);
            }

            log.warn("Recurring transfer slot failed: scheduleId={}, failureReason={}, nextExecutionAt={}",
                    MaskingUtils.maskAccountId(schedule.getId()),
                    failureReason,
                    schedule.getNextExecutionAt());
        }

        // Observability metrics emitted after database commit
        String frequencyName = schedule.getFrequency().name();
        if (ledgerMetrics != null) {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        ledgerMetrics.recordRecurringTransferExecuted(frequencyName, "FAILED");
                        ledgerMetrics.recordRecurringTransferFailed(frequencyName);
                        ledgerMetrics.recordRecurringProcessingDuration(frequencyName, "FAILED", duration);
                    }
                });
            } else {
                ledgerMetrics.recordRecurringTransferExecuted(frequencyName, "FAILED");
                ledgerMetrics.recordRecurringTransferFailed(frequencyName);
                ledgerMetrics.recordRecurringProcessingDuration(frequencyName, "FAILED", duration);
            }
        }
    }
}
