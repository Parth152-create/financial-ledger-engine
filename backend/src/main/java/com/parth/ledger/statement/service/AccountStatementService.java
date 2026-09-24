package com.parth.ledger.statement.service;

import com.parth.ledger.account.Account;
import com.parth.ledger.account.AccountNotFoundException;
import com.parth.ledger.account.AccountRepository;
import com.parth.ledger.account.AccountType;
import com.parth.ledger.security.AuthenticatedUserService;
import com.parth.ledger.statement.dto.AccountStatementResponseDto;
import com.parth.ledger.statement.dto.StatementEntryDto;
import com.parth.ledger.transaction.TransactionStatus;
import com.parth.ledger.transaction.TransactionType;
import com.parth.ledger.transaction.dto.TransactionDirection;
import com.parth.ledger.user.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class AccountStatementService {

    private final AccountRepository accountRepository;
    private final AuthenticatedUserService authenticatedUserService;
    private final EntityManager entityManager;

    public AccountStatementService(AccountRepository accountRepository,
                                   AuthenticatedUserService authenticatedUserService,
                                   EntityManager entityManager) {
        this.accountRepository = accountRepository;
        this.authenticatedUserService = authenticatedUserService;
        this.entityManager = entityManager;
    }

    public AccountStatementResponseDto getStatement(
            UUID accountId,
            String fromStr,
            String toStr,
            String transactionTypeStr,
            String statusStr,
            int page,
            int size
    ) {
        if (accountId == null) {
            throw new IllegalArgumentException("Account ID must not be null");
        }
        if (page < 0) {
            throw new IllegalArgumentException("Page index must not be negative: " + page);
        }
        if (size < 1) {
            throw new IllegalArgumentException("Page size must be at least 1: " + size);
        }
        if (size > 100) {
            throw new IllegalArgumentException("Page size must not exceed 100: " + size);
        }

        Instant from = parseInstant(fromStr, "from");
        Instant to = parseInstant(toStr, "to");

        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("'from' timestamp (" + fromStr + ") must be before or equal to 'to' timestamp (" + toStr + ")");
        }

        TransactionType txType = parseTransactionType(transactionTypeStr);
        TransactionStatus txStatus = parseTransactionStatus(statusStr);

        User currentUser = authenticatedUserService.getCurrentUser();
        Account account = accountRepository.findByIdAndUserIdAndAccountType(
                accountId,
                currentUser.getId(),
                AccountType.USER_CHECKING
        ).orElseThrow(() -> new AccountNotFoundException("Account not found: " + accountId));

        BigDecimal openingBalance = calculateOpeningBalance(account.getId(), from);
        BigDecimal closingBalance = calculateClosingBalance(account.getId(), to);

        if (from != null && to != null && from.equals(to)) {
            return new AccountStatementResponseDto(
                    account.getId(),
                    account.getAccountNumber(),
                    account.getCurrency(),
                    openingBalance,
                    Collections.emptyList(),
                    closingBalance,
                    BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP),
                    BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP),
                    page,
                    size,
                    0L,
                    0,
                    true,
                    true
            );
        }

        TotalsResult totals = calculateTotals(account.getId(), from, to, txType, txStatus);
        int totalPages = size == 0 ? 0 : (int) Math.ceil((double) totals.totalElements() / size);

        int offset = page * size;
        if (totals.totalElements() == 0 || offset >= totals.totalElements()) {
            return new AccountStatementResponseDto(
                    account.getId(),
                    account.getAccountNumber(),
                    account.getCurrency(),
                    openingBalance,
                    Collections.emptyList(),
                    closingBalance,
                    totals.totalCredits(),
                    totals.totalDebits(),
                    page,
                    size,
                    totals.totalElements(),
                    totalPages,
                    page == 0,
                    page >= totalPages - 1
            );
        }

        List<StatementEntryDto> statementEntries = fetchPageEntries(
                account, from, to, txType, txStatus, offset, size
        );

        return new AccountStatementResponseDto(
                account.getId(),
                account.getAccountNumber(),
                account.getCurrency(),
                openingBalance,
                statementEntries,
                closingBalance,
                totals.totalCredits(),
                totals.totalDebits(),
                page,
                size,
                totals.totalElements(),
                totalPages,
                page == 0,
                page >= totalPages - 1
        );
    }

    private BigDecimal calculateOpeningBalance(UUID accountId, Instant from) {
        if (from == null) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }

        String sql = """
            SELECT COALESCE(SUM(CASE WHEN le.entry_type = 'CREDIT' THEN le.amount ELSE -le.amount END), 0)
            FROM ledger_entries le
            WHERE le.account_id = :accountId
              AND le.created_at < :from
        """;
        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("accountId", accountId);
        query.setParameter("from", Timestamp.from(from));
        return toBigDecimal(query.getSingleResult()).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateClosingBalance(UUID accountId, Instant to) {
        StringBuilder sql = new StringBuilder("""
            SELECT COALESCE(SUM(CASE WHEN le.entry_type = 'CREDIT' THEN le.amount ELSE -le.amount END), 0)
            FROM ledger_entries le
            WHERE le.account_id = :accountId
        """);

        if (to != null) {
            sql.append(" AND le.created_at < :to");
        }

        Query query = entityManager.createNativeQuery(sql.toString());
        query.setParameter("accountId", accountId);
        if (to != null) {
            query.setParameter("to", Timestamp.from(to));
        }
        return toBigDecimal(query.getSingleResult()).setScale(4, RoundingMode.HALF_UP);
    }

    private TotalsResult calculateTotals(UUID accountId, Instant from, Instant to, TransactionType txType, TransactionStatus txStatus) {
        StringBuilder sql = new StringBuilder("""
            SELECT
                COALESCE(SUM(CASE WHEN le.entry_type = 'CREDIT' THEN le.amount ELSE 0 END), 0) AS total_credits,
                COALESCE(SUM(CASE WHEN le.entry_type = 'DEBIT' THEN le.amount ELSE 0 END), 0) AS total_debits,
                COUNT(le.id) AS total_elements
            FROM ledger_entries le
            JOIN transactions t ON le.transaction_id = t.id
            WHERE le.account_id = :accountId
        """);

        if (from != null) {
            sql.append(" AND le.created_at >= :from");
        }
        if (to != null) {
            sql.append(" AND le.created_at < :to");
        }
        if (txType != null) {
            sql.append(" AND t.transaction_type = :txType");
        }
        if (txStatus != null) {
            sql.append(" AND t.status = :txStatus");
        }

        Query query = entityManager.createNativeQuery(sql.toString());
        query.setParameter("accountId", accountId);
        if (from != null) {
            query.setParameter("from", Timestamp.from(from));
        }
        if (to != null) {
            query.setParameter("to", Timestamp.from(to));
        }
        if (txType != null) {
            query.setParameter("txType", txType.name());
        }
        if (txStatus != null) {
            query.setParameter("txStatus", txStatus.name());
        }

        Object[] result = (Object[]) query.getSingleResult();
        BigDecimal totalCredits = toBigDecimal(result[0]).setScale(4, RoundingMode.HALF_UP);
        BigDecimal totalDebits = toBigDecimal(result[1]).setScale(4, RoundingMode.HALF_UP);
        long totalElements = ((Number) result[2]).longValue();

        return new TotalsResult(totalCredits, totalDebits, totalElements);
    }

    private List<StatementEntryDto> fetchPageEntries(
            Account account,
            Instant from,
            Instant to,
            TransactionType txType,
            TransactionStatus txStatus,
            int offset,
            int size
    ) {
        StringBuilder sql = new StringBuilder("""
            SELECT
                le.id AS ledger_id,
                le.entry_type,
                le.amount,
                le.currency,
                t.id AS tx_id,
                t.transaction_type,
                t.status,
                t.description,
                t.created_at AS tx_created_at,
                t.completed_at AS tx_completed_at,
                (
                    SELECT COALESCE(SUM(CASE WHEN prev.entry_type = 'CREDIT' THEN prev.amount ELSE -prev.amount END), 0)
                    FROM ledger_entries prev
                    WHERE prev.account_id = le.account_id
                      AND (prev.created_at < le.created_at OR (prev.created_at = le.created_at AND prev.id <= le.id))
                ) AS balance_after
            FROM ledger_entries le
            JOIN transactions t ON le.transaction_id = t.id
            WHERE le.account_id = :accountId
        """);

        if (from != null) {
            sql.append(" AND le.created_at >= :from");
        }
        if (to != null) {
            sql.append(" AND le.created_at < :to");
        }
        if (txType != null) {
            sql.append(" AND t.transaction_type = :txType");
        }
        if (txStatus != null) {
            sql.append(" AND t.status = :txStatus");
        }

        sql.append(" ORDER BY le.created_at ASC, le.id ASC LIMIT :size OFFSET :offset");

        Query query = entityManager.createNativeQuery(sql.toString());
        query.setParameter("accountId", account.getId());
        if (from != null) {
            query.setParameter("from", Timestamp.from(from));
        }
        if (to != null) {
            query.setParameter("to", Timestamp.from(to));
        }
        if (txType != null) {
            query.setParameter("txType", txType.name());
        }
        if (txStatus != null) {
            query.setParameter("txStatus", txStatus.name());
        }
        query.setParameter("size", size);
        query.setParameter("offset", offset);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();

        List<StatementEntryDto> statementEntries = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            String entryTypeStr = (String) row[1];
            BigDecimal amount = toBigDecimal(row[2]).setScale(4, RoundingMode.HALF_UP);
            String currency = (String) row[3];
            UUID txId = toUUID(row[4]);
            TransactionType type = TransactionType.valueOf((String) row[5]);
            TransactionStatus status = TransactionStatus.valueOf((String) row[6]);
            String description = (String) row[7];
            Instant createdAt = toInstant(row[8]);
            Instant completedAt = toInstant(row[9]);
            BigDecimal balanceAfter = toBigDecimal(row[10]).setScale(4, RoundingMode.HALF_UP);

            if (!account.getCurrency().equals(currency)) {
                throw new IllegalStateException("Currency mismatch in ledger entry for account " + account.getId());
            }

            TransactionDirection direction = "CREDIT".equals(entryTypeStr)
                    ? TransactionDirection.CREDIT
                    : TransactionDirection.DEBIT;

            statementEntries.add(new StatementEntryDto(
                    txId,
                    type,
                    direction,
                    amount,
                    currency,
                    description,
                    status,
                    createdAt,
                    completedAt,
                    balanceAfter
            ));
        }

        return statementEntries;
    }

    private UUID toUUID(Object obj) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof UUID u) {
            return u;
        }
        return UUID.fromString(obj.toString());
    }

    private Instant toInstant(Object obj) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof Instant inst) {
            return inst;
        }
        if (obj instanceof Timestamp ts) {
            return ts.toInstant();
        }
        if (obj instanceof java.time.temporal.TemporalAccessor ta) {
            return Instant.from(ta);
        }
        return Instant.parse(obj.toString());
    }

    private BigDecimal toBigDecimal(Object obj) {
        if (obj == null) {
            return BigDecimal.ZERO;
        }
        if (obj instanceof BigDecimal bd) {
            return bd;
        }
        return new BigDecimal(obj.toString());
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

    private record TotalsResult(BigDecimal totalCredits, BigDecimal totalDebits, long totalElements) {
    }
}
