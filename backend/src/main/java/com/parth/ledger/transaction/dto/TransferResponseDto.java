package com.parth.ledger.transaction.dto;

import com.parth.ledger.transaction.Transaction;
import com.parth.ledger.transaction.TransactionStatus;

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
        Instant completedAt
) {
    public static TransferResponseDto from(Transaction tx) {
        return new TransferResponseDto(
                tx.getId(),
                tx.getStatus(),
                tx.getSourceAccount().getId(),
                tx.getDestinationAccount().getId(),
                tx.getAmount(),
                tx.getCurrency(),
                tx.getCreatedAt(),
                tx.getCompletedAt()
        );
    }
}
