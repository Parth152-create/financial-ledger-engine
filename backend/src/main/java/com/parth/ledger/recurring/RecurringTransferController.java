package com.parth.ledger.recurring;

import com.parth.ledger.recurring.dto.CreateRecurringTransferRequestDto;
import com.parth.ledger.recurring.dto.RecurringExecutionPageResponseDto;
import com.parth.ledger.recurring.dto.RecurringTransferPageResponseDto;
import com.parth.ledger.recurring.dto.RecurringTransferResponseDto;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST controller for recurring transfer schedule management.
 */
@RestController
@RequestMapping("/api/v1/recurring-transfers")
public class RecurringTransferController {

    private final RecurringTransferService recurringTransferService;

    public RecurringTransferController(RecurringTransferService recurringTransferService) {
        this.recurringTransferService = recurringTransferService;
    }

    /**
     * Creates a new recurring transfer schedule.
     * Does NOT execute a financial transfer or alter account balances.
     */
    @PostMapping
    public ResponseEntity<RecurringTransferResponseDto> createRecurringTransfer(
            @Valid @RequestBody CreateRecurringTransferRequestDto request) {
        RecurringTransferResponseDto response = recurringTransferService.createRecurringTransfer(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Lists paginated recurring transfer schedules owned by the authenticated user.
     */
    @GetMapping
    public ResponseEntity<RecurringTransferPageResponseDto> listSchedules(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "status", required = false) RecurringTransferStatus status) {
        int boundedPage = Math.max(page, 0);
        int boundedSize = Math.clamp(size, 1, 100);
        PageRequest pageRequest = PageRequest.of(boundedPage, boundedSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        RecurringTransferPageResponseDto response = recurringTransferService.listUserSchedules(pageRequest, status);
        return ResponseEntity.ok(response);
    }

    /**
     * Retrieves schedule details by ID if owned by the caller (or admin).
     */
    @GetMapping("/{id}")
    public ResponseEntity<RecurringTransferResponseDto> getSchedule(@PathVariable("id") UUID id) {
        RecurringTransferResponseDto response = recurringTransferService.getSchedule(id);
        return ResponseEntity.ok(response);
    }

    /**
     * Pauses an active schedule.
     */
    @PostMapping("/{id}/pause")
    public ResponseEntity<RecurringTransferResponseDto> pauseSchedule(@PathVariable("id") UUID id) {
        RecurringTransferResponseDto response = recurringTransferService.pauseSchedule(id);
        return ResponseEntity.ok(response);
    }

    /**
     * Resumes a paused schedule.
     */
    @PostMapping("/{id}/resume")
    public ResponseEntity<RecurringTransferResponseDto> resumeSchedule(@PathVariable("id") UUID id) {
        RecurringTransferResponseDto response = recurringTransferService.resumeSchedule(id);
        return ResponseEntity.ok(response);
    }

    /**
     * Cancels a schedule. Cancellation is permanent and terminal.
     */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<RecurringTransferResponseDto> cancelSchedule(@PathVariable("id") UUID id) {
        RecurringTransferResponseDto response = recurringTransferService.cancelSchedule(id);
        return ResponseEntity.ok(response);
    }

    /**
     * Retrieves paginated execution history for an owned schedule.
     */
    @GetMapping("/{id}/executions")
    public ResponseEntity<RecurringExecutionPageResponseDto> listExecutions(
            @PathVariable("id") UUID id,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        int boundedPage = Math.max(page, 0);
        int boundedSize = Math.clamp(size, 1, 100);
        PageRequest pageRequest = PageRequest.of(boundedPage, boundedSize);
        RecurringExecutionPageResponseDto response = recurringTransferService.listExecutions(id, pageRequest);
        return ResponseEntity.ok(response);
    }
}
