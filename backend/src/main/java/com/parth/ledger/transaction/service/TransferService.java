package com.parth.ledger.transaction.service;

import com.parth.ledger.account.Account;
import com.parth.ledger.account.AccountClosedException;
import com.parth.ledger.account.AccountFrozenException;
import com.parth.ledger.account.AccountNotFoundException;
import com.parth.ledger.account.AccountRepository;
import com.parth.ledger.account.AccountStatus;
import com.parth.ledger.account.AccountStatusException;
import com.parth.ledger.account.AccountType;
import com.parth.ledger.account.InvalidAccountTypeException;
import com.parth.ledger.ledger.LedgerEntry;
import com.parth.ledger.ledger.LedgerEntryRepository;
import com.parth.ledger.ledger.LedgerEntryType;
import com.parth.ledger.transaction.Transaction;
import com.parth.ledger.transaction.TransactionRepository;
import com.parth.ledger.transaction.TransactionStatus;
import com.parth.ledger.transaction.TransactionType;
import com.parth.ledger.transaction.dto.TransferRequestDto;
import com.parth.ledger.transaction.dto.TransferResponseDto;
import com.parth.ledger.transaction.exception.CurrencyMismatchException;
import com.parth.ledger.transaction.exception.IdempotencyConflictException;
import com.parth.ledger.transaction.exception.InsufficientBalanceException;
import com.parth.ledger.transaction.exception.InvalidAmountException;
import com.parth.ledger.transaction.exception.SameAccountTransferException;
import com.parth.ledger.transaction.exception.UnbalancedLedgerException;
import com.parth.ledger.idempotency.IdempotencyCacheService;
import com.parth.ledger.security.AccountOwnershipException;
import com.parth.ledger.security.AuthenticatedUserService;
import com.parth.ledger.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Service orchestrating atomic, concurrency-safe, double-entry financial transfers.
 *
 * All operations within executeTransfer occur inside a single authoritative PostgreSQL transaction.
 * Concurrency is managed via pessimistic row-level locking (PESSIMISTIC_WRITE / SELECT FOR UPDATE)
 * using deterministic lock ordering by Account UUID to prevent two-account circular-wait deadlocks.
 */
