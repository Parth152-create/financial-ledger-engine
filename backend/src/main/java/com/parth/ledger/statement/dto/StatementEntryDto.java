package com.parth.ledger.statement.dto;

import com.parth.ledger.transaction.TransactionStatus;
import com.parth.ledger.transaction.TransactionType;
import com.parth.ledger.transaction.dto.TransactionDirection;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record StatementEntryDto(
        UUID transactionId,
        TransactionType transactionType,
        TransactionDirection direction,
        BigDecimal amount,
        String currency,
        String description,
        TransactionStatus status,
        Instant createdAt,
        Instant completedAt,
        BigDecimal balanceAfter
) {
}
