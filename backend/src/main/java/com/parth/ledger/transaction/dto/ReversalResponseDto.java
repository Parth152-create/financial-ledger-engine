package com.parth.ledger.transaction.dto;

import com.parth.ledger.transaction.Transaction;
import com.parth.ledger.transaction.TransactionStatus;
import com.parth.ledger.transaction.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO representing a completed compensating reversal transaction.
 */
public record ReversalResponseDto(
        UUID reversalTransactionId,
        UUID originalTransactionId,
        TransactionType transactionType,
        TransactionStatus status,
        BigDecimal amount,
        String currency,
        String reason,
        Instant createdAt,
        Instant completedAt
) {
    public static ReversalResponseDto from(Transaction reversalTx) {
        return new ReversalResponseDto(
                reversalTx.getId(),
                reversalTx.getReversesTransactionId(),
                reversalTx.getTransactionType(),
                reversalTx.getStatus(),
                reversalTx.getAmount(),
                reversalTx.getCurrency(),
                reversalTx.getDescription(),
                reversalTx.getCreatedAt(),
                reversalTx.getCompletedAt()
        );
    }
}
