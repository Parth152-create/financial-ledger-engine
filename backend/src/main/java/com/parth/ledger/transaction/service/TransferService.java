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

    public TransferService(AccountRepository accountRepository,
                           TransactionRepository transactionRepository,
                           LedgerEntryRepository ledgerEntryRepository,
                           IdempotencyCacheService idempotencyCacheService,
                           AuthenticatedUserService authenticatedUserService) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.idempotencyCacheService = idempotencyCacheService;
        this.authenticatedUserService = authenticatedUserService;
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

        // Standardize scale to 4 decimal places matching PostgreSQL NUMERIC(19,4)
        BigDecimal scaledAmount = request.amount().setScale(4, RoundingMode.HALF_UP);
        String currency = request.currency().trim().toUpperCase();

        // 5. Resolve authenticated application user from SecurityContext
        User authenticatedUser = authenticatedUserService.getCurrentUser();

        // Fast-Path: Check Redis idempotency cache before initiating DB transaction / locking
        Optional<TransferResponseDto> cachedResponse = Optional.empty();
        try {
            cachedResponse = idempotencyCacheService.get(cleanIdempotencyKey);
        } catch (Exception e) {
            log.warn("Error accessing Redis idempotency cache for key '{}': {}. Failing open to PostgreSQL.",
                    cleanIdempotencyKey, e.getMessage());
        }

        if (cachedResponse.isPresent()) {
            TransferResponseDto cached = cachedResponse.get();
            boolean sameSource = cached.sourceAccountId().equals(request.sourceAccountId());
            boolean sameDest = cached.destinationAccountId().equals(request.destinationAccountId());
            boolean sameAmount = cached.amount().compareTo(scaledAmount) == 0;
            boolean sameCurrency = cached.currency().equalsIgnoreCase(currency);

            if (sameSource && sameDest && sameAmount && sameCurrency) {
                // Verify source account ownership on Redis fast-path hit
                if (!accountRepository.existsByIdAndUserId(request.sourceAccountId(), authenticatedUser.getId())) {
                    log.warn("Unauthorized attempt to access cached transfer for source account {} by user {}",
                            request.sourceAccountId(), authenticatedUser.getId());
                    throw new AccountOwnershipException("Authenticated user does not own source account");
                }
                log.info("Redis idempotency fast-path hit for key '{}'. Returning cached transaction {}",
                        cleanIdempotencyKey, cached.transactionId());
                return cached;
            } else {
                log.warn("Idempotency conflict detected in Redis cache for key '{}'. Cached: [source={}, dest={}, amount={}, currency={}], Request: [source={}, dest={}, amount={}, currency={}]",
                        cleanIdempotencyKey,
                        cached.sourceAccountId(),
                        cached.destinationAccountId(),
                        cached.amount(),
                        cached.currency(),
                        request.sourceAccountId(),
                        request.destinationAccountId(),
                        scaledAmount,
                        currency);
                throw new IdempotencyConflictException(
                        "Idempotency key '" + cleanIdempotencyKey + "' was already used for a transfer with different parameters"
                );
            }
        }

        // 6. Pre-lock Idempotency Check: Fast return for committed retries or conflict detection
        Optional<Transaction> existingTx = transactionRepository.findByIdempotencyKey(cleanIdempotencyKey);
        if (existingTx.isPresent()) {
            return handleExistingTransaction(existingTx.get(), request, scaledAmount, currency, cleanIdempotencyKey, authenticatedUser);
        }

        // 8. Deterministic Lock Ordering:
        // Prevent two-account circular-wait deadlocks (e.g. concurrent A -> B and B -> A) by sorting
        // the account UUIDs and always acquiring locks in ascending UUID order.
        // NOTE: While deterministic ordering eliminates classic circular-wait patterns between these
        // two accounts, it does not claim to eliminate all conceivable distributed deadlock conditions.
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

        // Post-lock Idempotency Re-check:
        // If a concurrent request with the exact same idempotency key was in-flight, it held the account
        // lock while this thread was waiting. Now that this thread acquired the lock, check if the other
        // thread committed the transaction to avoid duplicate transfers.
        Optional<Transaction> txAfterLock = transactionRepository.findByIdempotencyKey(cleanIdempotencyKey);
        if (txAfterLock.isPresent()) {
            return handleExistingTransaction(txAfterLock.get(), request, scaledAmount, currency, cleanIdempotencyKey, authenticatedUser);
        }

        // Account Type Validation:
        // User transfers can strictly only occur between USER_CHECKING accounts.
        // System clearing accounts must NOT be transferred via the user transfer endpoint.
        if (sourceAccount.getAccountType() != AccountType.USER_CHECKING) {
            log.warn("Transfer rejected: source account {} has ineligible type {}", sourceId, sourceAccount.getAccountType());
            throw new InvalidAccountTypeException("Source account must be a USER_CHECKING account: " + sourceId);
        }
        if (destinationAccount.getAccountType() != AccountType.USER_CHECKING) {
            log.warn("Transfer rejected: destination account {} has ineligible type {}", destinationId, destinationAccount.getAccountType());
            throw new InvalidAccountTypeException("Destination account must be a USER_CHECKING account: " + destinationId);
        }

        // 9. Source Account Ownership Authorization:
        // Verify that the authenticated application user is the owner of the source account being debited.
        // This check occurs while holding the pessimistic write lock on the source account to eliminate TOCTOU races.
        if (sourceAccount.getUser() == null || !sourceAccount.getUser().getId().equals(authenticatedUser.getId())) {
            log.warn("Unauthorized transfer: user {} does not own source account {}",
                    authenticatedUser.getId(), sourceId);
            throw new AccountOwnershipException("Authenticated user does not own source account");
        }

        // Account Status Validation:
        // Ensure both source and destination accounts are ACTIVE. Transfers fail if FROZEN or CLOSED.
        if (sourceAccount.getStatus() == AccountStatus.FROZEN) {
            log.warn("Transfer rejected: source account {} is FROZEN", sourceId);
            throw new AccountFrozenException("Source account " + sourceId + " is FROZEN");
        }
        if (sourceAccount.getStatus() == AccountStatus.CLOSED) {
            log.warn("Transfer rejected: source account {} is CLOSED", sourceId);
            throw new AccountClosedException("Source account " + sourceId + " is CLOSED");
        }
        if (sourceAccount.getStatus() != AccountStatus.ACTIVE) {
            log.warn("Transfer rejected: source account {} is in status {}", sourceId, sourceAccount.getStatus());
            throw new AccountStatusException("Source account " + sourceId + " is not ACTIVE");
        }

        if (destinationAccount.getStatus() == AccountStatus.FROZEN) {
            log.warn("Transfer rejected: destination account {} is FROZEN", destinationId);
            throw new AccountFrozenException("Destination account " + destinationId + " is FROZEN");
        }
        if (destinationAccount.getStatus() == AccountStatus.CLOSED) {
            log.warn("Transfer rejected: destination account {} is CLOSED", destinationId);
            throw new AccountClosedException("Destination account " + destinationId + " is CLOSED");
        }
        if (destinationAccount.getStatus() != AccountStatus.ACTIVE) {
            log.warn("Transfer rejected: destination account {} is in status {}", destinationId, destinationAccount.getStatus());
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
                destinationAccount
        );
        transaction = transactionRepository.save(transaction);

        // 13. Create DEBIT ledger entry & 14. Create CREDIT ledger entry
        LedgerEntry debitEntry = new LedgerEntry(
                transaction,
                sourceAccount,
                LedgerEntryType.DEBIT,
                scaledAmount
        );
        LedgerEntry creditEntry = new LedgerEntry(
                transaction,
                destinationAccount,
                LedgerEntryType.CREDIT,
                scaledAmount
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

        log.info("Successfully executed transfer: txId={}, amount={} {}, from={} to={}",
                transaction.getId(), scaledAmount, currency, sourceId, destinationId);

        TransferResponseDto response = TransferResponseDto.from(transaction);

        // Store successful response in Redis ONLY after the PostgreSQL transaction commits
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        idempotencyCacheService.set(cleanIdempotencyKey, response);
                    } catch (Exception e) {
                        log.warn("Failed to cache response in Redis after commit for key '{}': {}",
                                cleanIdempotencyKey, e.getMessage());
                    }
                }
            });
        } else {
            try {
                idempotencyCacheService.set(cleanIdempotencyKey, response);
            } catch (Exception e) {
                log.warn("Failed to cache response in Redis for key '{}': {}",
                        cleanIdempotencyKey, e.getMessage());
            }
        }

        // 18. Return transaction result DTO
        return response;
    }

    private TransferResponseDto handleExistingTransaction(
            Transaction existing,
            TransferRequestDto request,
            BigDecimal scaledAmount,
            String currency,
            String idempotencyKey,
            User authenticatedUser) {

        boolean sameSource = existing.getSourceAccount().getId().equals(request.sourceAccountId());
        boolean sameDest = existing.getDestinationAccount().getId().equals(request.destinationAccountId());
        boolean sameAmount = existing.getAmount().compareTo(scaledAmount) == 0;
        boolean sameCurrency = existing.getCurrency().equalsIgnoreCase(currency);

        if (sameSource && sameDest && sameAmount && sameCurrency) {
            // Verify source account ownership on database idempotency retry
            if (existing.getSourceAccount().getUser() == null || !existing.getSourceAccount().getUser().getId().equals(authenticatedUser.getId())) {
                log.warn("Unauthorized attempt to access existing transaction {} for source account {} by user {}",
                        existing.getId(), existing.getSourceAccount().getId(), authenticatedUser.getId());
                throw new AccountOwnershipException("Authenticated user does not own source account");
            }

            log.info("Idempotent retry detected for key '{}'. Returning existing transaction {}",
                    idempotencyKey, existing.getId());
            TransferResponseDto response = TransferResponseDto.from(existing);
            try {
                idempotencyCacheService.set(idempotencyKey, response);
            } catch (Exception e) {
                log.warn("Failed to repopulate Redis cache for key '{}': {}", idempotencyKey, e.getMessage());
            }
            return response;
        } else {
            log.warn("Idempotency conflict for key '{}'. Existing: [source={}, dest={}, amount={}, currency={}], Request: [source={}, dest={}, amount={}, currency={}]",
                    idempotencyKey,
                    existing.getSourceAccount().getId(),
                    existing.getDestinationAccount().getId(),
                    existing.getAmount(),
                    existing.getCurrency(),
                    request.sourceAccountId(),
                    request.destinationAccountId(),
                    scaledAmount,
                    currency);
            throw new IdempotencyConflictException(
                    "Idempotency key '" + idempotencyKey + "' was already used for a transfer with different parameters"
            );
        }
    }
}
