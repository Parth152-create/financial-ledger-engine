package com.parth.ledger.withdrawal.controller;

import com.parth.ledger.transaction.dto.TransactionResponseDto;
import com.parth.ledger.withdrawal.dto.WithdrawalRequestDto;
import com.parth.ledger.withdrawal.dto.WithdrawalResult;
import com.parth.ledger.withdrawal.service.WithdrawalService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/withdrawals")
public class WithdrawalController {

    private final WithdrawalService withdrawalService;

    public WithdrawalController(WithdrawalService withdrawalService) {
        this.withdrawalService = withdrawalService;
    }

    @PostMapping
    public ResponseEntity<TransactionResponseDto> withdraw(
            @RequestHeader(value = "Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody WithdrawalRequestDto request
    ) {
        if (idempotencyKey == null || idempotencyKey.trim().isEmpty()) {
            throw new IllegalArgumentException("Idempotency-Key header must not be blank");
        }

        WithdrawalResult result = withdrawalService.processWithdrawal(idempotencyKey, request);
        if (result.replayed()) {
            return ResponseEntity.ok(result.response());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(result.response());
    }
}
