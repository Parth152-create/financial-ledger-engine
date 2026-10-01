package com.parth.ledger.outbox.dto;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Paginated response container for administrative outbox queries.
 */
public record OutboxPageResponseDto(
        List<OutboxEventSummaryResponseDto> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {
    public static OutboxPageResponseDto from(Page<OutboxEventSummaryResponseDto> page) {
        return new OutboxPageResponseDto(
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
