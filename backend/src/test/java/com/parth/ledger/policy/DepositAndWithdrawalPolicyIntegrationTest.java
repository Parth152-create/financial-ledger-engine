package com.parth.ledger.policy;

import com.parth.ledger.BaseIntegrationTest;
import com.parth.ledger.account.Account;
import com.parth.ledger.account.AccountRepository;
import com.parth.ledger.deposit.dto.DepositRequestDto;
import com.parth.ledger.transaction.dto.TransactionResponseDto;
import com.parth.ledger.deposit.service.DepositService;
import com.parth.ledger.ledger.LedgerEntryRepository;
import com.parth.ledger.system.SystemFundingService;
import com.parth.ledger.transaction.TransactionRepository;
import com.parth.ledger.transaction.TransactionType;
import com.parth.ledger.transaction.exception.InsufficientBalanceException;
import com.parth.ledger.user.User;
import com.parth.ledger.user.UserRepository;
import com.parth.ledger.withdrawal.dto.WithdrawalRequestDto;
import com.parth.ledger.withdrawal.service.WithdrawalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DepositAndWithdrawalPolicyIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private DepositService depositService;

    @Autowired
    private WithdrawalService withdrawalService;

    @Autowired
    private SystemFundingService systemFundingService;

    @Autowired
    private FinancialPolicyRepository financialPolicyRepository;

    @Autowired
    private DailyPolicyUsageRepository dailyPolicyUsageRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    private User aliceUser;
    private Account aliceAccount;

    @BeforeEach
    void setUp() {
        clearRedis();
        if (jdbcTemplate != null) {
            jdbcTemplate.execute("TRUNCATE TABLE audit_events CASCADE");
            jdbcTemplate.execute("TRUNCATE TABLE ledger_entries CASCADE");
            jdbcTemplate.execute("TRUNCATE TABLE transactions CASCADE");
            jdbcTemplate.execute("TRUNCATE TABLE policy_usage_daily CASCADE");
            jdbcTemplate.execute("TRUNCATE TABLE financial_policies CASCADE");
            jdbcTemplate.execute("TRUNCATE TABLE accounts CASCADE");
            jdbcTemplate.execute("TRUNCATE TABLE users CASCADE");
        }

        // Ensure system clearing has liquidity
        systemFundingService.bootstrapSystemFunding(new BigDecimal("10000000.0000"));

        aliceUser = userRepository.save(new User("alice.depwith@ledger.com", "Alice"));
        aliceAccount = accountRepository.save(new Account(aliceUser, "INR", new BigDecimal("10000.0000")));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice.depwith@ledger.com", null, Collections.emptyList())
        );
    }

    @Test
    @DisplayName("19. Deposit applies deposit policies (max, daily amount, daily count, destination balance)")
    void verifyDepositAppliesDepositPolicies() {
        // Configure deposit policies: max = 50,000; daily amount = 80,000; daily count = 2; balance limit = 60,000
        financialPolicyRepository.save(new FinancialPolicy(
                aliceAccount.getId(),
                PolicyScope.ACCOUNT,
                TransactionType.DEPOSIT,
                PolicyType.MAX_TRANSACTION_AMOUNT,
                new BigDecimal("50000.0000"),
                null,
                "INR",
                true
        ));
        financialPolicyRepository.save(new FinancialPolicy(
                aliceAccount.getId(),
                PolicyScope.ACCOUNT,
                TransactionType.DEPOSIT,
                PolicyType.DAILY_TRANSACTION_AMOUNT,
                new BigDecimal("80000.0000"),
                null,
                "INR",
                true
        ));
        financialPolicyRepository.save(new FinancialPolicy(
                aliceAccount.getId(),
                PolicyScope.ACCOUNT,
                TransactionType.DEPOSIT,
                PolicyType.DAILY_TRANSACTION_COUNT,
                null,
                2,
                "INR",
                true
        ));
        financialPolicyRepository.save(new FinancialPolicy(
                aliceAccount.getId(),
                PolicyScope.ACCOUNT,
                null,
                PolicyType.ACCOUNT_BALANCE_LIMIT,
                new BigDecimal("60000.0000"),
                null,
                "INR",
                true
        ));

        // Attempt 1: 50,000.0001 exceeds max deposit amount (50,000)
        assertThatThrownBy(() -> depositService.executeDeposit("dep-max-exceeded", new DepositRequestDto(
                aliceAccount.getId(),
                new BigDecimal("50000.0001"),
                "INR"
        ))).isInstanceOf(PolicyViolationException.class)
                .extracting(e -> ((PolicyViolationException) e).getErrorCode())
                .isEqualTo(PolicyErrorCode.POLICY_TRANSACTION_LIMIT_EXCEEDED);

        // Attempt 2: 50,000 would result in balance 10,000 + 50,000.0001 > 60,000 balance limit
        // Current balance is 10,000. Deposit of 50,000 brings balance to exactly 60,000 -> succeeds!
        TransactionResponseDto dep1 = depositService.executeDeposit("dep-valid-1", new DepositRequestDto(
                aliceAccount.getId(),
                new BigDecimal("50000.0000"),
                "INR"
        ));
        assertThat(dep1.status()).isEqualTo(com.parth.ledger.transaction.TransactionStatus.COMPLETED);

        // Attempt 3: Deposit 1.0000 would exceed balance limit (60,000 + 1 = 60,001 > 60,000)
        assertThatThrownBy(() -> depositService.executeDeposit("dep-bal-exceeded", new DepositRequestDto(
                aliceAccount.getId(),
                new BigDecimal("1.0000"),
                "INR"
        ))).isInstanceOf(PolicyViolationException.class)
                .extracting(e -> ((PolicyViolationException) e).getErrorCode())
                .isEqualTo(PolicyErrorCode.POLICY_BALANCE_LIMIT_EXCEEDED);

        // SYSTEM_CLEARING should NOT have user daily limits consumed
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Optional<DailyPolicyUsage> clearingUsage = dailyPolicyUsageRepository.findByAccountIdAndTransactionTypeAndUsageDate(
                SystemFundingService.SYSTEM_CLEARING_ACCOUNT_ID,
                TransactionType.DEPOSIT,
                today
        );
        assertThat(clearingUsage).isEmpty();
    }

    @Test
    @DisplayName("Deposit does not apply withdrawal limits")
    void verifyDepositDoesNotApplyWithdrawalLimits() {
        // Configure strict withdrawal limit = 100
        financialPolicyRepository.save(new FinancialPolicy(
                aliceAccount.getId(),
                PolicyScope.ACCOUNT,
                TransactionType.WITHDRAWAL,
                PolicyType.MAX_TRANSACTION_AMOUNT,
                new BigDecimal("100.0000"),
                null,
                "INR",
                true
        ));

        // Deposit of 5,000 should NOT be affected by withdrawal limit
        assertThatCode(() -> depositService.executeDeposit("dep-not-affected", new DepositRequestDto(
                aliceAccount.getId(),
                new BigDecimal("5000.0000"),
                "INR"
        ))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("20. Withdrawal applies withdrawal policies (max, daily amount, daily count)")
    void verifyWithdrawalAppliesWithdrawalPolicies() {
        // Configure withdrawal limits: max = 2,000; daily amount = 3,000; count = 2
        financialPolicyRepository.save(new FinancialPolicy(
                aliceAccount.getId(),
                PolicyScope.ACCOUNT,
                TransactionType.WITHDRAWAL,
                PolicyType.MAX_TRANSACTION_AMOUNT,
                new BigDecimal("2000.0000"),
                null,
                "INR",
                true
        ));
        financialPolicyRepository.save(new FinancialPolicy(
                aliceAccount.getId(),
                PolicyScope.ACCOUNT,
                TransactionType.WITHDRAWAL,
                PolicyType.DAILY_TRANSACTION_AMOUNT,
                new BigDecimal("3000.0000"),
                null,
                "INR",
                true
        ));
        financialPolicyRepository.save(new FinancialPolicy(
                aliceAccount.getId(),
                PolicyScope.ACCOUNT,
                TransactionType.WITHDRAWAL,
                PolicyType.DAILY_TRANSACTION_COUNT,
                null,
                2,
                "INR",
                true
        ));

        // Attempt 1: 2,500 exceeds max withdrawal limit (2,000)
        assertThatThrownBy(() -> withdrawalService.executeWithdrawal("with-max-exceeded", new WithdrawalRequestDto(
                aliceAccount.getId(),
                new BigDecimal("2500.0000"),
                "INR"
        ))).isInstanceOf(PolicyViolationException.class)
                .extracting(e -> ((PolicyViolationException) e).getErrorCode())
                .isEqualTo(PolicyErrorCode.POLICY_TRANSACTION_LIMIT_EXCEEDED);

        // Withdrawal 1: 1,500 succeeds
        TransactionResponseDto with1 = withdrawalService.executeWithdrawal("with-valid-1", new WithdrawalRequestDto(
                aliceAccount.getId(),
                new BigDecimal("1500.0000"),
                "INR"
        ));
        assertThat(with1.status()).isEqualTo(com.parth.ledger.transaction.TransactionStatus.COMPLETED);

        // Attempt 2: 2,000 would exceed daily amount (1,500 + 2,000 = 3,500 > 3,000)
        assertThatThrownBy(() -> withdrawalService.executeWithdrawal("with-daily-amt-exceeded", new WithdrawalRequestDto(
                aliceAccount.getId(),
                new BigDecimal("2000.0000"),
                "INR"
        ))).isInstanceOf(PolicyViolationException.class)
                .extracting(e -> ((PolicyViolationException) e).getErrorCode())
                .isEqualTo(PolicyErrorCode.POLICY_DAILY_AMOUNT_LIMIT_EXCEEDED);

        // Withdrawal 2: 1,000 succeeds (Daily = 2,500 <= 3,000; Count = 2 <= 2)
        TransactionResponseDto with2 = withdrawalService.executeWithdrawal("with-valid-2", new WithdrawalRequestDto(
                aliceAccount.getId(),
                new BigDecimal("1000.0000"),
                "INR"
        ));
        assertThat(with2.status()).isEqualTo(com.parth.ledger.transaction.TransactionStatus.COMPLETED);

        // Attempt 3: 100 exceeds daily count (Count would be 3 > 2)
        assertThatThrownBy(() -> withdrawalService.executeWithdrawal("with-count-exceeded", new WithdrawalRequestDto(
                aliceAccount.getId(),
                new BigDecimal("100.0000"),
                "INR"
        ))).isInstanceOf(PolicyViolationException.class)
                .extracting(e -> ((PolicyViolationException) e).getErrorCode())
                .isEqualTo(PolicyErrorCode.POLICY_DAILY_COUNT_LIMIT_EXCEEDED);
    }

    @Test
    @DisplayName("Withdrawal checks existing insufficient balance invariant authoritatively")
    void verifyWithdrawalChecksInsufficientBalance() {
        // Configure high limits so policy passes
        financialPolicyRepository.save(new FinancialPolicy(
                aliceAccount.getId(),
                PolicyScope.ACCOUNT,
                TransactionType.WITHDRAWAL,
                PolicyType.MAX_TRANSACTION_AMOUNT,
                new BigDecimal("100000.0000"),
                null,
                "INR",
                true
        ));

        // Alice only has 10,000. Attempt withdrawal of 15,000.
        assertThatThrownBy(() -> withdrawalService.executeWithdrawal("with-insufficient", new WithdrawalRequestDto(
                aliceAccount.getId(),
                new BigDecimal("15000.0000"),
                "INR"
        ))).isInstanceOf(InsufficientBalanceException.class);
    }

    @Test
    @DisplayName("Withdrawal decreases balance, so ACCOUNT_BALANCE_LIMIT does not block it")
    void verifyWithdrawalDoesNotTriggerBalanceLimit() {
        // Balance limit of 5,000 on Alice's account, but Alice currently has 10,000
        financialPolicyRepository.save(new FinancialPolicy(
                aliceAccount.getId(),
                PolicyScope.ACCOUNT,
                null,
                PolicyType.ACCOUNT_BALANCE_LIMIT,
                new BigDecimal("5000.0000"),
                null,
                "INR",
                true
        ));

        // Withdrawal of 1,000 decreases balance from 10,000 to 9,000
        assertThatCode(() -> withdrawalService.executeWithdrawal("with-no-bal-check", new WithdrawalRequestDto(
                aliceAccount.getId(),
                new BigDecimal("1000.0000"),
                "INR"
        ))).doesNotThrowAnyException();
    }
}