@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final IdempotencyCacheService idempotencyCacheService;
    private final AuthenticatedUserService authenticatedUserService;
    private final com.parth.ledger.audit.AuditEventService auditEventService;
    private final com.parth.ledger.policy.PolicyService policyService;
    private final com.parth.ledger.observability.metrics.LedgerMetrics ledgerMetrics;

    @org.springframework.beans.factory.annotation.Autowired
    public TransferService(AccountRepository accountRepository,
                           TransactionRepository transactionRepository,
                           LedgerEntryRepository ledgerEntryRepository,
                           IdempotencyCacheService idempotencyCacheService,
                           AuthenticatedUserService authenticatedUserService,
                           com.parth.ledger.audit.AuditEventService auditEventService,
                           com.parth.ledger.policy.PolicyService policyService,
                           @org.springframework.beans.factory.annotation.Autowired(required = false) com.parth.ledger.observability.metrics.LedgerMetrics ledgerMetrics) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.idempotencyCacheService = idempotencyCacheService;
        this.authenticatedUserService = authenticatedUserService;
        this.auditEventService = auditEventService;
        this.policyService = policyService;
        this.ledgerMetrics = ledgerMetrics;
    }

    public TransferService(AccountRepository accountRepository,
                           TransactionRepository transactionRepository,
                           LedgerEntryRepository ledgerEntryRepository,
                           IdempotencyCacheService idempotencyCacheService,
                           AuthenticatedUserService authenticatedUserService,
                           com.parth.ledger.audit.AuditEventService auditEventService,
                           com.parth.ledger.policy.PolicyService policyService) {
        this(accountRepository, transactionRepository, ledgerEntryRepository, idempotencyCacheService, authenticatedUserService, auditEventService, policyService, null);
    }

    /**
     * Executes a financial transfer between two accounts with idempotency protection,
     * deterministic pessimistic row-level locking, and balanced double-entry ledger bookkeeping.
     *
     * @param idempotencyKey Client-supplied unique key from Idempotency-Key header.
     * @param request Transfer request parameters.
     * @return TransferResponseDto containing completed transaction details.
     */
    @Transactional
    public TransferResponseDto executeTransfer(String idempotencyKey, TransferRequestDto request) {
        long startTime = System.currentTimeMillis();

        // 1. Validate request parameters
        if (idempotencyKey == null || idempotencyKey.trim().isEmpty()) {
            throw new IllegalArgumentException("Idempotency key must not be blank");
        }
        String cleanIdempotencyKey = idempotencyKey.trim();

        if (request == null) {
            throw new IllegalArgumentException("Transfer request body must not be null");
        }
        if (request.sourceAccountId() == null) {
            throw new IllegalArgumentException("Source account ID is required");
        }
        if (request.destinationAccountId() == null) {
            throw new IllegalArgumentException("Destination account ID is required");
        }

        // 3. Validate amount > 0
        if (request.amount() == null || request.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidAmountException("Transfer amount must be greater than zero");
        }

        // 4. Validate source and destination are different
        if (request.sourceAccountId().equals(request.destinationAccountId())) {
            throw new SameAccountTransferException(
                    "Source and destination accounts must be different: " + request.sourceAccountId()
            );
        }

        if (request.currency() == null || request.currency().trim().isEmpty()) {
            throw new IllegalArgumentException("Currency must not be blank");
        }
        String currency = request.currency().trim().toUpperCase(java.util.Locale.ROOT);
        if (!currency.matches("^[A-Z]{3}$")) {
            throw new IllegalArgumentException("Currency must be a 3-character ISO code: " + currency);
        }
        if (!"INR".equals(currency)) {
            throw new IllegalArgumentException("Only INR currency is supported: " + currency);
        }

        if (ledgerMetrics != null) {
            ledgerMetrics.recordOperation("TRANSFER", "ATTEMPTED");
        }

        try {
            // Standardize scale to 4 decimal places matching PostgreSQL NUMERIC(19,4)
            BigDecimal scaledAmount = request.amount().setScale(4, RoundingMode.HALF_UP);
            String cleanDescription = (request.description() != null && !request.description().isBlank()) ? request.description().trim() : null;

            // 5. Resolve authenticated application user from SecurityContext
            User authenticatedUser = authenticatedUserService.getCurrentUser();

            // Fast-Path: Check Redis idempotency cache before initiating DB transaction / locking
            Optional<TransferResponseDto> cachedResponse = Optional.empty();
            try {
                cachedResponse = idempotencyCacheService.get(cleanIdempotencyKey);
            } catch (Exception e) {
                log.warn("Error accessing Redis idempotency cache: {}. Failing open to PostgreSQL.", e.getMessage());
            }

            if (cachedResponse.isPresent()) {
                TransferResponseDto cached = cachedResponse.get();
                boolean sameSource = cached.sourceAccountId().equals(request.sourceAccountId());
                boolean sameDest = cached.destinationAccountId().equals(request.destinationAccountId());
                boolean sameAmount = cached.amount().compareTo(scaledAmount) == 0;
                boolean sameCurrency = cached.currency().equalsIgnoreCase(currency);
                boolean sameDesc = Objects.equals(cached.description(), cleanDescription);

                if (sameSource && sameDest && sameAmount && sameCurrency && sameDesc) {
                    // Verify source account ownership on Redis fast-path hit
                    if (!accountRepository.existsByIdAndUserId(request.sourceAccountId(), authenticatedUser.getId())) {
                        log.warn("Unauthorized attempt to access cached transfer by user {}",
                                com.parth.ledger.observability.logging.MaskingUtils.maskAccountId(authenticatedUser.getId()));
                        throw new AccountOwnershipException("Authenticated user does not own source account");
                    }
                    if (ledgerMetrics != null) {
                        ledgerMetrics.recordIdempotencyOutcome("TRANSFER", "REPLAY");
                        ledgerMetrics.recordIdempotencyDuration("TRANSFER", "REPLAY", System.currentTimeMillis() - startTime);
                    }
                    log.info("Redis idempotency fast-path hit. Returning cached transaction {}",
                            com.parth.ledger.observability.logging.MaskingUtils.maskAccountId(cached.transactionId()));
                    return cached;
                } else {
                    log.warn("Idempotency conflict detected in Redis cache for transfer.");
                    throw new IdempotencyConflictException(
                            "Idempotency key '" + cleanIdempotencyKey + "' was already used for a transfer with different parameters"
                    );
                }
            }

            // 6. Pre-lock Idempotency Check: Fast return for committed retries or conflict detection
            Optional<Transaction> existingTx = transactionRepository.findByIdempotencyKey(cleanIdempotencyKey);
            if (existingTx.isPresent()) {
                return handleExistingTransaction(existingTx.get(), request, scaledAmount, currency, cleanDescription, cleanIdempotencyKey, authenticatedUser, startTime);
            }

            // 8. Deterministic Lock Ordering
            UUID sourceId = request.sourceAccountId();
            UUID destinationId = request.destinationAccountId();

            UUID firstLockId;
            UUID secondLockId;
            if (sourceId.compareTo(destinationId) < 0) {
                firstLockId = sourceId;
                secondLockId = destinationId;
            } else {
                firstLockId = destinationId;
                secondLockId = sourceId;
            }

            Account firstAccount = accountRepository.findByIdForUpdate(firstLockId)
                    .orElseThrow(() -> new AccountNotFoundException("Account not found: " + firstLockId));
            Account secondAccount = accountRepository.findByIdForUpdate(secondLockId)
                    .orElseThrow(() -> new AccountNotFoundException("Account not found: " + secondLockId));

            Account sourceAccount = sourceId.equals(firstAccount.getId()) ? firstAccount : secondAccount;
            Account destinationAccount = destinationId.equals(secondAccount.getId()) ? secondAccount : firstAccount;

            // Post-lock Idempotency Re-check
            Optional<Transaction> txAfterLock = transactionRepository.findByIdempotencyKey(cleanIdempotencyKey);
            if (txAfterLock.isPresent()) {
                return handleExistingTransaction(txAfterLock.get(), request, scaledAmount, currency, cleanDescription, cleanIdempotencyKey, authenticatedUser, startTime);
            }

            // Account Type Validation
            if (sourceAccount.getAccountType() != AccountType.USER_CHECKING) {
                log.warn("Transfer rejected: source account {} has ineligible type {}",
                        com.parth.ledger.observability.logging.MaskingUtils.maskAccountId(sourceId), sourceAccount.getAccountType());
                throw new InvalidAccountTypeException("Source account must be a USER_CHECKING account: " + sourceId);
            }
            if (destinationAccount.getAccountType() != AccountType.USER_CHECKING) {
                log.warn("Transfer rejected: destination account {} has ineligible type {}",
                        com.parth.ledger.observability.logging.MaskingUtils.maskAccountId(destinationId), destinationAccount.getAccountType());
                throw new InvalidAccountTypeException("Destination account must be a USER_CHECKING account: " + destinationId);
            }

            // 9. Source Account Ownership Authorization
            if (sourceAccount.getUser() == null || !sourceAccount.getUser().getId().equals(authenticatedUser.getId())) {
                log.warn("Unauthorized transfer: user {} does not own source account {}",
                        com.parth.ledger.observability.logging.MaskingUtils.maskAccountId(authenticatedUser.getId()),
                        com.parth.ledger.observability.logging.MaskingUtils.maskAccountId(sourceId));
                throw new AccountOwnershipException("Authenticated user does not own source account");
            }

            // Account Status Validation
            if (sourceAccount.getStatus() == AccountStatus.FROZEN) {
                log.warn("Transfer rejected: source account {} is FROZEN", com.parth.ledger.observability.logging.MaskingUtils.maskAccountId(sourceId));
                throw new AccountFrozenException("Source account " + sourceId + " is FROZEN");
            }
            if (sourceAccount.getStatus() == AccountStatus.CLOSED) {
                log.warn("Transfer rejected: source account {} is CLOSED", com.parth.ledger.observability.logging.MaskingUtils.maskAccountId(sourceId));
                throw new AccountClosedException("Source account " + sourceId + " is CLOSED");
            }
            if (sourceAccount.getStatus() != AccountStatus.ACTIVE) {
                log.warn("Transfer rejected: source account {} is in status {}", com.parth.ledger.observability.logging.MaskingUtils.maskAccountId(sourceId), sourceAccount.getStatus());
                throw new AccountStatusException("Source account " + sourceId + " is not ACTIVE");
            }

            if (destinationAccount.getStatus() == AccountStatus.FROZEN) {
                log.warn("Transfer rejected: destination account {} is FROZEN", com.parth.ledger.observability.logging.MaskingUtils.maskAccountId(destinationId));
                throw new AccountFrozenException("Destination account " + destinationId + " is FROZEN");
            }
            if (destinationAccount.getStatus() == AccountStatus.CLOSED) {
                log.warn("Transfer rejected: destination account {} is CLOSED", com.parth.ledger.observability.logging.MaskingUtils.maskAccountId(destinationId));
                throw new AccountClosedException("Destination account " + destinationId + " is CLOSED");
            }
            if (destinationAccount.getStatus() != AccountStatus.ACTIVE) {
                log.warn("Transfer rejected: destination account {} is in status {}", com.parth.ledger.observability.logging.MaskingUtils.maskAccountId(destinationId), destinationAccount.getStatus());
                throw new AccountStatusException("Destination account " + destinationId + " is not ACTIVE");
            }

            // 2 & 5. Validate currency compatibility
            if (!sourceAccount.getCurrency().equalsIgnoreCase(currency)) {
                throw new CurrencyMismatchException(
                        "Currency mismatch: transfer currency '" + currency + "' does not match source account currency '" + sourceAccount.getCurrency() + "'"
                );
            }
            if (!destinationAccount.getCurrency().equalsIgnoreCase(currency)) {
                throw new CurrencyMismatchException(
                        "Currency mismatch: transfer currency '" + currency + "' does not match destination account currency '" + destinationAccount.getCurrency() + "'"
                );
            }

            // 9. Check source balance
            if (sourceAccount.getBalance().compareTo(scaledAmount) < 0) {
                throw new InsufficientBalanceException(
                        "Insufficient balance in source account " + sourceId + ": available "
                                + sourceAccount.getBalance() + ", required " + scaledAmount
                );
            }

            // 9.5. Policy Engine Evaluation
            try {
                policyService.evaluateAndRecordTransferLimits(sourceAccount, destinationAccount, scaledAmount);
            } catch (com.parth.ledger.policy.PolicyViolationException ex) {
                try {
                    auditEventService.recordPolicyRejectionEventOnce(
                            authenticatedUser != null ? authenticatedUser.getId() : null,
                            com.parth.ledger.audit.AuditEventType.TRANSFER_REJECTED_POLICY,
                            com.parth.ledger.audit.AuditEntityType.ACCOUNT,
                            sourceId,
                            cleanIdempotencyKey,
                            "TRANSFER",
                            java.util.Map.of(
                                    "policyType", ex.getErrorCode().name(),
                                    "reason", ex.getMessage(),
                                    "amount", scaledAmount,
                                    "currency", currency,
                                    "sourceAccountId", sourceId,
                                    "destinationAccountId", destinationId
                            )
                    );
                } catch (Exception auditErr) {
                    log.warn("Failed to record TRANSFER_REJECTED_POLICY audit event: {}", auditErr.getMessage());
                }
                throw ex;
            }

            // 10. Debit source account & 11. Credit destination account
            sourceAccount.setBalance(sourceAccount.getBalance().subtract(scaledAmount));
            destinationAccount.setBalance(destinationAccount.getBalance().add(scaledAmount));

            accountRepository.save(sourceAccount);
            accountRepository.save(destinationAccount);

            // 12. Create transaction record (PENDING)
            Transaction transaction = new Transaction(
                    cleanIdempotencyKey,
                    scaledAmount,
                    currency,
                    TransactionStatus.PENDING,
                    sourceAccount,
                    destinationAccount,
                    TransactionType.TRANSFER,
                    authenticatedUser,
                    cleanDescription
            );
            transaction = transactionRepository.save(transaction);

            // 13. Create DEBIT ledger entry & 14. Create CREDIT ledger entry
            LedgerEntry debitEntry = new LedgerEntry(
                    transaction,
                    sourceAccount,
                    LedgerEntryType.DEBIT,
                    scaledAmount,
                    currency
            );
            LedgerEntry creditEntry = new LedgerEntry(
                    transaction,
                    destinationAccount,
                    LedgerEntryType.CREDIT,
                    scaledAmount,
                    currency
            );

            ledgerEntryRepository.save(debitEntry);
            ledgerEntryRepository.save(creditEntry);

            // 15. Verify double-entry ledger entries balance (totalDebits == totalCredits)
            BigDecimal totalDebits = debitEntry.getAmount();
            BigDecimal totalCredits = creditEntry.getAmount();
            if (totalDebits.compareTo(totalCredits) != 0) {
                throw new UnbalancedLedgerException(
                        "Double-entry ledger invariant violation: total debits (" + totalDebits
                                + ") do not equal total credits (" + totalCredits + ")"
                );
            }

            // 16. Complete transaction
            transaction.setStatus(TransactionStatus.COMPLETED);
            transaction.setCompletedAt(Instant.now());
            transaction = transactionRepository.save(transaction);

            // 17. Record TRANSFER_COMPLETED operational audit event atomically within PostgreSQL transaction
            auditEventService.recordEvent(
                    authenticatedUser != null ? authenticatedUser.getId() : null,
                    com.parth.ledger.audit.AuditEventType.TRANSFER_COMPLETED,
                    com.parth.ledger.audit.AuditEntityType.TRANSACTION,
                    transaction.getId(),
                    java.util.Map.of(
                            "amount", scaledAmount,
                            "currency", currency,
                            "sourceAccountId", sourceId,
                            "destinationAccountId", destinationId
                    )
            );

            log.info("Successfully executed transfer: txId={}, amount={} {}, from={} to={}",
                    com.parth.ledger.observability.logging.MaskingUtils.maskAccountId(transaction.getId()), scaledAmount, currency,
                    com.parth.ledger.observability.logging.MaskingUtils.maskAccountId(sourceId),
                    com.parth.ledger.observability.logging.MaskingUtils.maskAccountId(destinationId));

            TransferResponseDto response = TransferResponseDto.from(transaction);

            // Store successful response in Redis and record COMPLETED metrics ONLY after the PostgreSQL transaction commits
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            idempotencyCacheService.set(cleanIdempotencyKey, response);
                        } catch (Exception e) {
                            log.warn("Failed to cache response in Redis after commit: {}", e.getMessage());
                        }
                        if (ledgerMetrics != null) {
                            ledgerMetrics.recordOperation("TRANSFER", "COMPLETED");
                            ledgerMetrics.recordIdempotencyOutcome("TRANSFER", "FIRST_EXECUTION");
                            ledgerMetrics.recordOperationDuration("TRANSFER", "COMPLETED", System.currentTimeMillis() - startTime);
                        }
                    }
                });
            } else {
                try {
                    idempotencyCacheService.set(cleanIdempotencyKey, response);
                } catch (Exception e) {
                    log.warn("Failed to cache response in Redis: {}", e.getMessage());
                }
                if (ledgerMetrics != null) {
                    ledgerMetrics.recordOperation("TRANSFER", "COMPLETED");
                    ledgerMetrics.recordIdempotencyOutcome("TRANSFER", "FIRST_EXECUTION");
                    ledgerMetrics.recordOperationDuration("TRANSFER", "COMPLETED", System.currentTimeMillis() - startTime);
                }
            }

            // 18. Return transaction result DTO
            return response;

        } catch (IdempotencyConflictException e) {
            if (ledgerMetrics != null) {
                ledgerMetrics.recordIdempotencyOutcome("TRANSFER", "CONFLICT");
                ledgerMetrics.recordIdempotencyDuration("TRANSFER", "CONFLICT", System.currentTimeMillis() - startTime);
                ledgerMetrics.recordOperation("TRANSFER", "REJECTED");
                ledgerMetrics.recordOperationDuration("TRANSFER", "REJECTED", System.currentTimeMillis() - startTime);
            }
            throw e;
        } catch (AccountNotFoundException | AccountOwnershipException | AccountStatusException |
                 CurrencyMismatchException | InsufficientBalanceException | InvalidAccountTypeException |
                 InvalidAmountException | SameAccountTransferException | com.parth.ledger.policy.PolicyViolationException e) {
            if (ledgerMetrics != null) {
                ledgerMetrics.recordOperation("TRANSFER", "REJECTED");
                ledgerMetrics.recordOperationDuration("TRANSFER", "REJECTED", System.currentTimeMillis() - startTime);
            }
            throw e;
        } catch (RuntimeException | Error e) {
            if (ledgerMetrics != null) {
                ledgerMetrics.recordOperation("TRANSFER", "FAILED");
                ledgerMetrics.recordOperationDuration("TRANSFER", "FAILED", System.currentTimeMillis() - startTime);
            }
            throw e;
        }
    }

    private TransferResponseDto handleExistingTransaction(
            Transaction existing,
            TransferRequestDto request,
            BigDecimal scaledAmount,
            String currency,
            String cleanDescription,
            String idempotencyKey,
            User authenticatedUser,
            long startTime) {

        boolean sameSource = existing.getSourceAccount().getId().equals(request.sourceAccountId());
        boolean sameDest = existing.getDestinationAccount().getId().equals(request.destinationAccountId());
        boolean sameAmount = existing.getAmount().compareTo(scaledAmount) == 0;
        boolean sameCurrency = existing.getCurrency().equalsIgnoreCase(currency);
        boolean sameDesc = Objects.equals(existing.getDescription(), cleanDescription);

        if (sameSource && sameDest && sameAmount && sameCurrency && sameDesc) {
            // Verify source account ownership on database idempotency retry
            if (existing.getSourceAccount().getUser() == null || !existing.getSourceAccount().getUser().getId().equals(authenticatedUser.getId())) {
                log.warn("Unauthorized attempt to access existing transaction for user {}",
                        com.parth.ledger.observability.logging.MaskingUtils.maskAccountId(authenticatedUser.getId()));
                throw new AccountOwnershipException("Authenticated user does not own source account");
            }

            if (ledgerMetrics != null) {
                ledgerMetrics.recordIdempotencyOutcome("TRANSFER", "REPLAY");
                ledgerMetrics.recordIdempotencyDuration("TRANSFER", "REPLAY", System.currentTimeMillis() - startTime);
            }

            log.info("Idempotent retry detected for transfer. Returning existing transaction {}",
                    com.parth.ledger.observability.logging.MaskingUtils.maskAccountId(existing.getId()));
            TransferResponseDto response = TransferResponseDto.from(existing);
            try {
                idempotencyCacheService.set(idempotencyKey, response);
            } catch (Exception e) {
                log.warn("Failed to repopulate Redis cache: {}", e.getMessage());
            }
            return response;
        } else {
            log.warn("Idempotency conflict for transfer.");
            throw new IdempotencyConflictException(
                    "Idempotency key '" + idempotencyKey + "' was already used for a transfer with different parameters"
            );
        }
    }
}
