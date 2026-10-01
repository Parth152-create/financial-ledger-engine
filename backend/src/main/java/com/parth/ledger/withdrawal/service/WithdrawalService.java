package com.parth.ledger.withdrawal.service;

import com.parth.ledger.account.Account;
import com.parth.ledger.account.AccountClosedException;
import com.parth.ledger.account.AccountFrozenException;
import com.parth.ledger.account.AccountNotFoundException;
import com.parth.ledger.account.AccountRepository;
import com.parth.ledger.account.AccountStatus;
import com.parth.ledger.account.AccountStatusException;
import com.parth.ledger.account.AccountType;
import com.parth.ledger.idempotency.IdempotencyCacheService;
import com.parth.ledger.ledger.LedgerEntry;
import com.parth.ledger.ledger.LedgerEntryRepository;
import com.parth.ledger.ledger.LedgerEntryType;
import com.parth.ledger.security.AuthenticatedUserService;
import com.parth.ledger.transaction.Transaction;
import com.parth.ledger.transaction.TransactionRepository;
import com.parth.ledger.transaction.TransactionStatus;
import com.parth.ledger.transaction.TransactionType;
import com.parth.ledger.transaction.dto.TransactionResponseDto;
import com.parth.ledger.transaction.exception.CurrencyMismatchException;
import com.parth.ledger.transaction.exception.IdempotencyConflictException;
import com.parth.ledger.transaction.exception.InsufficientBalanceException;
import com.parth.ledger.transaction.exception.InvalidAmountException;
import com.parth.ledger.transaction.exception.UnbalancedLedgerException;
import com.parth.ledger.user.User;
import com.parth.ledger.withdrawal.dto.WithdrawalRequestDto;
import com.parth.ledger.withdrawal.dto.WithdrawalResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class WithdrawalService {

    private static final Logger log = LoggerFactory.getLogger(WithdrawalService.class);

    public static final UUID SYSTEM_CLEARING_ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final IdempotencyCacheService idempotencyCacheService;
    private final AuthenticatedUserService authenticatedUserService;
    private final com.parth.ledger.audit.AuditEventService auditEventService;
    private final com.parth.ledger.policy.PolicyService policyService;
    private final com.parth.ledger.observability.metrics.LedgerMetrics ledgerMetrics;

    @org.springframework.beans.factory.annotation.Autowired
    public WithdrawalService(AccountRepository accountRepository,
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

    public WithdrawalService(AccountRepository accountRepository,
                             TransactionRepository transactionRepository,
                             LedgerEntryRepository ledgerEntryRepository,
                             IdempotencyCacheService idempotencyCacheService,
                             AuthenticatedUserService authenticatedUserService,
                             com.parth.ledger.audit.AuditEventService auditEventService,
                             com.parth.ledger.policy.PolicyService policyService) {
        this(accountRepository, transactionRepository, ledgerEntryRepository, idempotencyCacheService, authenticatedUserService, auditEventService, policyService, null);
    }

    @Transactional
    public TransactionResponseDto executeWithdrawal(String idempotencyKey, WithdrawalRequestDto request) {
        return processWithdrawal(idempotencyKey, request).response();
    }

    @Transactional
    public WithdrawalResult processWithdrawal(String idempotencyKey, WithdrawalRequestDto request) {
        if (idempotencyKey == null || idempotencyKey.trim().isEmpty()) {
            throw new IllegalArgumentException("Idempotency key must not be blank");
        }
        String cleanIdempotencyKey = idempotencyKey.trim();

        if (request == null) {
            throw new IllegalArgumentException("Withdrawal request body must not be null");
        }
        if (request.accountId() == null) {
            throw new IllegalArgumentException("Source account ID is required");
        }

        if (request.amount() == null || request.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidAmountException("Withdrawal amount must be greater than zero");
        }
        if (request.amount().stripTrailingZeros().scale() > 4) {
            throw new InvalidAmountException("Withdrawal amount precision cannot exceed 4 decimal places");
        }

        if (request.currency() == null || request.currency().trim().isEmpty()) {
            throw new IllegalArgumentException("Currency must not be blank");
        }
        String currency = request.currency().trim().toUpperCase(Locale.ROOT);
        if (!currency.matches("^[A-Z]{3}$")) {
            throw new IllegalArgumentException("Currency must be a 3-character ISO code: " + currency);
        }
        if (!"INR".equals(currency)) {
            throw new IllegalArgumentException("Only INR currency is supported: " + currency);
        }

        long startTime = System.currentTimeMillis();
        if (ledgerMetrics != null) {
            ledgerMetrics.recordOperation("WITHDRAWAL", "ATTEMPTED");
        }

        try {
            BigDecimal scaledAmount = request.amount().setScale(4, RoundingMode.HALF_UP);
            String cleanDescription = (request.description() != null && !request.description().isBlank())
                    ? request.description().trim()
                    : null;
            if (cleanDescription != null && cleanDescription.length() > 255) {
                throw new IllegalArgumentException("Description cannot exceed 255 characters");
            }

            User authenticatedUser = authenticatedUserService.getCurrentUser();

            Optional<TransactionResponseDto> cachedResponse = Optional.empty();
            try {
                cachedResponse = idempotencyCacheService.get(cleanIdempotencyKey, TransactionResponseDto.class);
            } catch (Exception e) {
                log.warn("Error accessing Redis idempotency cache: {}. Failing open to PostgreSQL.", e.getMessage());
            }

            if (cachedResponse.isPresent()) {
                TransactionResponseDto cached = cachedResponse.get();
                boolean sameType = cached.transactionType() == TransactionType.WITHDRAWAL;
                boolean sameSource = cached.sourceAccountId().equals(request.accountId());
                boolean sameAmount = cached.amount().compareTo(scaledAmount) == 0;
                boolean sameCurrency = cached.currency().equalsIgnoreCase(currency);
                boolean sameDesc = Objects.equals(cached.description(), cleanDescription);

                if (sameType && sameSource && sameAmount && sameCurrency && sameDesc) {
                    if (!accountRepository.existsByIdAndUserId(request.accountId(), authenticatedUser.getId())) {
                        log.warn("Unauthorized attempt to access cached withdrawal for account {} by user {}",
                                com.parth.ledger.observability.logging.MaskingUtils.maskAccountId(request.accountId()),
                                com.parth.ledger.observability.logging.MaskingUtils.maskAccountId(authenticatedUser.getId()));
                        throw new AccountNotFoundException("Account not found: " + request.accountId());
                    }
                    if (ledgerMetrics != null) {
                        ledgerMetrics.recordIdempotencyOutcome("WITHDRAWAL", "REPLAY");
                        ledgerMetrics.recordIdempotencyDuration("WITHDRAWAL", "REPLAY", System.currentTimeMillis() - startTime);
                    }
                    log.info("Redis idempotency fast-path hit. Returning cached transaction {}",
                            com.parth.ledger.observability.logging.MaskingUtils.maskAccountId(cached.transactionId()));
                    return new WithdrawalResult(cached, true);
                } else {
                    log.warn("Idempotency conflict detected in Redis cache for withdrawal.");
                    throw new IdempotencyConflictException(
                            "Idempotency key '" + cleanIdempotencyKey + "' was already used for a transaction with different parameters"
                    );
                }
            }

            Optional<Transaction> existingTx = transactionRepository.findByIdempotencyKey(cleanIdempotencyKey);
            if (existingTx.isPresent()) {
                return handleExistingTransaction(existingTx.get(), request, scaledAmount, currency, cleanDescription, cleanIdempotencyKey, authenticatedUser, startTime);
            }

            UUID sourceId = request.accountId();
            UUID clearingId = SYSTEM_CLEARING_ACCOUNT_ID;

            if (clearingId.equals(sourceId)) {
                log.warn("Withdrawal rejected: attempted to use SYSTEM_CLEARING account as source");
                throw new AccountNotFoundException("Account not found: " + sourceId);
            }

            UUID firstLockId = sourceId.compareTo(clearingId) < 0 ? sourceId : clearingId;
            UUID secondLockId = sourceId.compareTo(clearingId) < 0 ? clearingId : sourceId;

            Account firstAccount = accountRepository.findByIdForUpdate(firstLockId)
                    .orElseThrow(() -> new AccountNotFoundException("Account not found: " + firstLockId));
            Account secondAccount = accountRepository.findByIdForUpdate(secondLockId)
                    .orElseThrow(() -> new AccountNotFoundException("Account not found: " + secondLockId));

            Account lockedSourceAccount = sourceId.equals(firstAccount.getId()) ? firstAccount : secondAccount;
            Account lockedClearingAccount = clearingId.equals(secondAccount.getId()) ? secondAccount : firstAccount;

            Optional<Transaction> txAfterLock = transactionRepository.findByIdempotencyKey(cleanIdempotencyKey);
            if (txAfterLock.isPresent()) {
                return handleExistingTransaction(txAfterLock.get(), request, scaledAmount, currency, cleanDescription, cleanIdempotencyKey, authenticatedUser, startTime);
            }

            if (lockedClearingAccount.getAccountType() != AccountType.SYSTEM_CLEARING) {
                throw new IllegalStateException("Configured clearing account is not of type SYSTEM_CLEARING");
            }
            if (!lockedClearingAccount.getCurrency().equalsIgnoreCase(currency)) {
                throw new CurrencyMismatchException(
                        "Currency mismatch: withdrawal currency '" + currency + "' does not match system clearing account currency '" + lockedClearingAccount.getCurrency() + "'"
                );
            }
            if (lockedClearingAccount.getStatus() != AccountStatus.ACTIVE) {
                throw new AccountStatusException("System clearing account " + clearingId + " is not ACTIVE");
            }

            if (lockedSourceAccount.getAccountType() != AccountType.USER_CHECKING) {
                log.warn("Withdrawal rejected: source account {} has ineligible type {}",
                        com.parth.ledger.observability.logging.MaskingUtils.maskAccountId(sourceId), lockedSourceAccount.getAccountType());
                throw new AccountNotFoundException("Account not found: " + sourceId);
            }
            if (lockedSourceAccount.getUser() == null || !lockedSourceAccount.getUser().getId().equals(authenticatedUser.getId())) {
                log.warn("Source account {} not found or unauthorized for user {}",
                        com.parth.ledger.observability.logging.MaskingUtils.maskAccountId(sourceId),
                        com.parth.ledger.observability.logging.MaskingUtils.maskAccountId(authenticatedUser.getId()));
                throw new AccountNotFoundException("Account not found: " + sourceId);
            }

            if (lockedSourceAccount.getStatus() == AccountStatus.FROZEN) {
                log.warn("Withdrawal rejected: source account {} is FROZEN",
                        com.parth.ledger.observability.logging.MaskingUtils.maskAccountId(sourceId));
                throw new AccountFrozenException("Source account " + sourceId + " is FROZEN");
            }
            if (lockedSourceAccount.getStatus() == AccountStatus.CLOSED) {
                log.warn("Withdrawal rejected: source account {} is CLOSED",
                        com.parth.ledger.observability.logging.MaskingUtils.maskAccountId(sourceId));
                throw new AccountClosedException("Source account " + sourceId + " is CLOSED");
            }
            if (lockedSourceAccount.getStatus() != AccountStatus.ACTIVE) {
                log.warn("Withdrawal rejected: source account {} is in status {}",
                        com.parth.ledger.observability.logging.MaskingUtils.maskAccountId(sourceId), lockedSourceAccount.getStatus());
                throw new AccountStatusException("Source account " + sourceId + " is not ACTIVE");
            }

            if (!lockedSourceAccount.getCurrency().equalsIgnoreCase(currency)) {
                throw new CurrencyMismatchException(
                        "Currency mismatch: withdrawal currency '" + currency + "' does not match source account currency '" + lockedSourceAccount.getCurrency() + "'"
                );
            }

            if (lockedSourceAccount.getBalance().compareTo(scaledAmount) < 0) {
                log.warn("Withdrawal rejected: insufficient balance in source account: available {}, required {}",
                        lockedSourceAccount.getBalance(), scaledAmount);
                throw new InsufficientBalanceException(
                        "Insufficient balance in source account: available "
                                + lockedSourceAccount.getBalance() + ", required " + scaledAmount
                );
            }

            // Policy Engine Evaluation: Max withdrawal amount, daily withdrawal amount, daily withdrawal count
            try {
                policyService.evaluateAndRecordWithdrawalLimits(lockedSourceAccount, scaledAmount);
            } catch (com.parth.ledger.policy.PolicyViolationException ex) {
                try {
                    auditEventService.recordPolicyRejectionEventOnce(
                            authenticatedUser != null ? authenticatedUser.getId() : null,
                            com.parth.ledger.audit.AuditEventType.WITHDRAWAL_REJECTED_POLICY,
                            com.parth.ledger.audit.AuditEntityType.ACCOUNT,
                            sourceId,
                            cleanIdempotencyKey,
                            "WITHDRAWAL",
                            java.util.Map.of(
                                    "policyType", ex.getErrorCode().name(),
                                    "reason", ex.getMessage(),
                                    "amount", scaledAmount,
                                    "currency", currency,
                                    "sourceAccountId", sourceId
                            )
                    );
                } catch (Exception auditErr) {
                    log.warn("Failed to record WITHDRAWAL_REJECTED_POLICY audit event: {}", auditErr.getMessage());
                }
                throw ex;
            }

            lockedSourceAccount.setBalance(lockedSourceAccount.getBalance().subtract(scaledAmount));
            lockedClearingAccount.setBalance(lockedClearingAccount.getBalance().add(scaledAmount));

            accountRepository.save(lockedSourceAccount);
            accountRepository.save(lockedClearingAccount);

            Transaction transaction = new Transaction(
                    cleanIdempotencyKey,
                    scaledAmount,
                    currency,
                    TransactionStatus.PENDING,
                    lockedSourceAccount,
                    lockedClearingAccount,
                    TransactionType.WITHDRAWAL,
                    authenticatedUser,
                    cleanDescription
            );
            transaction = transactionRepository.save(transaction);

            LedgerEntry debitEntry = new LedgerEntry(
                    transaction,
                    lockedSourceAccount,
                    LedgerEntryType.DEBIT,
                    scaledAmount,
                    currency
            );
            LedgerEntry creditEntry = new LedgerEntry(
                    transaction,
                    lockedClearingAccount,
                    LedgerEntryType.CREDIT,
                    scaledAmount,
                    currency
            );

            ledgerEntryRepository.save(debitEntry);
            ledgerEntryRepository.save(creditEntry);

            BigDecimal totalDebits = debitEntry.getAmount();
            BigDecimal totalCredits = creditEntry.getAmount();
            if (totalDebits.compareTo(totalCredits) != 0) {
                throw new UnbalancedLedgerException(
                        "Double-entry ledger invariant violation: total debits (" + totalDebits
                                + ") do not equal total credits (" + totalCredits + ")"
                );
            }

            transaction.setStatus(TransactionStatus.COMPLETED);
            transaction.setCompletedAt(Instant.now());
            transaction = transactionRepository.save(transaction);

            // Record WITHDRAWAL_COMPLETED operational audit event atomically within PostgreSQL transaction
            auditEventService.recordEvent(
                    authenticatedUser != null ? authenticatedUser.getId() : null,
                    com.parth.ledger.audit.AuditEventType.WITHDRAWAL_COMPLETED,
                    com.parth.ledger.audit.AuditEntityType.TRANSACTION,
                    transaction.getId(),
                    java.util.Map.of(
                            "amount", scaledAmount,
                            "currency", currency,
                            "sourceAccountId", sourceId
                    )
            );

            log.info("Successfully executed withdrawal: txId={}, amount={} {}, userAccount={} to clearing={}",
                    com.parth.ledger.observability.logging.MaskingUtils.maskAccountId(transaction.getId()), scaledAmount, currency,
                    com.parth.ledger.observability.logging.MaskingUtils.maskAccountId(sourceId),
                    com.parth.ledger.observability.logging.MaskingUtils.maskAccountId(clearingId));

            TransactionResponseDto response = TransactionResponseDto.from(transaction);

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
                            ledgerMetrics.recordOperation("WITHDRAWAL", "COMPLETED");
                            ledgerMetrics.recordIdempotencyOutcome("WITHDRAWAL", "FIRST_EXECUTION");
                            ledgerMetrics.recordOperationDuration("WITHDRAWAL", "COMPLETED", System.currentTimeMillis() - startTime);
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
                    ledgerMetrics.recordOperation("WITHDRAWAL", "COMPLETED");
                    ledgerMetrics.recordIdempotencyOutcome("WITHDRAWAL", "FIRST_EXECUTION");
                    ledgerMetrics.recordOperationDuration("WITHDRAWAL", "COMPLETED", System.currentTimeMillis() - startTime);
                }
            }

            return new WithdrawalResult(response, false);

        } catch (IdempotencyConflictException e) {
            if (ledgerMetrics != null) {
                ledgerMetrics.recordIdempotencyOutcome("WITHDRAWAL", "CONFLICT");
                ledgerMetrics.recordIdempotencyDuration("WITHDRAWAL", "CONFLICT", System.currentTimeMillis() - startTime);
                ledgerMetrics.recordOperation("WITHDRAWAL", "REJECTED");
                ledgerMetrics.recordOperationDuration("WITHDRAWAL", "REJECTED", System.currentTimeMillis() - startTime);
            }
            throw e;
        } catch (AccountNotFoundException | AccountStatusException |
                 CurrencyMismatchException | InsufficientBalanceException |
                 InvalidAmountException | com.parth.ledger.policy.PolicyViolationException e) {
            if (ledgerMetrics != null) {
                ledgerMetrics.recordOperation("WITHDRAWAL", "REJECTED");
                ledgerMetrics.recordOperationDuration("WITHDRAWAL", "REJECTED", System.currentTimeMillis() - startTime);
            }
            throw e;
        } catch (RuntimeException | Error e) {
            if (ledgerMetrics != null) {
                ledgerMetrics.recordOperation("WITHDRAWAL", "FAILED");
                ledgerMetrics.recordOperationDuration("WITHDRAWAL", "FAILED", System.currentTimeMillis() - startTime);
            }
            throw e;
        }
    }

    private WithdrawalResult handleExistingTransaction(
            Transaction existing,
            WithdrawalRequestDto request,
            BigDecimal scaledAmount,
            String currency,
            String cleanDescription,
            String idempotencyKey,
            User authenticatedUser,
            long startTime) {

        boolean sameType = existing.getTransactionType() == TransactionType.WITHDRAWAL;
        boolean sameSource = existing.getSourceAccount().getId().equals(request.accountId());
        boolean sameAmount = existing.getAmount().compareTo(scaledAmount) == 0;
        boolean sameCurrency = existing.getCurrency().equalsIgnoreCase(currency);
        boolean sameDesc = Objects.equals(existing.getDescription(), cleanDescription);

        if (sameType && sameSource && sameAmount && sameCurrency && sameDesc) {
            if (existing.getSourceAccount().getUser() == null ||
                    !existing.getSourceAccount().getUser().getId().equals(authenticatedUser.getId())) {
                log.warn("Unauthorized attempt to access existing withdrawal for user {}",
                        com.parth.ledger.observability.logging.MaskingUtils.maskAccountId(authenticatedUser.getId()));
                throw new AccountNotFoundException("Account not found: " + request.accountId());
            }

            if (ledgerMetrics != null) {
                ledgerMetrics.recordIdempotencyOutcome("WITHDRAWAL", "REPLAY");
                ledgerMetrics.recordIdempotencyDuration("WITHDRAWAL", "REPLAY", System.currentTimeMillis() - startTime);
            }

            log.info("Idempotent retry detected for withdrawal. Returning existing transaction {}",
                    com.parth.ledger.observability.logging.MaskingUtils.maskAccountId(existing.getId()));
            TransactionResponseDto response = TransactionResponseDto.from(existing);
            try {
                idempotencyCacheService.set(idempotencyKey, response);
            } catch (Exception e) {
                log.warn("Failed to repopulate Redis cache: {}", e.getMessage());
            }
            return new WithdrawalResult(response, true);
        } else {
            log.warn("Idempotency conflict for withdrawal.");
            throw new IdempotencyConflictException(
                    "Idempotency key '" + idempotencyKey + "' was already used for a transaction with different parameters"
            );
        }
    }
}
