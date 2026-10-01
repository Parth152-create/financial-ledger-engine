package com.parth.ledger.transaction.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import com.parth.ledger.account.Account;
import com.parth.ledger.account.AccountClosedException;
import com.parth.ledger.account.AccountFrozenException;
import com.parth.ledger.account.AccountNotFoundException;
import com.parth.ledger.account.AccountRepository;
import com.parth.ledger.account.AccountStatus;
import com.parth.ledger.account.AccountStatusException;
import com.parth.ledger.audit.AuditEntityType;
import com.parth.ledger.audit.AuditEventService;
import com.parth.ledger.audit.AuditEventType;
import com.parth.ledger.idempotency.IdempotencyCacheService;
import com.parth.ledger.ledger.LedgerEntry;
import com.parth.ledger.ledger.LedgerEntryRepository;
import com.parth.ledger.ledger.LedgerEntryType;
import com.parth.ledger.security.AccountOwnershipException;
import com.parth.ledger.security.AuthenticatedUserService;
import com.parth.ledger.transaction.Transaction;
import com.parth.ledger.transaction.TransactionRepository;
import com.parth.ledger.transaction.TransactionStatus;
import com.parth.ledger.transaction.TransactionType;
import com.parth.ledger.transaction.dto.ReversalRequestDto;
import com.parth.ledger.transaction.dto.ReversalResponseDto;
import com.parth.ledger.transaction.dto.TransactionResponseDto;
import com.parth.ledger.transaction.exception.CurrencyMismatchException;
import com.parth.ledger.transaction.exception.IdempotencyConflictException;
import com.parth.ledger.transaction.exception.InsufficientBalanceException;
import com.parth.ledger.transaction.exception.TransactionAlreadyReversedException;
import com.parth.ledger.transaction.exception.TransactionNotFoundException;
import com.parth.ledger.transaction.exception.TransactionNotReversibleException;
import com.parth.ledger.transaction.exception.UnbalancedLedgerException;
import com.parth.ledger.observability.logging.MaskingUtils;
import com.parth.ledger.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Service orchestrating atomic, concurrency-safe, double-entry financial transaction reversals.
 *
 * Core Principles:
 * 1. Historical financial records (original transactions and ledger entries) are IMMUTABLE and never mutated.
 * 2. A reversal is a NEW compensating double-entry financial transaction with its own unique transaction ID,
 *    its own balanced ledger entries, and an explicit reference to the original transaction.
 * 3. Database-enforced uniqueness: at most one reversal transaction per original transaction.
 * 4. Deterministic account row locking (min UUID, then max UUID) prevents deadlocks with normal transfers.
 * 5. Atomicity: Account balance mutations, reversal transaction creation, compensating ledger entries,
 *    and operational audit event (TRANSACTION_REVERSED) participate in the SAME PostgreSQL transaction.
 * 6. Authorization: A normal user cannot cause an unauthorized debit from another user's account.
 *    Reversal authorization is strictly aligned with the account debited by the compensating transaction.
 * 7. Anti-enumeration: Unrelated callers receive 404 Not Found before evaluating lifecycle state.
 */
@Service
public class TransactionReversalService {

    private static final Logger log = LoggerFactory.getLogger(TransactionReversalService.class);

    @PersistenceContext
    private EntityManager entityManager;

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final IdempotencyCacheService idempotencyCacheService;
    private final AuthenticatedUserService authenticatedUserService;
    private final AuditEventService auditEventService;
    private final com.parth.ledger.outbox.OutboxService outboxService;
    private final com.parth.ledger.observability.metrics.LedgerMetrics ledgerMetrics;

    @org.springframework.beans.factory.annotation.Autowired
    public TransactionReversalService(TransactionRepository transactionRepository,
                                      AccountRepository accountRepository,
                                      LedgerEntryRepository ledgerEntryRepository,
                                      IdempotencyCacheService idempotencyCacheService,
                                      AuthenticatedUserService authenticatedUserService,
                                      AuditEventService auditEventService,
                                      @org.springframework.beans.factory.annotation.Autowired(required = false) com.parth.ledger.outbox.OutboxService outboxService,
                                      @org.springframework.beans.factory.annotation.Autowired(required = false) com.parth.ledger.observability.metrics.LedgerMetrics ledgerMetrics) {
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.idempotencyCacheService = idempotencyCacheService;
        this.authenticatedUserService = authenticatedUserService;
        this.auditEventService = auditEventService;
        this.outboxService = outboxService;
        this.ledgerMetrics = ledgerMetrics;
    }

