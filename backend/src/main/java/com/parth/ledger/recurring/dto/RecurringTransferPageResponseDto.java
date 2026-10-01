package com.parth.ledger.recurring.dto;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Paginated response container for recurring transfer schedules.
 */
public record RecurringTransferPageResponseDto(
        List<RecurringTransferResponseDto> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {
    public static RecurringTransferPageResponseDto from(Page<RecurringTransferResponseDto> page) {
        return new RecurringTransferPageResponseDto(
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
