package com.parth.ledger.audit.dto;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Paginated response structure for operational audit events.
 */
public record AuditEventPageResponseDto(
        List<AuditEventResponseDto> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {
    public static AuditEventPageResponseDto from(Page<AuditEventResponseDto> page) {
        return new AuditEventPageResponseDto(
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
