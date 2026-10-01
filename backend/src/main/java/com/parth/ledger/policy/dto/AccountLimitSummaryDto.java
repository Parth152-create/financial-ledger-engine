package com.parth.ledger.policy.dto;

import com.parth.ledger.transaction.TransactionType;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Authoritative DTO representing applicable financial limits, current daily usage,
 * and authoritative remaining capacities for an owned account.
 */
public record AccountLimitSummaryDto(
        UUID accountId,
        TransactionType transactionType,
        BigDecimal maxTransactionAmount,
        BigDecimal dailyAmountLimit,
        BigDecimal dailyAmountUsed,
        BigDecimal dailyAmountRemaining,
        Integer dailyCountLimit,
        Integer dailyCountUsed,
        Integer dailyCountRemaining,
        BigDecimal currentBalance,
        BigDecimal balanceLimit,
        BigDecimal accountBalanceLimit,
        BigDecimal balanceRemaining,
        String currency
) {
}
