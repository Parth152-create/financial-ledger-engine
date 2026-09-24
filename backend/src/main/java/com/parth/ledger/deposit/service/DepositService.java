package com.parth.ledger.deposit.service;

import com.parth.ledger.account.Account;
import com.parth.ledger.account.AccountClosedException;
import com.parth.ledger.account.AccountFrozenException;
import com.parth.ledger.account.AccountNotFoundException;
import com.parth.ledger.account.AccountRepository;
import com.parth.ledger.account.AccountStatus;
import com.parth.ledger.account.AccountStatusException;
import com.parth.ledger.account.AccountType;
import com.parth.ledger.account.InvalidAccountTypeException;
import com.parth.ledger.deposit.dto.DepositRequestDto;
import com.parth.ledger.deposit.dto.DepositResult;
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

/**
 * Service orchestrating atomic, concurrency-safe, double-entry financial deposits.
 *
 * Deposits transfer existing platform funds from the platform-owned SYSTEM_CLEARING account
 * to an authenticated user's USER_CHECKING account:
 *   SYSTEM_CLEARING: DEBIT X
 *   USER_CHECKING:   CREDIT X
 *
 * A deposit never mints money without corresponding clearing debits and ledger entries.
 * All mutations occur within a single authoritative PostgreSQL transaction, protected by
 * deterministic pessimistic row-level locking (PESSIMISTIC_WRITE / SELECT FOR UPDATE).
 */
@Service
public class DepositService {

    private static final Logger log = LoggerFactory.getLogger(DepositService.class);

    public static final UUID SYSTEM_CLEARING_ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final IdempotencyCacheService idempotencyCacheService;
    private final AuthenticatedUserService authenticatedUserService;