    public TransactionReversalService(TransactionRepository transactionRepository,
                                      AccountRepository accountRepository,
                                      LedgerEntryRepository ledgerEntryRepository,
                                      IdempotencyCacheService idempotencyCacheService,
                                      AuthenticatedUserService authenticatedUserService,
                                      AuditEventService auditEventService,
                                      com.parth.ledger.observability.metrics.LedgerMetrics ledgerMetrics) {
        this(transactionRepository, accountRepository, ledgerEntryRepository, idempotencyCacheService, authenticatedUserService, auditEventService, null, ledgerMetrics);
    }

    public TransactionReversalService(TransactionRepository transactionRepository,
                                      AccountRepository accountRepository,
                                      LedgerEntryRepository ledgerEntryRepository,
                                      IdempotencyCacheService idempotencyCacheService,
                                      AuthenticatedUserService authenticatedUserService,
                                      AuditEventService auditEventService) {
        this(transactionRepository, accountRepository, ledgerEntryRepository, idempotencyCacheService, authenticatedUserService, auditEventService, null, null);
    }

    /**
     * Executes a compensating reversal for an existing completed financial transaction.
     *
     * @param originalTransactionId ID of the transaction to reverse.
     * @param idempotencyKey        Unique client-supplied idempotency key from header.
     * @param request               Optional reversal request payload containing reason.
     * @return ReversalResponseDto representing the completed compensating transaction.
     */
    @Transactional
    public ReversalResponseDto executeReversal(UUID originalTransactionId, String idempotencyKey, ReversalRequestDto request) {
        if (originalTransactionId == null) {
            throw new IllegalArgumentException("Original transaction ID is required");
        }
        if (idempotencyKey == null || idempotencyKey.trim().isEmpty()) {
            throw new IllegalArgumentException("Idempotency-Key header must not be blank");
        }
        String cleanIdempotencyKey = idempotencyKey.trim();

        long startTime = System.currentTimeMillis();
        if (ledgerMetrics != null) {
            ledgerMetrics.recordOperation("REVERSAL", "ATTEMPTED");
        }

        try {
            String cleanReason = null;
            if (request != null && request.reason() != null && !request.reason().isBlank()) {
                cleanReason = request.reason().trim();
                if (cleanReason.length() > 255) {
                    throw new IllegalArgumentException("Reversal reason must not exceed 255 characters");
                }
                cleanReason = cleanReason.replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", "");
            }

            User currentUser = authenticatedUserService.getCurrentUser();
            boolean isAdmin = authenticatedUserService.isAdmin();

            // 1. Pessimistic Row-Level Lock on Original Transaction
            // Serializes concurrent reversal attempts on the exact same original transaction
            Transaction originalTx = transactionRepository.findByIdForUpdate(originalTransactionId)
                    .orElseThrow(() -> new TransactionNotFoundException(originalTransactionId));

            // 2. Anti-Enumeration Authorization Check
            // Enforced FIRST before checking transaction lifecycle state or idempotency to prevent probing
            validateAntiEnumeration(originalTx, currentUser, isAdmin);

            // 3. Debit Authority Authorization Check
            // Senders cannot cause unauthorized debits from counterparty accounts.
            // For TRANSFER: Only the destination account owner (who will be debited) or ADMIN can reverse.
            // For DEPOSIT: Only the destination account owner (who will be debited) or ADMIN can reverse.
            // For WITHDRAWAL: Only the source account owner (who will be credited) or ADMIN can reverse.
            validateDebitAuthorization(originalTx, currentUser, isAdmin);

            // 4. Fast-Path: Redis Idempotency Cache Check (post-authorization)
            Optional<ReversalResponseDto> cachedResponse = Optional.empty();
            try {
                cachedResponse = idempotencyCacheService.get(cleanIdempotencyKey, ReversalResponseDto.class);
            } catch (Exception e) {
                log.warn("Error accessing Redis idempotency cache for reversal: {}. Failing open to PostgreSQL.", e.getMessage());
            }

            if (cachedResponse.isPresent()) {
                ReversalResponseDto cached = cachedResponse.get();
                if (cached.transactionType() != TransactionType.REVERSAL
                        || !originalTransactionId.equals(cached.originalTransactionId())) {
                    log.warn("Idempotency conflict in Redis cache for reversal.");
                    throw new IdempotencyConflictException("Idempotency key was already used for a different transaction");
                }

                if (ledgerMetrics != null) {
                    ledgerMetrics.recordIdempotencyOutcome("REVERSAL", "REPLAY");
                    ledgerMetrics.recordIdempotencyDuration("REVERSAL", "REPLAY", System.currentTimeMillis() - startTime);
                }
                log.info("Redis idempotency fast-path hit for reversal. Returning cached reversal {}",
                        MaskingUtils.maskAccountId(cached.reversalTransactionId()));
                return cached;
            }

            // 5. Pre-Lock Database Idempotency Check
            Optional<Transaction> existingTxOpt = transactionRepository.findByIdempotencyKey(cleanIdempotencyKey);
            if (existingTxOpt.isPresent()) {
                Transaction existingTx = existingTxOpt.get();
                if (existingTx.getTransactionType() == TransactionType.REVERSAL
                        && existingTx.getReversesTransaction() != null
                        && originalTransactionId.equals(existingTx.getReversesTransactionId())) {
                    if (ledgerMetrics != null) {
                        ledgerMetrics.recordIdempotencyOutcome("REVERSAL", "REPLAY");
                        ledgerMetrics.recordIdempotencyDuration("REVERSAL", "REPLAY", System.currentTimeMillis() - startTime);
                    }
                    log.info("Database idempotency hit for reversal. Returning committed reversal {}",
                            MaskingUtils.maskAccountId(existingTx.getId()));
                    return ReversalResponseDto.from(existingTx);
                } else {
                    throw new IdempotencyConflictException("Idempotency key was already used for a different transaction");
                }
            }

            // 6. Validate Reversal Eligibility
            // A. Reject reversal of a reversal
            if (originalTx.getTransactionType() == TransactionType.REVERSAL || originalTx.getReversesTransaction() != null) {
                log.warn("Reversal rejected: transaction {} is already a reversal",
                        MaskingUtils.maskAccountId(originalTransactionId));
                throw new TransactionNotReversibleException("A reversal transaction cannot be reversed: " + originalTransactionId);
            }

            // B. Reject reversal of system funding
            if (originalTx.getTransactionType() == TransactionType.SYSTEM_FUNDING) {
                log.warn("Reversal rejected: system funding transaction {} cannot be reversed",
                        MaskingUtils.maskAccountId(originalTransactionId));
                throw new TransactionNotReversibleException("System funding transactions cannot be reversed: " + originalTransactionId);
            }

            // C. Reject non-completed transactions
            if (originalTx.getStatus() != TransactionStatus.COMPLETED) {
                log.warn("Reversal rejected: transaction {} status is {} (only COMPLETED is eligible)",
                        MaskingUtils.maskAccountId(originalTransactionId), originalTx.getStatus());
                throw new TransactionNotReversibleException("Only COMPLETED transactions can be reversed. Current status: " + originalTx.getStatus());
            }

            // D. Validate supported transaction type
            if (originalTx.getTransactionType() != TransactionType.TRANSFER
                    && originalTx.getTransactionType() != TransactionType.DEPOSIT
                    && originalTx.getTransactionType() != TransactionType.WITHDRAWAL) {
                log.warn("Reversal rejected: unsupported transaction type {} for tx {}",
                        originalTx.getTransactionType(), originalTransactionId);
                throw new TransactionNotReversibleException("Unsupported transaction type for reversal: " + originalTx.getTransactionType());
            }

            // E. Check if original transaction has already been reversed
            Optional<Transaction> existingReversalOpt = transactionRepository.findByReversesTransactionId(originalTransactionId);
            if (existingReversalOpt.isPresent()) {
                Transaction existingReversal = existingReversalOpt.get();
                if (existingReversal.getIdempotencyKey().equals(cleanIdempotencyKey)) {
                    if (ledgerMetrics != null) {
                        ledgerMetrics.recordIdempotencyOutcome("REVERSAL", "REPLAY");
                        ledgerMetrics.recordOperationDuration("REVERSAL", "COMPLETED", System.currentTimeMillis() - startTime);
                    }
                    log.info("Idempotent replay under lock: transaction {} already reversed by reversal {}",
                            originalTransactionId, existingReversal.getId());
                    return ReversalResponseDto.from(existingReversal);
                }
                log.warn("Reversal rejected: transaction {} has already been reversed by {}",
                        originalTransactionId, existingReversal.getId());
                throw new TransactionAlreadyReversedException(originalTransactionId);
            }

            // 7. Symmetrical Account Inversion & Deterministic Row Locking
            UUID reversalDebitAccountId = originalTx.getDestinationAccount().getId();
            UUID reversalCreditAccountId = originalTx.getSourceAccount().getId();

            UUID firstLockId = reversalDebitAccountId.compareTo(reversalCreditAccountId) < 0
                    ? reversalDebitAccountId
                    : reversalCreditAccountId;
            UUID secondLockId = reversalDebitAccountId.compareTo(reversalCreditAccountId) < 0
                    ? reversalCreditAccountId
                    : reversalDebitAccountId;

            if (entityManager != null) {
                entityManager.detach(originalTx.getSourceAccount());
                entityManager.detach(originalTx.getDestinationAccount());
            }

            Account firstAccount = accountRepository.findByIdForUpdate(firstLockId)
                    .orElseThrow(() -> new AccountNotFoundException("Account not found: " + firstLockId));
            Account secondAccount = accountRepository.findByIdForUpdate(secondLockId)
                    .orElseThrow(() -> new AccountNotFoundException("Account not found: " + secondLockId));

            Account reversalDebitAccount = reversalDebitAccountId.equals(firstAccount.getId()) ? firstAccount : secondAccount;
            Account reversalCreditAccount = reversalCreditAccountId.equals(secondAccount.getId()) ? secondAccount : firstAccount;

            // Post-lock idempotency re-check: verify key, type, and original transaction relationship
            Optional<Transaction> txAfterLock = transactionRepository.findByIdempotencyKey(cleanIdempotencyKey);
            if (txAfterLock.isPresent()) {
                Transaction tx = txAfterLock.get();
                if (tx.getTransactionType() == TransactionType.REVERSAL
                        && tx.getReversesTransaction() != null
                        && originalTransactionId.equals(tx.getReversesTransactionId())) {
                    if (ledgerMetrics != null) {
                        ledgerMetrics.recordIdempotencyOutcome("REVERSAL", "REPLAY");
                        ledgerMetrics.recordIdempotencyDuration("REVERSAL", "REPLAY", System.currentTimeMillis() - startTime);
                    }
                    return ReversalResponseDto.from(tx);
                } else {
                    throw new IdempotencyConflictException("Idempotency key was already used for a different transaction");
                }
            }

            // 8. Validate Account States & Balance Safety
            validateAccountStatus(reversalDebitAccount, "debited");
            validateAccountStatus(reversalCreditAccount, "credited");

            BigDecimal amount = originalTx.getAmount();
            String currency = originalTx.getCurrency();

            if (!reversalDebitAccount.getCurrency().equalsIgnoreCase(currency)) {
                throw new CurrencyMismatchException("Currency mismatch: reversal currency '" + currency
                        + "' does not match debited account currency '" + reversalDebitAccount.getCurrency() + "'");
            }
            if (!reversalCreditAccount.getCurrency().equalsIgnoreCase(currency)) {
                throw new CurrencyMismatchException("Currency mismatch: reversal currency '" + currency
                        + "' does not match credited account currency '" + reversalCreditAccount.getCurrency() + "'");
            }

            // Non-negative balance invariant: Debited account must have sufficient balance to reverse
            if (reversalDebitAccount.getBalance().compareTo(amount) < 0) {
                log.warn("Reversal rejected: insufficient balance in account {}: available {}, required {}",
                        com.parth.ledger.observability.logging.MaskingUtils.maskAccountId(reversalDebitAccount.getId()),
                        reversalDebitAccount.getBalance(), amount);
                throw new InsufficientBalanceException("Insufficient balance in account " + reversalDebitAccount.getId()
                        + " to execute reversal: available " + reversalDebitAccount.getBalance() + ", required " + amount);
            }

            // 9. Execute Financial Balance Updates
            reversalDebitAccount.setBalance(reversalDebitAccount.getBalance().subtract(amount));
            reversalCreditAccount.setBalance(reversalCreditAccount.getBalance().add(amount));

            accountRepository.save(reversalDebitAccount);
            accountRepository.save(reversalCreditAccount);

            // 10. Create Reversal Transaction Record (PENDING)
            String description = (cleanReason != null && !cleanReason.isBlank())
                    ? cleanReason
                    : "Reversal of transaction " + originalTransactionId;

            Transaction reversalTx = new Transaction(
                    cleanIdempotencyKey,
                    amount,
                    currency,
                    TransactionStatus.PENDING,
                    reversalDebitAccount,
                    reversalCreditAccount,
                    TransactionType.REVERSAL,
                    currentUser,
                    description,
                    originalTx
            );
            reversalTx = transactionRepository.save(reversalTx);

            // 11. Create Symmetrical Double-Entry Ledger Entries
            LedgerEntry debitEntry = new LedgerEntry(
                    reversalTx,
                    reversalDebitAccount,
                    LedgerEntryType.DEBIT,
                    amount,
                    currency
            );
            LedgerEntry creditEntry = new LedgerEntry(
                    reversalTx,
                    reversalCreditAccount,
                    LedgerEntryType.CREDIT,
                    amount,
                    currency
            );
            ledgerEntryRepository.save(debitEntry);
            ledgerEntryRepository.save(creditEntry);

            // Verify double-entry balance invariant (totalDebits == totalCredits)
            if (debitEntry.getAmount().compareTo(creditEntry.getAmount()) != 0) {
                throw new UnbalancedLedgerException("Double-entry ledger invariant violation during reversal: total debits ("
                        + debitEntry.getAmount() + ") do not equal total credits (" + creditEntry.getAmount() + ")");
            }

            // 12. Complete Reversal Transaction
            reversalTx.setStatus(TransactionStatus.COMPLETED);
            reversalTx.setCompletedAt(Instant.now());
            reversalTx = transactionRepository.save(reversalTx);

            // 13. Record TRANSACTION_REVERSED Operational Audit Event atomically in PostgreSQL transaction
            auditEventService.recordEvent(
                    currentUser.getId(),
                    AuditEventType.TRANSACTION_REVERSED,
                    AuditEntityType.TRANSACTION,
                    originalTransactionId,
                    Map.of(
                            "reversalTransactionId", reversalTx.getId(),
                            "originalTransactionId", originalTransactionId,
                            "amount", amount,
                            "currency", currency,
                            "originalTransactionType", originalTx.getTransactionType().name(),
                            "sourceAccountId", reversalDebitAccount.getId(),
                            "destinationAccountId", reversalCreditAccount.getId(),
                            "reason", cleanReason != null ? cleanReason : ""
                    )
            );

            // 13.5. Record TRANSACTION_REVERSED transactional outbox event atomically in PostgreSQL transaction
            if (outboxService != null) {
                outboxService.recordEvent(
                        com.parth.ledger.outbox.OutboxAggregateType.TRANSACTION,
                        reversalTx.getId(),
                        com.parth.ledger.outbox.OutboxEventType.TRANSACTION_REVERSED,
                        Map.of(
                                "transactionId", reversalTx.getId(),
                                "reversesTransactionId", originalTransactionId,
                                "originalTransactionType", originalTx.getTransactionType().name(),
                                "sourceAccountId", reversalDebitAccount.getId(),
                                "destinationAccountId", reversalCreditAccount.getId(),
                                "amount", amount,
                                "currency", currency,
                                "occurredAt", reversalTx.getCompletedAt().toString()
                        )
                );
            }

            log.info("Successfully executed transaction reversal: reversalTxId={}, originalTxId={}, amount={} {}, debitedAccount={}, creditedAccount={}",
                    MaskingUtils.maskAccountId(reversalTx.getId()),
                    MaskingUtils.maskAccountId(originalTransactionId),
                    amount, currency,
                    MaskingUtils.maskAccountId(reversalDebitAccount.getId()),
                    MaskingUtils.maskAccountId(reversalCreditAccount.getId()));

            // 14. Register Post-Commit Redis Caching and Metric Synchronization
            ReversalResponseDto response = ReversalResponseDto.from(reversalTx);
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            idempotencyCacheService.set(cleanIdempotencyKey, response);
                        } catch (Exception e) {
                            log.warn("Failed to cache reversal response in Redis after commit: {}", e.getMessage());
                        }
                        if (ledgerMetrics != null) {
                            ledgerMetrics.recordOperation("REVERSAL", "COMPLETED");
                            ledgerMetrics.recordIdempotencyOutcome("REVERSAL", "FIRST_EXECUTION");
                            ledgerMetrics.recordOperationDuration("REVERSAL", "COMPLETED", System.currentTimeMillis() - startTime);
                        }
                    }
                });
            } else {
                try {
                    idempotencyCacheService.set(cleanIdempotencyKey, response);
                } catch (Exception e) {
                    log.warn("Failed to cache reversal response in Redis: {}", e.getMessage());
                }
                if (ledgerMetrics != null) {
                    ledgerMetrics.recordOperation("REVERSAL", "COMPLETED");
                    ledgerMetrics.recordIdempotencyOutcome("REVERSAL", "FIRST_EXECUTION");
                    ledgerMetrics.recordOperationDuration("REVERSAL", "COMPLETED", System.currentTimeMillis() - startTime);
                }
            }

            return response;

        } catch (IdempotencyConflictException e) {
            if (ledgerMetrics != null) {
                ledgerMetrics.recordIdempotencyOutcome("REVERSAL", "CONFLICT");
                ledgerMetrics.recordIdempotencyDuration("REVERSAL", "CONFLICT", System.currentTimeMillis() - startTime);
                ledgerMetrics.recordOperation("REVERSAL", "REJECTED");
                ledgerMetrics.recordOperationDuration("REVERSAL", "REJECTED", System.currentTimeMillis() - startTime);
            }
            throw e;
        } catch (TransactionNotFoundException | TransactionNotReversibleException |
                 TransactionAlreadyReversedException | AccountNotFoundException |
                 AccountStatusException | CurrencyMismatchException |
                 InsufficientBalanceException | AccountOwnershipException | IllegalArgumentException e) {
            if (ledgerMetrics != null) {
                ledgerMetrics.recordOperation("REVERSAL", "REJECTED");
                ledgerMetrics.recordOperationDuration("REVERSAL", "REJECTED", System.currentTimeMillis() - startTime);
            }
            throw e;
        } catch (RuntimeException | Error e) {
            if (ledgerMetrics != null) {
                ledgerMetrics.recordOperation("REVERSAL", "FAILED");
                ledgerMetrics.recordOperationDuration("REVERSAL", "FAILED", System.currentTimeMillis() - startTime);
            }
            throw e;
        }
    }

    /**
     * Retrieves transaction details by ID, enforcing account ownership or administrative authorization.
     *
     * @param transactionId ID of the transaction to look up.
     * @return TransactionResponseDto containing transaction details.
     */
    @Transactional(readOnly = true)
    public TransactionResponseDto getTransaction(UUID transactionId) {
        if (transactionId == null) {
            throw new IllegalArgumentException("Transaction ID is required");
        }
        User currentUser = authenticatedUserService.getCurrentUser();
        boolean isAdmin = authenticatedUserService.isAdmin();

        Transaction tx = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));

        if (!isAdmin) {
            boolean ownsSource = tx.getSourceAccount().getUser() != null && tx.getSourceAccount().getUser().getId().equals(currentUser.getId());
            boolean ownsDest = tx.getDestinationAccount().getUser() != null && tx.getDestinationAccount().getUser().getId().equals(currentUser.getId());
            if (!ownsSource && !ownsDest) {
                log.warn("Unauthorized transaction detail access for tx {} by user {}",
                        MaskingUtils.maskAccountId(transactionId),
                        MaskingUtils.maskAccountId(currentUser.getId()));
                throw new TransactionNotFoundException(transactionId);
            }
        }

        return TransactionResponseDto.from(tx);
    }

    /**
     * Enforces anti-enumeration: users who own neither account involved in the transaction receive
     * a generic TransactionNotFoundException (404 Not Found), preventing probing of transaction IDs.
     */
    private void validateAntiEnumeration(Transaction originalTx, User currentUser, boolean isAdmin) {
        if (isAdmin) {
            return;
        }

        boolean ownsSource = originalTx.getSourceAccount().getUser() != null
                && originalTx.getSourceAccount().getUser().getId().equals(currentUser.getId());
        boolean ownsDest = originalTx.getDestinationAccount().getUser() != null
                && originalTx.getDestinationAccount().getUser().getId().equals(currentUser.getId());

        if (!ownsSource && !ownsDest) {
            log.warn("Unauthorized reversal attempt by user {} for tx {} belonging to other accounts",
                    MaskingUtils.maskAccountId(currentUser.getId()),
                    MaskingUtils.maskAccountId(originalTx.getId()));
            throw new TransactionNotFoundException(originalTx.getId());
        }
    }

    /**
     * Enforces debit authority: a user cannot cause an unauthorized debit from another user's account.
     * - TRANSFER: The compensating transaction debits the original destination account.
     *   Therefore, only the owner of the destination account (or ADMIN) can authorize the reversal.
     * - DEPOSIT: The compensating transaction debits the user's account to refund system clearing.
     *   The user must own the destination account (or ADMIN).
     * - WITHDRAWAL: The compensating transaction debits system clearing to restore funds to the user's account.
     *   The user must own the source account being refunded (or ADMIN).
     */
    private void validateDebitAuthorization(Transaction originalTx, User currentUser, boolean isAdmin) {
        if (isAdmin) {
            return;
        }

        if (originalTx.getTransactionType() == TransactionType.TRANSFER) {
            boolean ownsDebitedAccount = originalTx.getDestinationAccount().getUser() != null
                    && originalTx.getDestinationAccount().getUser().getId().equals(currentUser.getId());
            if (!ownsDebitedAccount) {
                log.warn("Unauthorized reversal attempt for transfer tx {}: user {} does not own debited account {}",
                        MaskingUtils.maskAccountId(originalTx.getId()),
                        MaskingUtils.maskAccountId(currentUser.getId()),
                        MaskingUtils.maskAccountId(originalTx.getDestinationAccount().getId()));
                throw new AccountOwnershipException("Authenticated user does not own the account debited by this reversal");
            }
        } else if (originalTx.getTransactionType() == TransactionType.DEPOSIT) {
            boolean ownsDebitedAccount = originalTx.getDestinationAccount().getUser() != null
                    && originalTx.getDestinationAccount().getUser().getId().equals(currentUser.getId());
            if (!ownsDebitedAccount) {
                log.warn("Unauthorized reversal attempt for deposit tx {}: user {} does not own debited account {}",
                        MaskingUtils.maskAccountId(originalTx.getId()),
                        MaskingUtils.maskAccountId(currentUser.getId()),
                        MaskingUtils.maskAccountId(originalTx.getDestinationAccount().getId()));
                throw new AccountOwnershipException("Authenticated user does not own the account debited by this reversal");
            }
        } else if (originalTx.getTransactionType() == TransactionType.WITHDRAWAL) {
            boolean ownsCreditedAccount = originalTx.getSourceAccount().getUser() != null
                    && originalTx.getSourceAccount().getUser().getId().equals(currentUser.getId());
            if (!ownsCreditedAccount) {
                log.warn("Unauthorized reversal attempt for withdrawal tx {}: user {} does not own account {}",
                        MaskingUtils.maskAccountId(originalTx.getId()),
                        MaskingUtils.maskAccountId(currentUser.getId()),
                        MaskingUtils.maskAccountId(originalTx.getSourceAccount().getId()));
                throw new AccountOwnershipException("Authenticated user does not own the account for this withdrawal reversal");
            }
        }
    }

    private void validateAccountStatus(Account account, String role) {
        if (account.getStatus() == AccountStatus.FROZEN) {
            log.warn("Reversal rejected: {} account {} is FROZEN", role, MaskingUtils.maskAccountId(account.getId()));
            throw new AccountFrozenException("Account " + account.getId() + " is FROZEN");
        }
        if (account.getStatus() == AccountStatus.CLOSED) {
            log.warn("Reversal rejected: {} account {} is CLOSED", role, MaskingUtils.maskAccountId(account.getId()));
            throw new AccountClosedException("Account " + account.getId() + " is CLOSED");
        }
        if (account.getStatus() != AccountStatus.ACTIVE) {
            log.warn("Reversal rejected: {} account {} is in status {}", role,
                    MaskingUtils.maskAccountId(account.getId()), account.getStatus());
            throw new AccountStatusException("Account " + account.getId() + " is not ACTIVE");
        }
    }
}
