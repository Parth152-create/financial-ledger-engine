package com.parth.ledger.policy.dto;

import com.parth.ledger.policy.PolicyScope;
import com.parth.ledger.policy.PolicyType;
import com.parth.ledger.transaction.TransactionType;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record CreatePolicyRequestDto(
        UUID accountId,
        @NotNull(message = "Policy scope is required")
        PolicyScope policyScope,
        TransactionType transactionType,
        @NotNull(message = "Policy type is required")
        PolicyType policyType,
        BigDecimal amountLimit,
        Integer countLimit,
        String currency,
        Boolean enabled
) {
}
