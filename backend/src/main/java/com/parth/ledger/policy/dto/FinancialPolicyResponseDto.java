package com.parth.ledger.policy.dto;

import com.parth.ledger.policy.FinancialPolicy;
import com.parth.ledger.policy.PolicyScope;
import com.parth.ledger.policy.PolicyType;
import com.parth.ledger.transaction.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record FinancialPolicyResponseDto(
        UUID id,
        UUID accountId,
        PolicyScope policyScope,
        TransactionType transactionType,
        PolicyType policyType,
        BigDecimal amountLimit,
        Integer countLimit,
        String currency,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt
) {
    public static FinancialPolicyResponseDto from(FinancialPolicy policy) {
        return new FinancialPolicyResponseDto(
                policy.getId(),
                policy.getAccountId(),
                policy.getPolicyScope(),
                policy.getTransactionType(),
                policy.getPolicyType(),
                policy.getAmountLimit(),
                policy.getCountLimit(),
                policy.getCurrency(),
                policy.isEnabled(),
                policy.getCreatedAt(),
                policy.getUpdatedAt()
        );
    }
}
