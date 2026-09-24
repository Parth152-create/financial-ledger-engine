package com.parth.ledger.deposit.dto;

import com.parth.ledger.transaction.dto.TransactionResponseDto;

/**
 * Result of deposit processing indicating transaction response and whether request was an idempotent replay.
 */
public record DepositResult(
        TransactionResponseDto response,
        boolean replayed
) {
}
