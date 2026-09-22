package com.parth.ledger.transaction.service;

import com.parth.ledger.account.Account;
import com.parth.ledger.account.AccountNotFoundException;
import com.parth.ledger.account.AccountRepository;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public TransferService(AccountRepository accountRepository,
                           TransactionRepository transactionRepository,
                           LedgerEntryRepository ledgerEntryRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
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

        // 6. Pre-lock Idempotency Check: Fast return for committed retries or conflict detection
        Optional<Transaction> existingTx = transactionRepository.findByIdempotencyKey(cleanIdempotencyKey);
        if (existingTx.isPresent()) {
            return handleExistingTransaction(existingTx.get(), request, scaledAmount, currency, cleanIdempotencyKey);
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
            return handleExistingTransaction(txAfterLock.get(), request, scaledAmount, currency, cleanIdempotencyKey);
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

        // 18. Return transaction result DTO
        return TransferResponseDto.from(transaction);
    }

    private TransferResponseDto handleExistingTransaction(
            Transaction existing,
            TransferRequestDto request,
            BigDecimal scaledAmount,
            String currency,
            String idempotencyKey) {

        boolean sameSource = existing.getSourceAccount().getId().equals(request.sourceAccountId());
        boolean sameDest = existing.getDestinationAccount().getId().equals(request.destinationAccountId());
        boolean sameAmount = existing.getAmount().compareTo(scaledAmount) == 0;
        boolean sameCurrency = existing.getCurrency().equalsIgnoreCase(currency);

        if (sameSource && sameDest && sameAmount && sameCurrency) {
            log.info("Idempotent retry detected for key '{}'. Returning existing transaction {}",
                    idempotencyKey, existing.getId());
            return TransferResponseDto.from(existing);
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
