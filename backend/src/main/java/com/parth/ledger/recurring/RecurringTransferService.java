package com.parth.ledger.recurring;

import com.parth.ledger.account.Account;
import com.parth.ledger.account.AccountNotFoundException;
import com.parth.ledger.account.AccountRepository;
import com.parth.ledger.account.AccountStatus;
import com.parth.ledger.account.AccountStatusException;
import com.parth.ledger.account.AccountType;
import com.parth.ledger.account.InvalidAccountTypeException;
import com.parth.ledger.audit.AuditEntityType;
import com.parth.ledger.audit.AuditEventService;
import com.parth.ledger.audit.AuditEventType;
import com.parth.ledger.observability.logging.MaskingUtils;
import com.parth.ledger.observability.metrics.LedgerMetrics;
import com.parth.ledger.recurring.dto.CreateRecurringTransferRequestDto;
import com.parth.ledger.recurring.dto.RecurringExecutionPageResponseDto;
import com.parth.ledger.recurring.dto.RecurringExecutionResponseDto;
import com.parth.ledger.recurring.dto.RecurringTransferPageResponseDto;
import com.parth.ledger.recurring.dto.RecurringTransferResponseDto;
import com.parth.ledger.security.AccountOwnershipException;
import com.parth.ledger.security.AuthenticatedUserService;
import com.parth.ledger.transaction.exception.InvalidAmountException;
import com.parth.ledger.transaction.exception.SameAccountTransferException;
import com.parth.ledger.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Service managing recurring transfer scheduling definitions and lifecycle state transitions.
 *
 * A recurring transfer is SCHEDULING METADATA, not a secondary financial execution engine.
 * Creation, modification, pause, resume, and cancellation NEVER perform financial mutations
 * or create ledger entries.
 */
@Service
public class RecurringTransferService {

    private static final Logger log = LoggerFactory.getLogger(RecurringTransferService.class);

    private final RecurringTransferRepository recurringTransferRepository;
    private final RecurringTransferExecutionRepository executionRepository;
    private final AccountRepository accountRepository;
    private final AuthenticatedUserService authenticatedUserService;
    private final AuditEventService auditEventService;
    private final RecurringScheduleCalculator scheduleCalculator;
    private final LedgerMetrics ledgerMetrics;
    private final Clock clock;

