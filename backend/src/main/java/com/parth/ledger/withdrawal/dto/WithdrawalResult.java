package com.parth.ledger.withdrawal.dto;

import com.parth.ledger.transaction.dto.TransactionResponseDto;

public record WithdrawalResult(
        TransactionResponseDto response,
        boolean replayed
) {
}
