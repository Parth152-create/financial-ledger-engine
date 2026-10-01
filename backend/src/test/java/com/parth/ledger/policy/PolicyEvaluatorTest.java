package com.parth.ledger.policy;

import com.parth.ledger.transaction.TransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PolicyEvaluatorTest {

    private PolicyEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new PolicyEvaluator();
    }

    @Nested
    @DisplayName("MAX_TRANSACTION_AMOUNT Policy Evaluation")
    class MaxTransactionAmountTests {

        @Test
        @DisplayName("1. Transaction amount below limit succeeds")
        void amountBelowLimitSucceeds() {
            FinancialPolicy policy = createPolicy(PolicyType.MAX_TRANSACTION_AMOUNT, new BigDecimal("1000.0000"), null, true);
            assertThatCode(() -> evaluator.evaluateMaxTransactionAmount(policy, new BigDecimal("500.0000")))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("2. Transaction amount exactly at limit succeeds")
        void amountAtLimitSucceeds() {
            FinancialPolicy policy = createPolicy(PolicyType.MAX_TRANSACTION_AMOUNT, new BigDecimal("1000.0000"), null, true);
            assertThatCode(() -> evaluator.evaluateMaxTransactionAmount(policy, new BigDecimal("1000.0000")))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("3. Transaction amount above limit fails")
        void amountAboveLimitFails() {
            FinancialPolicy policy = createPolicy(PolicyType.MAX_TRANSACTION_AMOUNT, new BigDecimal("1000.0000"), null, true);
            assertThatThrownBy(() -> evaluator.evaluateMaxTransactionAmount(policy, new BigDecimal("1000.0001")))
                    .isInstanceOf(PolicyViolationException.class)
                    .hasMessageContaining("Transaction amount exceeds maximum allowed limit")
                    .extracting(e -> ((PolicyViolationException) e).getErrorCode())
                    .isEqualTo(PolicyErrorCode.POLICY_TRANSACTION_LIMIT_EXCEEDED);
        }

        @Test
        @DisplayName("4. Null or disabled policy means unlimited")
        void disabledOrNullPolicyMeansUnlimited() {
            FinancialPolicy disabled = createPolicy(PolicyType.MAX_TRANSACTION_AMOUNT, new BigDecimal("100.0000"), null, false);
            assertThatCode(() -> evaluator.evaluateMaxTransactionAmount(null, new BigDecimal("999999.0000")))
                    .doesNotThrowAnyException();
            assertThatCode(() -> evaluator.evaluateMaxTransactionAmount(disabled, new BigDecimal("999999.0000")))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("DAILY_TRANSACTION_AMOUNT Policy Evaluation")
    class DailyTransactionAmountTests {

        @Test
        @DisplayName("7. Daily amount below limit succeeds")
        void dailyAmountBelowLimitSucceeds() {
            FinancialPolicy policy = createPolicy(PolicyType.DAILY_TRANSACTION_AMOUNT, new BigDecimal("5000.0000"), null, true);
            assertThatCode(() -> evaluator.evaluateDailyAmountLimit(policy, new BigDecimal("2000.0000"), new BigDecimal("1000.0000")))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("8. Daily amount exactly at limit succeeds")
        void dailyAmountAtLimitSucceeds() {
            FinancialPolicy policy = createPolicy(PolicyType.DAILY_TRANSACTION_AMOUNT, new BigDecimal("5000.0000"), null, true);
            assertThatCode(() -> evaluator.evaluateDailyAmountLimit(policy, new BigDecimal("3000.0000"), new BigDecimal("2000.0000")))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("9. Daily amount exceeding limit fails")
        void dailyAmountExceedingLimitFails() {
            FinancialPolicy policy = createPolicy(PolicyType.DAILY_TRANSACTION_AMOUNT, new BigDecimal("5000.0000"), null, true);
            assertThatThrownBy(() -> evaluator.evaluateDailyAmountLimit(policy, new BigDecimal("4000.0000"), new BigDecimal("1000.0001")))
                    .isInstanceOf(PolicyViolationException.class)
                    .hasMessageContaining("Daily transaction amount limit exceeded")
                    .extracting(e -> ((PolicyViolationException) e).getErrorCode())
                    .isEqualTo(PolicyErrorCode.POLICY_DAILY_AMOUNT_LIMIT_EXCEEDED);
        }

        @Test
        @DisplayName("Daily amount handles null initial usage as zero")
        void nullInitialUsageTreatedAsZero() {
            FinancialPolicy policy = createPolicy(PolicyType.DAILY_TRANSACTION_AMOUNT, new BigDecimal("5000.0000"), null, true);
            assertThatCode(() -> evaluator.evaluateDailyAmountLimit(policy, null, new BigDecimal("5000.0000")))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("DAILY_TRANSACTION_COUNT Policy Evaluation")
    class DailyTransactionCountTests {

        @Test
        @DisplayName("10. Daily count below limit succeeds")
        void dailyCountBelowLimitSucceeds() {
            FinancialPolicy policy = createPolicy(PolicyType.DAILY_TRANSACTION_COUNT, null, 10, true);
            assertThatCode(() -> evaluator.evaluateDailyCountLimit(policy, 5))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("11. Daily count exactly at limit succeeds")
        void dailyCountAtLimitSucceeds() {
            FinancialPolicy policy = createPolicy(PolicyType.DAILY_TRANSACTION_COUNT, null, 10, true);
            // Current is 9, proposed operation is 9 + 1 = 10 (exactly at limit)
            assertThatCode(() -> evaluator.evaluateDailyCountLimit(policy, 9))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("12. Daily count exceeding limit fails")
        void dailyCountExceedingLimitFails() {
            FinancialPolicy policy = createPolicy(PolicyType.DAILY_TRANSACTION_COUNT, null, 10, true);
            // Current is 10, proposed operation is 10 + 1 = 11 > 10
            assertThatThrownBy(() -> evaluator.evaluateDailyCountLimit(policy, 10))
                    .isInstanceOf(PolicyViolationException.class)
                    .hasMessageContaining("Daily transaction count limit exceeded")
                    .extracting(e -> ((PolicyViolationException) e).getErrorCode())
                    .isEqualTo(PolicyErrorCode.POLICY_DAILY_COUNT_LIMIT_EXCEEDED);
        }
    }

    @Nested
    @DisplayName("ACCOUNT_BALANCE_LIMIT Policy Evaluation")
    class AccountBalanceLimitTests {

        @Test
        @DisplayName("13. Balance limit allows exact maximum")
        void balanceLimitAllowsExactMaximum() {
            FinancialPolicy policy = createPolicy(PolicyType.ACCOUNT_BALANCE_LIMIT, new BigDecimal("10000.0000"), null, true);
            // Current balance 8000 + credit 2000 = 10000
            assertThatCode(() -> evaluator.evaluateAccountBalanceLimit(policy, new BigDecimal("8000.0000"), new BigDecimal("2000.0000")))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("14. Balance limit rejects overflow")
        void balanceLimitRejectsOverflow() {
            FinancialPolicy policy = createPolicy(PolicyType.ACCOUNT_BALANCE_LIMIT, new BigDecimal("10000.0000"), null, true);
            // Current balance 8000 + credit 2000.0001 = 10000.0001 > 10000
            assertThatThrownBy(() -> evaluator.evaluateAccountBalanceLimit(policy, new BigDecimal("8000.0000"), new BigDecimal("2000.0001")))
                    .isInstanceOf(PolicyViolationException.class)
                    .hasMessageContaining("Resulting balance would exceed maximum allowed account balance")
                    .extracting(e -> ((PolicyViolationException) e).getErrorCode())
                    .isEqualTo(PolicyErrorCode.POLICY_BALANCE_LIMIT_EXCEEDED);
        }

        @Test
        @DisplayName("Null initial balance treated as zero")
        void nullInitialBalanceTreatedAsZero() {
            FinancialPolicy policy = createPolicy(PolicyType.ACCOUNT_BALANCE_LIMIT, new BigDecimal("10000.0000"), null, true);
            assertThatCode(() -> evaluator.evaluateAccountBalanceLimit(policy, null, new BigDecimal("10000.0000")))
                    .doesNotThrowAnyException();
        }
    }

    private FinancialPolicy createPolicy(PolicyType policyType, BigDecimal amountLimit, Integer countLimit, boolean enabled) {
        return new FinancialPolicy(
                UUID.randomUUID(),
                PolicyScope.ACCOUNT,
                policyType == PolicyType.ACCOUNT_BALANCE_LIMIT ? null : TransactionType.TRANSFER,
                policyType,
                amountLimit,
                countLimit,
                "INR",
                enabled
        );
    }
}