    public DepositService(AccountRepository accountRepository,
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
     * Executes a deposit returning the transaction response DTO.
     *
     * @param idempotencyKey Client-supplied unique key from Idempotency-Key header.
     * @param request Deposit request parameters.
     * @return TransactionResponseDto containing completed transaction details.
     */
    @Transactional
    public TransactionResponseDto executeDeposit(String idempotencyKey, DepositRequestDto request) {
        return processDeposit(idempotencyKey, request).response();
    }

    /**
     * Processes a deposit returning both the transaction response and an idempotent replay indicator.
     *
     * @param idempotencyKey Client-supplied unique key from Idempotency-Key header.
     * @param request Deposit request parameters.
     * @return DepositResult with transaction response and boolean replay flag.
     */
    @Transactional
    public DepositResult processDeposit(String idempotencyKey, DepositRequestDto request) {
        // 1. Validate request parameters
        if (idempotencyKey == null || idempotencyKey.trim().isEmpty()) {
            throw new IllegalArgumentException("Idempotency key must not be blank");
        }
        String cleanIdempotencyKey = idempotencyKey.trim();

        if (request == null) {
            throw new IllegalArgumentException("Deposit request body must not be null");
        }
        if (request.accountId() == null) {
            throw new IllegalArgumentException("Destination account ID is required");
        }

        // Validate amount > 0 and precision
        if (request.amount() == null || request.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidAmountException("Deposit amount must be greater than zero");
        }
        if (request.amount().stripTrailingZeros().scale() > 4) {
            throw new InvalidAmountException("Deposit amount precision cannot exceed 4 decimal places");
        }

        if (request.currency() == null || request.currency().trim().isEmpty()) {
            throw new IllegalArgumentException("Currency must not be blank");
        }
        String currency = request.currency().trim().toUpperCase(Locale.ROOT);
        if (!currency.matches("^[A-Z]{3}$")) {
            throw new IllegalArgumentException("Currency must be a 3-character ISO code: " + currency);
        }

        // Standardize scale to 4 decimal places matching PostgreSQL NUMERIC(19,4)
        BigDecimal scaledAmount = request.amount().setScale(4, RoundingMode.HALF_UP);
        String cleanDescription = (request.description() != null && !request.description().isBlank())
                ? request.description().trim()
                : null;
        if (cleanDescription != null && cleanDescription.length() > 255) {
            throw new IllegalArgumentException("Description cannot exceed 255 characters");
        }

        // 2. Resolve authenticated application user from SecurityContext
        User authenticatedUser = authenticatedUserService.getCurrentUser();

        // 3. Fast-Path: Check Redis idempotency cache before DB locking
        Optional<TransactionResponseDto> cachedResponse = Optional.empty();
        try {
            cachedResponse = idempotencyCacheService.get(cleanIdempotencyKey, TransactionResponseDto.class);
        } catch (Exception e) {
            log.warn("Error accessing Redis idempotency cache for key '{}': {}. Failing open to PostgreSQL.",
                    cleanIdempotencyKey, e.getMessage());
        }

        if (cachedResponse.isPresent()) {
            TransactionResponseDto cached = cachedResponse.get();
            boolean sameType = cached.transactionType() == TransactionType.DEPOSIT;
            boolean sameDest = cached.destinationAccountId().equals(request.accountId());
            boolean sameAmount = cached.amount().compareTo(scaledAmount) == 0;
            boolean sameCurrency = cached.currency().equalsIgnoreCase(currency);
            boolean sameDesc = Objects.equals(cached.description(), cleanDescription);

            if (sameType && sameDest && sameAmount && sameCurrency && sameDesc) {
                // Verify destination account ownership on Redis fast-path hit
                if (!accountRepository.existsByIdAndUserId(request.accountId(), authenticatedUser.getId())) {
                    log.warn("Unauthorized attempt to access cached deposit for account {} by user {}",
                            request.accountId(), authenticatedUser.getId());
                    throw new AccountNotFoundException("Account not found: " + request.accountId());
                }
                log.info("Redis idempotency fast-path hit for key '{}'. Returning cached transaction {}",
                        cleanIdempotencyKey, cached.transactionId());
                return new DepositResult(cached, true);
            } else {
                log.warn("Idempotency conflict detected in Redis cache for key '{}'", cleanIdempotencyKey);
                throw new IdempotencyConflictException(
                        "Idempotency key '" + cleanIdempotencyKey + "' was already used for a transaction with different parameters"
                );
            }
        }

        // 4. Pre-lock Idempotency Check in PostgreSQL
        Optional<Transaction> existingTx = transactionRepository.findByIdempotencyKey(cleanIdempotencyKey);
        if (existingTx.isPresent()) {
            return handleExistingTransaction(existingTx.get(), request, scaledAmount, currency, cleanDescription, cleanIdempotencyKey, authenticatedUser);
        }

        // 5. System clearing account ID and destination ID
        UUID clearingId = SYSTEM_CLEARING_ACCOUNT_ID;
        UUID destinationId = request.accountId();

        // Reject depositing directly into SYSTEM_CLEARING account
        if (clearingId.equals(destinationId)) {
            log.warn("Deposit rejected: attempted to use SYSTEM_CLEARING account {} as destination", destinationId);
            throw new InvalidAccountTypeException("Cannot deposit into SYSTEM_CLEARING account: " + destinationId);
        }

        // 6. Deterministic Lock Ordering:
        // Always acquire locks in ascending UUID order to prevent circular-wait deadlocks.
        UUID firstLockId;
        UUID secondLockId;
        if (clearingId.compareTo(destinationId) < 0) {
            firstLockId = clearingId;
            secondLockId = destinationId;
        } else {
            firstLockId = destinationId;
            secondLockId = clearingId;
        }

        Account firstAccount = accountRepository.findByIdForUpdate(firstLockId)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + firstLockId));
        Account secondAccount = accountRepository.findByIdForUpdate(secondLockId)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + secondLockId));

        Account lockedClearingAccount = clearingId.equals(firstAccount.getId()) ? firstAccount : secondAccount;
        Account lockedDestinationAccount = destinationId.equals(secondAccount.getId()) ? secondAccount : firstAccount;

        // 7. Post-lock Idempotency Re-check:
        // If a concurrent request with the exact same idempotency key committed while this thread waited, return it.
        Optional<Transaction> txAfterLock = transactionRepository.findByIdempotencyKey(cleanIdempotencyKey);
        if (txAfterLock.isPresent()) {
            return handleExistingTransaction(txAfterLock.get(), request, scaledAmount, currency, cleanDescription, cleanIdempotencyKey, authenticatedUser);
        }

        // Validate clearing account properties under lock
        if (lockedClearingAccount.getAccountType() != AccountType.SYSTEM_CLEARING) {
            throw new IllegalStateException("Configured clearing account " + clearingId + " is not of type SYSTEM_CLEARING");
        }
        if (!lockedClearingAccount.getCurrency().equalsIgnoreCase(currency)) {
            throw new CurrencyMismatchException(
                    "Currency mismatch: deposit currency '" + currency + "' does not match system clearing account currency '" + lockedClearingAccount.getCurrency() + "'"
            );
        }

        // 8. Account Type Validation:
        // Deposits can strictly only be credited to USER_CHECKING accounts.
        if (lockedDestinationAccount.getAccountType() != AccountType.USER_CHECKING) {
            log.warn("Deposit rejected: destination account {} has ineligible type {}", destinationId, lockedDestinationAccount.getAccountType());
            throw new InvalidAccountTypeException("Destination account must be a USER_CHECKING account: " + destinationId);
        }

        // 9. Destination Account Ownership Authorization:
        // Verify destination account belongs to authenticated user (404 on mismatch to prevent account enumeration)
        if (lockedDestinationAccount.getUser() == null || !lockedDestinationAccount.getUser().getId().equals(authenticatedUser.getId())) {
            log.warn("Destination account {} not found or unauthorized for user {}", destinationId, authenticatedUser.getId());
            throw new AccountNotFoundException("Account not found: " + destinationId);
        }

        // 10. Account Status Validation:
        if (lockedDestinationAccount.getStatus() == AccountStatus.FROZEN) {
            log.warn("Deposit rejected: destination account {} is FROZEN", destinationId);
            throw new AccountFrozenException("Destination account " + destinationId + " is FROZEN");
        }
        if (lockedDestinationAccount.getStatus() == AccountStatus.CLOSED) {
            log.warn("Deposit rejected: destination account {} is CLOSED", destinationId);
            throw new AccountClosedException("Destination account " + destinationId + " is CLOSED");
        }
        if (lockedDestinationAccount.getStatus() != AccountStatus.ACTIVE) {
            log.warn("Deposit rejected: destination account {} is in status {}", destinationId, lockedDestinationAccount.getStatus());
            throw new AccountStatusException("Destination account " + destinationId + " is not ACTIVE");
        }

        if (lockedClearingAccount.getStatus() != AccountStatus.ACTIVE) {
            log.warn("Deposit rejected: system clearing account {} is in status {}", clearingId, lockedClearingAccount.getStatus());
            throw new AccountStatusException("System clearing account " + clearingId + " is not ACTIVE");
        }

        // 11. Destination Account Currency Validation:
        if (!lockedDestinationAccount.getCurrency().equalsIgnoreCase(currency)) {
            throw new CurrencyMismatchException(
                    "Currency mismatch: deposit currency '" + currency + "' does not match destination account currency '" + lockedDestinationAccount.getCurrency() + "'"
            );
        }

        // 12. System Clearing Balance Validation:
        // Clearing balance must be sufficient; clearing account must never become negative.
        if (lockedClearingAccount.getBalance().compareTo(scaledAmount) < 0) {
            log.warn("Deposit rejected: insufficient balance in clearing account {}: available {}, required {}",
                    clearingId, lockedClearingAccount.getBalance(), scaledAmount);
            throw new InsufficientBalanceException(
                    "Insufficient balance in system clearing account: available "
                            + lockedClearingAccount.getBalance() + ", required " + scaledAmount
            );
        }

        // 13. Financial Mutation: Debit SYSTEM_CLEARING and Credit USER_CHECKING
        lockedClearingAccount.setBalance(lockedClearingAccount.getBalance().subtract(scaledAmount));
        lockedDestinationAccount.setBalance(lockedDestinationAccount.getBalance().add(scaledAmount));

        accountRepository.save(lockedClearingAccount);
        accountRepository.save(lockedDestinationAccount);

        // 14. Create Transaction record (PENDING)
        Transaction transaction = new Transaction(
                cleanIdempotencyKey,
                scaledAmount,
                currency,
                TransactionStatus.PENDING,
                lockedClearingAccount,
                lockedDestinationAccount,
                TransactionType.DEPOSIT,
                authenticatedUser,
                cleanDescription
        );
        transaction = transactionRepository.save(transaction);

        // 15. Create balanced double-entry ledger entries:
        // DEBIT on SYSTEM_CLEARING, CREDIT on USER_CHECKING
        LedgerEntry debitEntry = new LedgerEntry(
                transaction,
                lockedClearingAccount,
                LedgerEntryType.DEBIT,
                scaledAmount,
                currency
        );
        LedgerEntry creditEntry = new LedgerEntry(
                transaction,
                lockedDestinationAccount,
                LedgerEntryType.CREDIT,
                scaledAmount,
                currency
        );

        ledgerEntryRepository.save(debitEntry);
        ledgerEntryRepository.save(creditEntry);

        // 16. Verify double-entry ledger balance invariant (totalDebits == totalCredits)
        BigDecimal totalDebits = debitEntry.getAmount();
        BigDecimal totalCredits = creditEntry.getAmount();
        if (totalDebits.compareTo(totalCredits) != 0) {
            throw new UnbalancedLedgerException(
                    "Double-entry ledger invariant violation: total debits (" + totalDebits
                            + ") do not equal total credits (" + totalCredits + ")"
            );
        }

        // 17. Complete transaction
        transaction.setStatus(TransactionStatus.COMPLETED);
        transaction.setCompletedAt(Instant.now());
        transaction = transactionRepository.save(transaction);

        log.info("Successfully executed deposit: txId={}, amount={} {}, clearing={} to userAccount={}",
                transaction.getId(), scaledAmount, currency, clearingId, destinationId);

        TransactionResponseDto response = TransactionResponseDto.from(transaction);

        // 18. Store successful response in Redis ONLY after the PostgreSQL transaction commits
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

        return new DepositResult(response, false);
    }

    private DepositResult handleExistingTransaction(
            Transaction existing,
            DepositRequestDto request,
            BigDecimal scaledAmount,
            String currency,
            String cleanDescription,
            String idempotencyKey,
            User authenticatedUser) {

        boolean sameType = existing.getTransactionType() == TransactionType.DEPOSIT;
        boolean sameDest = existing.getDestinationAccount().getId().equals(request.accountId());
        boolean sameAmount = existing.getAmount().compareTo(scaledAmount) == 0;
        boolean sameCurrency = existing.getCurrency().equalsIgnoreCase(currency);
        boolean sameDesc = Objects.equals(existing.getDescription(), cleanDescription);

        if (sameType && sameDest && sameAmount && sameCurrency && sameDesc) {
            // Verify destination account ownership on database idempotency retry
            if (existing.getDestinationAccount().getUser() == null ||
                    !existing.getDestinationAccount().getUser().getId().equals(authenticatedUser.getId())) {
                log.warn("Unauthorized attempt to access existing deposit {} for account {} by user {}",
                        existing.getId(), existing.getDestinationAccount().getId(), authenticatedUser.getId());
                throw new AccountNotFoundException("Account not found: " + request.accountId());
            }

            log.info("Idempotent retry detected for key '{}'. Returning existing transaction {}",
                    idempotencyKey, existing.getId());
            TransactionResponseDto response = TransactionResponseDto.from(existing);
            try {
                idempotencyCacheService.set(idempotencyKey, response);
            } catch (Exception e) {
                log.warn("Failed to repopulate Redis cache for key '{}': {}", idempotencyKey, e.getMessage());
            }
            return new DepositResult(response, true);
        } else {
            log.warn("Idempotency conflict for key '{}'", idempotencyKey);
            throw new IdempotencyConflictException(
                    "Idempotency key '" + idempotencyKey + "' was already used for a transaction with different parameters"
            );
        }
    }
}
