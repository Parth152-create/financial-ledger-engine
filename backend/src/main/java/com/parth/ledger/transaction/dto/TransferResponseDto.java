package com.parth.ledger.transaction.dto;

import com.parth.ledger.transaction.Transaction;
import com.parth.ledger.transaction.TransactionStatus;
import com.parth.ledger.transaction.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransferResponseDto(
        UUID transactionId,
        TransactionStatus status,
        UUID sourceAccountId,
        UUID destinationAccountId,
        BigDecimal amount,
        String currency,
        Instant createdAt,
        Instant completedAt,
        TransactionType transactionType,
        UUID initiatedByUserId,
        String description
) {
    public TransferResponseDto(
            UUID transactionId,
            TransactionStatus status,
            UUID sourceAccountId,
            UUID destinationAccountId,
            BigDecimal amount,
            String currency,
            Instant createdAt,
            Instant completedAt
    ) {
        this(transactionId, status, sourceAccountId, destinationAccountId, amount, currency, createdAt, completedAt, TransactionType.TRANSFER, null, null);
    }

    public static TransferResponseDto from(Transaction tx) {
        return new TransferResponseDto(
                tx.getId(),
                tx.getStatus(),
                tx.getSourceAccount().getId(),
                tx.getDestinationAccount().getId(),
                tx.getAmount(),
                tx.getCurrency(),
                tx.getCreatedAt(),
                tx.getCompletedAt(),
                tx.getTransactionType(),
                tx.getInitiatedByUser() != null ? tx.getInitiatedByUser().getId() : null,
                tx.getDescription()
        );
    }
}