    @Autowired
    public RecurringTransferService(RecurringTransferRepository recurringTransferRepository,
                                  RecurringTransferExecutionRepository executionRepository,
                                  AccountRepository accountRepository,
                                  AuthenticatedUserService authenticatedUserService,
                                  AuditEventService auditEventService,
                                  RecurringScheduleCalculator scheduleCalculator,
                                  @Autowired(required = false) LedgerMetrics ledgerMetrics,
                                  @Autowired(required = false) Clock clock) {
        this.recurringTransferRepository = recurringTransferRepository;
        this.executionRepository = executionRepository;
        this.accountRepository = accountRepository;
        this.authenticatedUserService = authenticatedUserService;
        this.auditEventService = auditEventService;
        this.scheduleCalculator = scheduleCalculator;
        this.ledgerMetrics = ledgerMetrics;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    /**
     * Creates a new recurring transfer schedule for the authenticated user.
     * Creation DOES NOT perform any financial transfer, create transactions, or alter balances.
     *
     * @param request The schedule creation parameters.
     * @return RecurringTransferResponseDto representing the created schedule.
     */
    @Transactional
    public RecurringTransferResponseDto createRecurringTransfer(CreateRecurringTransferRequestDto request) {
        if (request == null) {
            throw new IllegalArgumentException("Recurring transfer request must not be null");
        }
        if (request.sourceAccountId() == null) {
            throw new IllegalArgumentException("Source account ID is required");
        }
        if (request.destinationAccountId() == null) {
            throw new IllegalArgumentException("Destination account ID is required");
        }
        if (request.sourceAccountId().equals(request.destinationAccountId())) {
            throw new SameAccountTransferException(
                    "Source and destination accounts must be different: " + request.sourceAccountId()
            );
        }

        // Validate amount
        if (request.amount() == null || request.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidAmountException("Transfer amount must be greater than zero");
        }
        BigDecimal scaledAmount = request.amount().setScale(4, RoundingMode.HALF_UP);

        // Validate currency
        if (request.currency() == null || request.currency().trim().isEmpty()) {
            throw new IllegalArgumentException("Currency must not be blank");
        }
        String currency = request.currency().trim().toUpperCase(Locale.ROOT);
        if (!"INR".equals(currency)) {
            throw new IllegalArgumentException("Only INR currency is supported: " + currency);
        }

        // Validate frequency
        if (request.frequency() == null) {
            throw new IllegalArgumentException("Frequency is required");
        }

        // Validate dates
        LocalDate startDate = request.startDate();
        if (startDate == null) {
            throw new IllegalArgumentException("Start date is required");
        }
        LocalDate endDate = request.endDate();
        if (endDate != null && endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("End date must be greater than or equal to start date");
        }

        User currentUser = authenticatedUserService.getCurrentUser();

        // Validate source account
        Account sourceAccount = accountRepository.findById(request.sourceAccountId())
                .orElseThrow(() -> new AccountNotFoundException("Source account not found: " + request.sourceAccountId()));

        if (sourceAccount.getUser() == null || !sourceAccount.getUser().getId().equals(currentUser.getId())) {
            log.warn("Unauthorized attempt to schedule recurring transfer from account {} by user {}",
                    MaskingUtils.maskAccountId(request.sourceAccountId()),
                    MaskingUtils.maskAccountId(currentUser.getId()));
            throw new AccountOwnershipException("Authenticated user does not own source account");
        }

        if (sourceAccount.getAccountType() != AccountType.USER_CHECKING) {
            throw new InvalidAccountTypeException("Source account must be a USER_CHECKING account: " + request.sourceAccountId());
        }

        if (sourceAccount.getStatus() != AccountStatus.ACTIVE) {
            throw new AccountStatusException("Source account is not active: status is " + sourceAccount.getStatus());
        }

        // Validate destination account
        Account destinationAccount = accountRepository.findById(request.destinationAccountId())
                .orElseThrow(() -> new AccountNotFoundException("Destination account not found: " + request.destinationAccountId()));

        if (destinationAccount.getAccountType() != AccountType.USER_CHECKING) {
            throw new InvalidAccountTypeException("Destination account must be a USER_CHECKING account: " + request.destinationAccountId());
        }

        if (destinationAccount.getStatus() != AccountStatus.ACTIVE) {
            throw new AccountStatusException("Destination account is not active: status is " + destinationAccount.getStatus());
        }

        // First execution instant corresponds to the schedule start date in UTC
        Instant initialNextExecution = scheduleCalculator.calculateInitialExecution(startDate);

        RecurringTransfer schedule = new RecurringTransfer(
                currentUser,
                sourceAccount,
                destinationAccount,
                scaledAmount,
                currency,
                request.frequency(),
                startDate,
                endDate,
                initialNextExecution
        );

        RecurringTransfer saved = recurringTransferRepository.save(schedule);

        log.info("Created recurring transfer schedule {} (frequency={}, amount={}, source={}, dest={})",
                MaskingUtils.maskAccountId(saved.getId()),
                saved.getFrequency(),
                saved.getAmount(),
                MaskingUtils.maskAccountId(saved.getSourceAccount().getId()),
                MaskingUtils.maskAccountId(saved.getDestinationAccount().getId()));

        // Audit Trail
        auditEventService.recordEvent(
                currentUser.getId(),
                AuditEventType.RECURRING_TRANSFER_CREATED,
                AuditEntityType.RECURRING_TRANSFER,
                saved.getId(),
                Map.of(
                        "frequency", saved.getFrequency().name(),
                        "amount", saved.getAmount(),
                        "currency", saved.getCurrency(),
                        "startDate", saved.getStartDate().toString(),
                        "endDate", saved.getEndDate() != null ? saved.getEndDate().toString() : "NONE"
                )
        );

        if (ledgerMetrics != null) {
            ledgerMetrics.recordRecurringTransferCreated(saved.getFrequency().name());
        }

        return RecurringTransferResponseDto.from(saved);
    }

    /**
     * Lists schedules owned by the currently authenticated user.
     *
     * @param pageable Pagination request parameters.
     * @param status   Optional status filter.
     * @return Paginated container of RecurringTransferResponseDto.
     */
    @Transactional(readOnly = true)
    public RecurringTransferPageResponseDto listUserSchedules(Pageable pageable, RecurringTransferStatus status) {
        User currentUser = authenticatedUserService.getCurrentUser();
        Page<RecurringTransfer> page;
        if (status != null) {
            page = recurringTransferRepository.findByUserIdAndStatus(currentUser.getId(), status, pageable);
        } else {
            page = recurringTransferRepository.findByUserId(currentUser.getId(), pageable);
        }

        List<UUID> scheduleIds = page.getContent().stream().map(RecurringTransfer::getId).toList();
        Map<UUID, Integer> failureCounts = getFailureCounts(scheduleIds);

        return RecurringTransferPageResponseDto.from(page.map(
                schedule -> RecurringTransferResponseDto.from(schedule, failureCounts.getOrDefault(schedule.getId(), 0))
        ));
    }

    /**
     * Retrieves an individual recurring transfer schedule by ID with anti-enumeration protection.
     *
     * @param id The schedule UUID.
     * @return RecurringTransferResponseDto of the schedule.
     */
    @Transactional(readOnly = true)
    public RecurringTransferResponseDto getSchedule(UUID id) {
        if (id == null) {
            throw new IllegalArgumentException("Schedule ID must not be null");
        }
        User currentUser = authenticatedUserService.getCurrentUser();
        RecurringTransfer schedule = recurringTransferRepository.findById(id)
                .orElseThrow(() -> new RecurringTransferNotFoundException("Recurring transfer not found: " + id));

        if (!schedule.getUser().getId().equals(currentUser.getId()) && !authenticatedUserService.isAdmin()) {
            log.warn("Access denied: user {} attempted to view schedule {} owned by another user",
                    MaskingUtils.maskAccountId(currentUser.getId()),
                    MaskingUtils.maskAccountId(id));
            throw new RecurringTransferNotFoundException("Recurring transfer not found: " + id);
        }

        return RecurringTransferResponseDto.from(schedule, getFailureCount(schedule.getId()));
    }

    /**
     * Pauses an active recurring transfer schedule.
     *
     * @param id Schedule UUID.
     * @return Updated RecurringTransferResponseDto.
     */
    @Transactional
    public RecurringTransferResponseDto pauseSchedule(UUID id) {
        if (id == null) {
            throw new IllegalArgumentException("Schedule ID must not be null");
        }
        User currentUser = authenticatedUserService.getCurrentUser();
        RecurringTransfer schedule = recurringTransferRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new RecurringTransferNotFoundException("Recurring transfer not found: " + id));

        validateOwnership(schedule, currentUser);

        if (schedule.getStatus().isTerminal()) {
            throw new RecurringTransferStatusException("Cannot pause terminal recurring transfer with status: " + schedule.getStatus());
        }

        if (schedule.getStatus() == RecurringTransferStatus.PAUSED) {
            return RecurringTransferResponseDto.from(schedule, getFailureCount(schedule.getId()));
        }

        schedule.setStatus(RecurringTransferStatus.PAUSED);
        RecurringTransfer saved = recurringTransferRepository.save(schedule);

        log.info("Paused recurring transfer schedule {}", MaskingUtils.maskAccountId(saved.getId()));

        auditEventService.recordEvent(
                currentUser.getId(),
                AuditEventType.RECURRING_TRANSFER_PAUSED,
                AuditEntityType.RECURRING_TRANSFER,
                saved.getId(),
                Map.of("status", RecurringTransferStatus.PAUSED.name())
        );

        return RecurringTransferResponseDto.from(saved, getFailureCount(saved.getId()));
    }

