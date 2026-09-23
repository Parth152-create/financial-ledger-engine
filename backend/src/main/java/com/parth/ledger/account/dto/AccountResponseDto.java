package com.parth.ledger.account.dto;

import com.parth.ledger.account.Account;
import com.parth.ledger.account.AccountStatus;
import com.parth.ledger.account.AccountType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Public response representation of an account.
 * Exposes core account attributes without leaking internal JPA implementation details.
 *
 * @param accountId Unique identifier of the account.
 * @param accountNumber Unique public human-readable account number.
 * @param accountType Functional classification (e.g., USER_CHECKING).
 * @param status Lifecycle status (e.g., ACTIVE, FROZEN, CLOSED).
 * @param currency ISO 4217 3-letter currency code.
 * @param balance Current snapshot balance (scaled to 4 decimal places).
 * @param createdAt Creation timestamp.
 * @param updatedAt Last update timestamp.
 */
public record AccountResponseDto(
        UUID accountId,
        String accountNumber,
        AccountType accountType,
        AccountStatus status,
        String currency,
        BigDecimal balance,
        Instant createdAt,
        Instant updatedAt
) {
    public static AccountResponseDto from(Account account) {
        if (account == null) {
            return null;
        }
        return new AccountResponseDto(
                account.getId(),
                account.getAccountNumber(),
                account.getAccountType(),
                account.getStatus(),
                account.getCurrency(),
                account.getBalance(),
                account.getCreatedAt(),
                account.getUpdatedAt()
        );
    }
}
