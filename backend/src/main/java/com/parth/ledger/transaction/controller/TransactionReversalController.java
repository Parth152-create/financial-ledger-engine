package com.parth.ledger.transaction.controller;

import com.parth.ledger.transaction.dto.ReversalRequestDto;
import com.parth.ledger.transaction.dto.ReversalResponseDto;
import com.parth.ledger.transaction.dto.TransactionResponseDto;
import com.parth.ledger.transaction.service.TransactionReversalService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST controller for executing and querying transaction reversals.
 *
 * Endpoints:
 * - POST /api/v1/transactions/{transactionId}/reversal : Executes a compensating reversal.
 * - GET  /api/v1/transactions/{transactionId}          : Retrieves transaction details by ID.
 */
@RestController
@RequestMapping("/api/v1/transactions")
public class TransactionReversalController {

    private final TransactionReversalService transactionReversalService;

    public TransactionReversalController(TransactionReversalService transactionReversalService) {
        this.transactionReversalService = transactionReversalService;
    }

    /**
     * Executes a compensating reversal for the specified transaction.
     *
     * @param transactionId  ID of the original transaction to reverse.
     * @param idempotencyKey Required unique idempotency key from header.
     * @param request        Optional reversal request body (e.g. reason).
     * @return 200 OK with ReversalResponseDto.
     */
    @PostMapping("/{transactionId}/reversal")
    public ResponseEntity<ReversalResponseDto> reverseTransaction(
            @PathVariable("transactionId") UUID transactionId,
            @RequestHeader(value = "Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody(required = false) ReversalRequestDto request
    ) {
        if (idempotencyKey == null || idempotencyKey.trim().isEmpty()) {
            throw new IllegalArgumentException("Idempotency-Key header must not be blank");
        }

        ReversalRequestDto safeRequest = request != null ? request : new ReversalRequestDto(null);
        ReversalResponseDto response = transactionReversalService.executeReversal(transactionId, idempotencyKey, safeRequest);
        return ResponseEntity.ok(response);
    }

    /**
     * Retrieves transaction details by ID.
     *
     * @param transactionId ID of the transaction to look up.
     * @return 200 OK with TransactionResponseDto.
     */
    @GetMapping("/{transactionId}")
    public ResponseEntity<TransactionResponseDto> getTransaction(
            @PathVariable("transactionId") UUID transactionId
    ) {
        TransactionResponseDto response = transactionReversalService.getTransaction(transactionId);
        return ResponseEntity.ok(response);
    }
}
