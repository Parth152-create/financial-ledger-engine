package com.parth.ledger.policy;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Pure evaluator for financial policy constraints.
 * Evaluates proposed operations against applicable policies without performing database mutations or ledger modifications.
 */
@Component
public class PolicyEvaluator {

    /**
     * Evaluates a transaction amount against an applicable MAX_TRANSACTION_AMOUNT policy.
     */
    public void evaluateMaxTransactionAmount(FinancialPolicy policy, BigDecimal amount) {
        if (policy == null || !policy.isEnabled() || policy.getAmountLimit() == null) {
            return;
        }
        if (amount.compareTo(policy.getAmountLimit()) > 0) {
            throw new PolicyViolationException(
                    PolicyErrorCode.POLICY_TRANSACTION_LIMIT_EXCEEDED,
                    "Transaction amount exceeds maximum allowed limit: " + policy.getAmountLimit()
            );
        }
    }

    /**
     * Evaluates cumulative daily transaction amount against an applicable DAILY_TRANSACTION_AMOUNT policy.
     */
    public void evaluateDailyAmountLimit(FinancialPolicy policy, BigDecimal currentUsage, BigDecimal additionalAmount) {
        if (policy == null || !policy.isEnabled() || policy.getAmountLimit() == null) {
            return;
        }
        BigDecimal total = (currentUsage != null ? currentUsage : BigDecimal.ZERO).add(additionalAmount);
        if (total.compareTo(policy.getAmountLimit()) > 0) {
            throw new PolicyViolationException(
                    PolicyErrorCode.POLICY_DAILY_AMOUNT_LIMIT_EXCEEDED,
                    "Daily transaction amount limit exceeded: limit " + policy.getAmountLimit() + ", attempted cumulative " + total
            );
        }
    }

    /**
     * Evaluates cumulative daily transaction count against an applicable DAILY_TRANSACTION_COUNT policy.
     */
    public void evaluateDailyCountLimit(FinancialPolicy policy, int currentCount) {
        if (policy == null || !policy.isEnabled() || policy.getCountLimit() == null) {
            return;
        }
        int proposedCount = currentCount + 1;
        if (proposedCount > policy.getCountLimit()) {
            throw new PolicyViolationException(
                    PolicyErrorCode.POLICY_DAILY_COUNT_LIMIT_EXCEEDED,
                    "Daily transaction count limit exceeded: limit " + policy.getCountLimit()
            );
        }
    }

    /**
     * Evaluates proposed account balance against an applicable ACCOUNT_BALANCE_LIMIT policy.
     * Only applies when balance increases (credits).
     */
    public void evaluateAccountBalanceLimit(FinancialPolicy policy, BigDecimal currentBalance, BigDecimal creditAmount) {
        if (policy == null || !policy.isEnabled() || policy.getAmountLimit() == null) {
            return;
        }
        BigDecimal proposedBalance = (currentBalance != null ? currentBalance : BigDecimal.ZERO).add(creditAmount);
        if (proposedBalance.compareTo(policy.getAmountLimit()) > 0) {
            throw new PolicyViolationException(
                    PolicyErrorCode.POLICY_BALANCE_LIMIT_EXCEEDED,
                    "Resulting balance would exceed maximum allowed account balance: limit " + policy.getAmountLimit() + ", proposed " + proposedBalance
            );
        }
    }
}
