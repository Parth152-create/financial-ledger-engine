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

    public WithdrawalService(AccountRepository accountRepository,
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
            log.warn("Error accessing Redis idempotency cache for key '{}': {}. Failing open to PostgreSQL.",
                    cleanIdempotencyKey, e.getMessage());
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
                            request.accountId(), authenticatedUser.getId());
                    throw new AccountNotFoundException("Account not found: " + request.accountId());
                }
                log.info("Redis idempotency fast-path hit for key '{}'. Returning cached transaction {}",
                        cleanIdempotencyKey, cached.transactionId());
                return new WithdrawalResult(cached, true);
            } else {
                log.warn("Idempotency conflict detected in Redis cache for key '{}'", cleanIdempotencyKey);
                throw new IdempotencyConflictException(
                        "Idempotency key '" + cleanIdempotencyKey + "' was already used for a transaction with different parameters"
                );
            }
        }

        Optional<Transaction> existingTx = transactionRepository.findByIdempotencyKey(cleanIdempotencyKey);
        if (existingTx.isPresent()) {
            return handleExistingTransaction(existingTx.get(), request, scaledAmount, currency, cleanDescription, cleanIdempotencyKey, authenticatedUser);
        }

        UUID sourceId = request.accountId();
        UUID clearingId = SYSTEM_CLEARING_ACCOUNT_ID;

        if (clearingId.equals(sourceId)) {
            log.warn("Withdrawal rejected: attempted to use SYSTEM_CLEARING account {} as source", sourceId);
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
            return handleExistingTransaction(txAfterLock.get(), request, scaledAmount, currency, cleanDescription, cleanIdempotencyKey, authenticatedUser);
        }

        if (lockedClearingAccount.getAccountType() != AccountType.SYSTEM_CLEARING) {
            throw new IllegalStateException("Configured clearing account " + clearingId + " is not of type SYSTEM_CLEARING");
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
            throw new AccountNotFoundException("Account not found: " + sourceId);
        }
        if (lockedSourceAccount.getUser() == null || !lockedSourceAccount.getUser().getId().equals(authenticatedUser.getId())) {
            throw new AccountNotFoundException("Account not found: " + sourceId);
        }

        if (lockedSourceAccount.getStatus() == AccountStatus.FROZEN) {
            throw new AccountFrozenException("Source account " + sourceId + " is FROZEN");
        }
        if (lockedSourceAccount.getStatus() == AccountStatus.CLOSED) {
            throw new AccountClosedException("Source account " + sourceId + " is CLOSED");
        }
        if (lockedSourceAccount.getStatus() != AccountStatus.ACTIVE) {
            throw new AccountStatusException("Source account " + sourceId + " is not ACTIVE");
        }

        if (!lockedSourceAccount.getCurrency().equalsIgnoreCase(currency)) {
            throw new CurrencyMismatchException(
                    "Currency mismatch: withdrawal currency '" + currency + "' does not match source account currency '" + lockedSourceAccount.getCurrency() + "'"
            );
        }

        if (lockedSourceAccount.getBalance().compareTo(scaledAmount) < 0) {
            throw new InsufficientBalanceException(
                    "Insufficient balance in source account: available "
                            + lockedSourceAccount.getBalance() + ", required " + scaledAmount
            );
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

        log.info("Successfully executed withdrawal: txId={}, amount={} {}, userAccount={} to clearing={}",
                transaction.getId(), scaledAmount, currency, sourceId, clearingId);

        TransactionResponseDto response = TransactionResponseDto.from(transaction);

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

        return new WithdrawalResult(response, false);
    }

    private WithdrawalResult handleExistingTransaction(
            Transaction existing,
            WithdrawalRequestDto request,
            BigDecimal scaledAmount,
            String currency,
            String cleanDescription,
            String idempotencyKey,
            User authenticatedUser) {

        boolean sameType = existing.getTransactionType() == TransactionType.WITHDRAWAL;
        boolean sameSource = existing.getSourceAccount().getId().equals(request.accountId());
        boolean sameAmount = existing.getAmount().compareTo(scaledAmount) == 0;
        boolean sameCurrency = existing.getCurrency().equalsIgnoreCase(currency);
        boolean sameDesc = Objects.equals(existing.getDescription(), cleanDescription);

        if (sameType && sameSource && sameAmount && sameCurrency && sameDesc) {
            if (existing.getSourceAccount().getUser() == null ||
                    !existing.getSourceAccount().getUser().getId().equals(authenticatedUser.getId())) {
                log.warn("Unauthorized attempt to access existing withdrawal {} for account {} by user {}",
                        existing.getId(), existing.getSourceAccount().getId(), authenticatedUser.getId());
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
            return new WithdrawalResult(response, true);
        } else {
            log.warn("Idempotency conflict for key '{}'", idempotencyKey);
            throw new IdempotencyConflictException(
                    "Idempotency key '" + idempotencyKey + "' was already used for a transaction with different parameters"
            );
        }
    }
}
