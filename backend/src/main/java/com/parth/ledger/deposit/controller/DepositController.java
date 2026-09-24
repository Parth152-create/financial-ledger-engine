package com.parth.ledger.deposit.controller;

import com.parth.ledger.deposit.dto.DepositRequestDto;
import com.parth.ledger.deposit.dto.DepositResult;
import com.parth.ledger.deposit.service.DepositService;
import com.parth.ledger.transaction.dto.TransactionResponseDto;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller exposing deposit endpoints for funding user checking accounts.
 *
 * All operations require user authentication and an Idempotency-Key HTTP header.
 */
@RestController
@RequestMapping("/api/v1/deposits")
public class DepositController {

    private final DepositService depositService;

    public DepositController(DepositService depositService) {
        this.depositService = depositService;
    }

    /**
     * Executes a deposit into a destination user checking account.
     *
     * @param idempotencyKey Client-supplied idempotency key from header.
     * @param request Deposit payload containing destination accountId, amount, currency, and optional description.
     * @return HTTP 201 Created for new deposit, or HTTP 200 OK for identical idempotent replay.
     */
    @PostMapping
    public ResponseEntity<TransactionResponseDto> deposit(
            @RequestHeader(value = "Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody DepositRequestDto request
    ) {
        if (idempotencyKey == null || idempotencyKey.trim().isEmpty()) {
            throw new IllegalArgumentException("Idempotency-Key header must not be blank");
        }

        DepositResult result = depositService.processDeposit(idempotencyKey, request);
        if (result.replayed()) {
            return ResponseEntity.ok(result.response());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(result.response());
    }
}
