package com.parth.ledger.recurring.dto;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Paginated response container for recurring transfer execution history.
 */
public record RecurringExecutionPageResponseDto(
        List<RecurringExecutionResponseDto> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {
    public static RecurringExecutionPageResponseDto from(Page<RecurringExecutionResponseDto> page) {
        return new RecurringExecutionPageResponseDto(
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