    /**
     * Resumes a paused recurring transfer schedule.
     *
     * @param id Schedule UUID.
     * @return Updated RecurringTransferResponseDto.
     */
    @Transactional
    public RecurringTransferResponseDto resumeSchedule(UUID id) {
        if (id == null) {
            throw new IllegalArgumentException("Schedule ID must not be null");
        }
        User currentUser = authenticatedUserService.getCurrentUser();
        RecurringTransfer schedule = recurringTransferRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new RecurringTransferNotFoundException("Recurring transfer not found: " + id));

        validateOwnership(schedule, currentUser);

        if (schedule.getStatus().isTerminal()) {
            throw new RecurringTransferStatusException("Cannot resume terminal recurring transfer with status: " + schedule.getStatus());
        }

        if (schedule.getStatus() == RecurringTransferStatus.ACTIVE) {
            return RecurringTransferResponseDto.from(schedule, getFailureCount(schedule.getId()));
        }

        schedule.setStatus(RecurringTransferStatus.ACTIVE);
        RecurringTransfer saved = recurringTransferRepository.save(schedule);

        log.info("Resumed recurring transfer schedule {}", MaskingUtils.maskAccountId(saved.getId()));

        auditEventService.recordEvent(
                currentUser.getId(),
                AuditEventType.RECURRING_TRANSFER_RESUMED,
                AuditEntityType.RECURRING_TRANSFER,
                saved.getId(),
                Map.of("status", RecurringTransferStatus.ACTIVE.name())
        );

