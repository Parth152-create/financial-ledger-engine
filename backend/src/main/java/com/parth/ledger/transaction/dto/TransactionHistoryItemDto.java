package com.parth.ledger.transaction.dto;

import com.parth.ledger.transaction.Transaction;
import com.parth.ledger.transaction.TransactionStatus;
import com.parth.ledger.transaction.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Account-relative transaction history item DTO.
 * Exposes core transaction attributes and calculates financial flow direction (DEBIT or CREDIT)
 * relative to the requested account.
 */
public record TransactionHistoryItemDto(
        UUID transactionId,
        TransactionType transactionType,
        TransactionDirection direction,
        UUID sourceAccountId,
        UUID destinationAccountId,
        BigDecimal amount,
        String currency,
        String description,
        TransactionStatus status,
        UUID initiatedByUserId,
        Instant createdAt,
        Instant completedAt
) {
    public static TransactionHistoryItemDto from(Transaction tx, UUID requestedAccountId) {
        TransactionDirection direction = tx.getSourceAccount().getId().equals(requestedAccountId)
                ? TransactionDirection.DEBIT
                : TransactionDirection.CREDIT;

        return new TransactionHistoryItemDto(
                tx.getId(),
                tx.getTransactionType(),
                direction,
                tx.getSourceAccount().getId(),
                tx.getDestinationAccount().getId(),
                tx.getAmount(),
                tx.getCurrency(),
                tx.getDescription(),
                tx.getStatus(),
                tx.getInitiatedByUser() != null ? tx.getInitiatedByUser().getId() : null,
                tx.getCreatedAt(),
                tx.getCompletedAt()
        );
    }
}
