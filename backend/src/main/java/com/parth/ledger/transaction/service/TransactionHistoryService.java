package com.parth.ledger.transaction.service;

import com.parth.ledger.account.Account;
import com.parth.ledger.account.AccountNotFoundException;
import com.parth.ledger.account.AccountRepository;
import com.parth.ledger.account.AccountType;
import com.parth.ledger.security.AuthenticatedUserService;
import com.parth.ledger.transaction.Transaction;
import com.parth.ledger.transaction.TransactionRepository;
import com.parth.ledger.transaction.TransactionStatus;
import com.parth.ledger.transaction.TransactionType;
import com.parth.ledger.transaction.dto.TransactionHistoryItemDto;
import com.parth.ledger.transaction.dto.TransactionHistoryPageResponseDto;
import com.parth.ledger.transaction.specification.TransactionSpecifications;
import com.parth.ledger.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.UUID;

/**
 * Read-only service providing paginated and filtered transaction history for user accounts.
 *
 * Enforces ownership invariants, strict deterministic sorting (createdAt DESC, id DESC),
 * input validation, and account-relative direction calculation without mutating ledger or
 * account state.
 */
@Service
@Transactional(readOnly = true)
public class TransactionHistoryService {

    private static final Logger log = LoggerFactory.getLogger(TransactionHistoryService.class);

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final AuthenticatedUserService authenticatedUserService;

    public TransactionHistoryService(AccountRepository accountRepository,
                                     TransactionRepository transactionRepository,
                                     AuthenticatedUserService authenticatedUserService) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.authenticatedUserService = authenticatedUserService;
    }

    /**
     * Retrieves a paginated list of transactions associated with the requested account.
     *
     * @param accountId          ID of the USER_CHECKING account owned by the caller.
     * @param transactionTypeStr Optional filter for transaction type (TRANSFER, DEPOSIT, WITHDRAWAL).
     * @param statusStr          Optional filter for transaction status (PENDING, COMPLETED, FAILED).
     * @param fromStr            Optional ISO-8601 inclusive start boundary.
     * @param toStr              Optional ISO-8601 exclusive end boundary.
     * @param page               Zero-based page index (must be &gt;= 0).
     * @param size               Page size (must be between 1 and 100 inclusive).
     * @return Paginated transaction history response.
     */
    public TransactionHistoryPageResponseDto getTransactionHistory(
            UUID accountId,
            String transactionTypeStr,
            String statusStr,
            String fromStr,
            String toStr,
            int page,
            int size
    ) {
        if (accountId == null) {
            throw new IllegalArgumentException("Account ID must not be null");
        }

        // Validate pagination parameters
        if (page < 0) {
            throw new IllegalArgumentException("Page index must not be negative: " + page);
        }
        if (size < 1) {
            throw new IllegalArgumentException("Page size must be at least 1: " + size);
        }
        if (size > 100) {
            throw new IllegalArgumentException("Page size must not exceed 100: " + size);
        }

        // Parse and validate optional filters
        TransactionType transactionType = parseTransactionType(transactionTypeStr);
        TransactionStatus status = parseTransactionStatus(statusStr);
        Instant from = parseInstant(fromStr, "from");
        Instant to = parseInstant(toStr, "to");

        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("'from' timestamp (" + fromStr + ") must be before or equal to 'to' timestamp (" + toStr + ")");
        }

        // Resolve current authenticated user
        User currentUser = authenticatedUserService.getCurrentUser();

        // Enforce account ownership and type:
        // Must exist, belong to authenticated user, and be a USER_CHECKING account.
        // Other users' accounts and SYSTEM_CLEARING return AccountNotFoundException (404 Not Found)
        // to prevent information leakage / account enumeration.
        Account account = accountRepository.findByIdAndUserIdAndAccountType(
                accountId,
                currentUser.getId(),
                AccountType.USER_CHECKING
        ).orElseThrow(() -> {
            log.warn("Account {} not found or unauthorized for user {}", accountId, currentUser.getId());
            return new AccountNotFoundException("Account not found: " + accountId);
        });

        // Enforce deterministic sorting: createdAt DESC, id DESC
        Pageable pageable = PageRequest.of(
                page,
                size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))
        );

        Specification<Transaction> spec = TransactionSpecifications.forAccountWithFilters(
                account.getId(),
                transactionType,
                status,
                from,
                to
        );

        Page<Transaction> transactionPage = transactionRepository.findAll(spec, pageable);

        Page<TransactionHistoryItemDto> dtoPage = transactionPage.map(
                tx -> TransactionHistoryItemDto.from(tx, account.getId())
        );

        return TransactionHistoryPageResponseDto.from(dtoPage);
    }

    private TransactionType parseTransactionType(String typeStr) {
        if (typeStr == null || typeStr.isBlank()) {
            return null;
        }
        try {
            return TransactionType.valueOf(typeStr.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid transaction type: " + typeStr);
        }
    }

    private TransactionStatus parseTransactionStatus(String statusStr) {
        if (statusStr == null || statusStr.isBlank()) {
            return null;
        }
        try {
            return TransactionStatus.valueOf(statusStr.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid transaction status: " + statusStr);
        }
    }

    private Instant parseInstant(String instantStr, String paramName) {
        if (instantStr == null || instantStr.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(instantStr.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid ISO-8601 '" + paramName + "' timestamp: " + instantStr);
        }
    }
}