        return RecurringTransferResponseDto.from(saved, getFailureCount(saved.getId()));
    }

    /**
     * Cancels a recurring transfer schedule. Cancellation is a permanent, terminal state transition.
     *
     * @param id Schedule UUID.
     * @return Updated RecurringTransferResponseDto.
     */
    @Transactional
    public RecurringTransferResponseDto cancelSchedule(UUID id) {
        if (id == null) {
            throw new IllegalArgumentException("Schedule ID must not be null");
        }
        User currentUser = authenticatedUserService.getCurrentUser();
        RecurringTransfer schedule = recurringTransferRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new RecurringTransferNotFoundException("Recurring transfer not found: " + id));

        validateOwnership(schedule, currentUser);

        if (schedule.getStatus() == RecurringTransferStatus.COMPLETED) {
            throw new RecurringTransferStatusException("Cannot cancel COMPLETED recurring transfer");
        }

        if (schedule.getStatus() == RecurringTransferStatus.CANCELLED) {
            return RecurringTransferResponseDto.from(schedule, getFailureCount(schedule.getId()));
        }

        schedule.setStatus(RecurringTransferStatus.CANCELLED);
        RecurringTransfer saved = recurringTransferRepository.save(schedule);

        log.info("Cancelled recurring transfer schedule {}", MaskingUtils.maskAccountId(saved.getId()));

        auditEventService.recordEvent(
                currentUser.getId(),
                AuditEventType.RECURRING_TRANSFER_CANCELLED,
                AuditEntityType.RECURRING_TRANSFER,
                saved.getId(),
                Map.of("status", RecurringTransferStatus.CANCELLED.name())
        );

        if (ledgerMetrics != null) {
            ledgerMetrics.recordRecurringTransferCancelled(saved.getFrequency().name());
        }

        return RecurringTransferResponseDto.from(saved, getFailureCount(saved.getId()));
    }

    /**
     * Retrieves paginated execution history for a schedule owned by the authenticated user.
     *
     * @param scheduleId The schedule UUID.
     * @param pageable   Pagination parameters.
     * @return Paginated container of RecurringExecutionResponseDto.
     */
    @Transactional(readOnly = true)
    public RecurringExecutionPageResponseDto listExecutions(UUID scheduleId, Pageable pageable) {
        if (scheduleId == null) {
            throw new IllegalArgumentException("Schedule ID must not be null");
        }
        User currentUser = authenticatedUserService.getCurrentUser();
        RecurringTransfer schedule = recurringTransferRepository.findById(scheduleId)
                .orElseThrow(() -> new RecurringTransferNotFoundException("Recurring transfer not found: " + scheduleId));

        if (!schedule.getUser().getId().equals(currentUser.getId()) && !authenticatedUserService.isAdmin()) {
            throw new RecurringTransferNotFoundException("Recurring transfer not found: " + scheduleId);
        }

        Page<RecurringTransferExecution> executions = executionRepository
                .findByRecurringTransferIdOrderByScheduledForDesc(scheduleId, pageable);

        return RecurringExecutionPageResponseDto.from(executions.map(RecurringExecutionResponseDto::from));
    }

    private void validateOwnership(RecurringTransfer schedule, User currentUser) {
        if (!schedule.getUser().getId().equals(currentUser.getId()) && !authenticatedUserService.isAdmin()) {
            log.warn("Access denied: user {} attempted to mutate schedule {} owned by another user",
                    MaskingUtils.maskAccountId(currentUser.getId()),
                    MaskingUtils.maskAccountId(schedule.getId()));
            throw new RecurringTransferNotFoundException("Recurring transfer not found: " + schedule.getId());
        }
    }

    private int getFailureCount(UUID scheduleId) {
        return (int) executionRepository.countByRecurringTransferIdAndStatus(scheduleId, RecurringExecutionStatus.FAILED);
    }

    private Map<UUID, Integer> getFailureCounts(Collection<UUID> scheduleIds) {
        if (scheduleIds == null || scheduleIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Object[]> rows = executionRepository.countByRecurringTransferIdInAndStatus(scheduleIds, RecurringExecutionStatus.FAILED);
        Map<UUID, Integer> counts = new HashMap<>();
        for (Object[] row : rows) {
            counts.put((UUID) row[0], ((Number) row[1]).intValue());
        }
        return counts;
    }
}
