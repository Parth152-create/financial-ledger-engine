package com.parth.ledger.transaction.dto;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Paginated response structure for account transaction history.
 */
public record TransactionHistoryPageResponseDto(
        List<TransactionHistoryItemDto> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {
    public static TransactionHistoryPageResponseDto from(Page<TransactionHistoryItemDto> page) {
        return new TransactionHistoryPageResponseDto(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast()
        );
    }
}
