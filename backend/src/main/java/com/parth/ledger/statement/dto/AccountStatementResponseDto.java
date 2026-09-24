package com.parth.ledger.statement.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record AccountStatementResponseDto(
        UUID accountId,
        String accountNumber,
        String currency,
        BigDecimal openingBalance,
        List<StatementEntryDto> entries,
        BigDecimal closingBalance,
        BigDecimal totalCredits,
        BigDecimal totalDebits,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {
}
