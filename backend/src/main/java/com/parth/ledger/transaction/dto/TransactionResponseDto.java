package com.parth.ledger.transaction.dto;

import com.parth.ledger.transaction.Transaction;
import com.parth.ledger.transaction.TransactionStatus;
import com.parth.ledger.transaction.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Shared transaction response DTO representing completed or idempotent transaction details.
 * Exposes core financial attributes without revealing internal database details.
 */
public record TransactionResponseDto(
        UUID transactionId,
        TransactionType transactionType,
        TransactionStatus status,
        UUID sourceAccountId,
        UUID destinationAccountId,
        BigDecimal amount,
        String currency,
        String description,
        String idempotencyKey,
        UUID initiatedByUserId,
        Instant createdAt,
        Instant completedAt
) {
    public static TransactionResponseDto from(Transaction tx) {
        return new TransactionResponseDto(
                tx.getId(),
                tx.getTransactionType(),
                tx.getStatus(),
                tx.getSourceAccount().getId(),
                tx.getDestinationAccount().getId(),
                tx.getAmount(),
                tx.getCurrency(),
                tx.getDescription(),
                tx.getIdempotencyKey(),
                tx.getInitiatedByUser() != null ? tx.getInitiatedByUser().getId() : null,
                tx.getCreatedAt(),
                tx.getCompletedAt()
        );
    }
}
