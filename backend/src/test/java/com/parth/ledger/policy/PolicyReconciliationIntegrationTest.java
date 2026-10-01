package com.parth.ledger.policy;

import com.parth.ledger.BaseIntegrationTest;
import com.parth.ledger.account.Account;
import com.parth.ledger.account.AccountRepository;
import com.parth.ledger.deposit.dto.DepositRequestDto;
import com.parth.ledger.deposit.service.DepositService;
import com.parth.ledger.reconciliation.ReconciliationStatus;
import com.parth.ledger.reconciliation.dto.OverallReconciliationDto;
import com.parth.ledger.reconciliation.dto.ReconciliationResultDto;
import com.parth.ledger.reconciliation.service.ReconciliationService;
import com.parth.ledger.system.SystemFundingService;
import com.parth.ledger.transaction.TransactionType;
import com.parth.ledger.transaction.dto.TransferRequestDto;
import com.parth.ledger.transaction.service.TransferService;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Focused integration test proving that policy engine limits and operational quota tracking
 * never cause financial truth or double-entry ledger reconciliation divergence.
 * Validates both successful mutations under policy and rejected policy mutations.
 */
class PolicyReconciliationIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TransferService transferService;

    @Autowired
    private DepositService depositService;

    @Autowired
    private WithdrawalService withdrawalService;

    @Autowired
    private ReconciliationService reconciliationService;

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

    private User aliceUser;
    private User bobUser;
    private Account aliceAccount;
    private Account bobAccount;

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

        systemFundingService.bootstrapSystemFunding(new BigDecimal("10000000.0000"));

        aliceUser = userRepository.save(new User("alice.recon@ledger.com", "Alice"));
        bobUser = userRepository.save(new User("bob.recon@ledger.com", "Bob"));

        // Both accounts start at 0.0000
        aliceAccount = accountRepository.save(new Account(aliceUser, "INR", BigDecimal.ZERO.setScale(4)));
        bobAccount = accountRepository.save(new Account(bobUser, "INR", BigDecimal.ZERO.setScale(4)));

        // Fund Alice with initial 10,000.0000 INR via deposit to ensure double-entry ledger entries exist
        authenticateAs(aliceUser);
        depositService.executeDeposit("init-alice-fund-" + UUID.randomUUID(), new DepositRequestDto(
                aliceAccount.getId(),
                new BigDecimal("10000.0000"),
                "INR"
        ));
        // Clear daily policy usage so tests start with clean quota state
        dailyPolicyUsageRepository.deleteAll();
    }

    private void authenticateAs(User user) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user.getEmail(), null, Collections.emptyList())
        );
    }

    @Test
    @DisplayName("Reconciliation: Successful Deposit, Transfer, and Withdrawal under policies maintain 100% consistency")
    void testReconciliationAfterSuccessfulPolicyMutations() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        // 1. Configure policies
        // A. Max Deposit Amount = 50,000 INR
        financialPolicyRepository.save(new FinancialPolicy(
                null,
                PolicyScope.GLOBAL,
                TransactionType.DEPOSIT,
                PolicyType.MAX_TRANSACTION_AMOUNT,
                new BigDecimal("50000.0000"),
                null,
                "INR",
                true
        ));
        // B. Max Transfer Amount = 5,000 INR
        financialPolicyRepository.save(new FinancialPolicy(
                null,
                PolicyScope.GLOBAL,
                TransactionType.TRANSFER,
                PolicyType.MAX_TRANSACTION_AMOUNT,
                new BigDecimal("5000.0000"),
                null,
                "INR",
                true
        ));
        // C. Daily Withdrawal Amount = 20,000 INR
        financialPolicyRepository.save(new FinancialPolicy(
                null,
                PolicyScope.GLOBAL,
                TransactionType.WITHDRAWAL,
                PolicyType.DAILY_TRANSACTION_AMOUNT,
                new BigDecimal("20000.0000"),
                null,
                "INR",
                true
        ));

        // 2. Perform valid DEPOSIT of 5,000 INR to Alice
        authenticateAs(aliceUser);
        depositService.executeDeposit("recon-pol-dep-1", new DepositRequestDto(
                aliceAccount.getId(),
                new BigDecimal("5000.0000"),
                "INR"
        ));

        // Verify policy usage recorded
        Optional<DailyPolicyUsage> aliceDepUsage = dailyPolicyUsageRepository.findByAccountIdAndTransactionTypeAndUsageDate(
                aliceAccount.getId(), TransactionType.DEPOSIT, today
        );
        assertThat(aliceDepUsage).isPresent();
        assertThat(aliceDepUsage.get().getAmountUsed()).isEqualByComparingTo("5000.0000");
        assertThat(aliceDepUsage.get().getTransactionCount()).isEqualTo(1);

        // Verify Alice account reconciliation after deposit
        ReconciliationResultDto aliceRecon1 = reconciliationService.reconcileAccount(aliceAccount.getId());
        assertThat(aliceRecon1.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
        assertThat(aliceRecon1.difference()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(aliceRecon1.snapshotBalance()).isEqualByComparingTo(aliceRecon1.ledgerBalance());
        assertThat(aliceRecon1.snapshotBalance()).isEqualByComparingTo("15000.0000");

        // 3. Perform valid TRANSFER of 3,000 INR from Alice to Bob
        transferService.executeTransfer("recon-pol-tx-1", new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("3000.0000"),
                "INR"
        ));

        // Verify policy usage on Alice's transfer quota
        Optional<DailyPolicyUsage> aliceTxUsage = dailyPolicyUsageRepository.findByAccountIdAndTransactionTypeAndUsageDate(
                aliceAccount.getId(), TransactionType.TRANSFER, today
        );
        assertThat(aliceTxUsage).isPresent();
        assertThat(aliceTxUsage.get().getAmountUsed()).isEqualByComparingTo("3000.0000");
        assertThat(aliceTxUsage.get().getTransactionCount()).isEqualTo(1);

        // Verify Alice and Bob reconciliation after transfer
        ReconciliationResultDto aliceRecon2 = reconciliationService.reconcileAccount(aliceAccount.getId());
        assertThat(aliceRecon2.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
        assertThat(aliceRecon2.difference()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(aliceRecon2.snapshotBalance()).isEqualByComparingTo("12000.0000");

        authenticateAs(bobUser);
        ReconciliationResultDto bobRecon1 = reconciliationService.reconcileAccount(bobAccount.getId());
        assertThat(bobRecon1.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
        assertThat(bobRecon1.difference()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(bobRecon1.snapshotBalance()).isEqualByComparingTo("3000.0000");

        // 4. Perform valid WITHDRAWAL of 1,000 INR from Bob
        withdrawalService.executeWithdrawal("recon-pol-wth-1", new WithdrawalRequestDto(
                bobAccount.getId(),
                new BigDecimal("1000.0000"),
                "INR"
        ));

        // Verify policy usage on Bob's withdrawal quota
        Optional<DailyPolicyUsage> bobWthUsage = dailyPolicyUsageRepository.findByAccountIdAndTransactionTypeAndUsageDate(
                bobAccount.getId(), TransactionType.WITHDRAWAL, today
        );
        assertThat(bobWthUsage).isPresent();
        assertThat(bobWthUsage.get().getAmountUsed()).isEqualByComparingTo("1000.0000");
        assertThat(bobWthUsage.get().getTransactionCount()).isEqualTo(1);

        // Verify Bob reconciliation after withdrawal
        ReconciliationResultDto bobRecon2 = reconciliationService.reconcileAccount(bobAccount.getId());
        assertThat(bobRecon2.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
        assertThat(bobRecon2.difference()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(bobRecon2.snapshotBalance()).isEqualByComparingTo("2000.0000");

        // 5. Verify user accounts reconciliation integrity
        OverallReconciliationDto overall = reconciliationService.reconcileUserAccounts();
        assertThat(overall.discrepancyCount()).isEqualTo(0);
        assertThat(overall.consistentAccounts()).isGreaterThan(0);
    }

    @Test
    @DisplayName("Reconciliation: Rejected policy requests leave NO financial mutation and cause NO reconciliation divergence")
    void testReconciliationAfterRejectedPolicyMutations() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        // Configure Max Transfer limit = 1,000 INR
        financialPolicyRepository.save(new FinancialPolicy(
                null,
                PolicyScope.GLOBAL,
                TransactionType.TRANSFER,
                PolicyType.MAX_TRANSACTION_AMOUNT,
                new BigDecimal("1000.0000"),
                null,
                "INR",
                true
        ));

        // Configure Destination Balance limit on Bob = 500 INR
        financialPolicyRepository.save(new FinancialPolicy(
                bobAccount.getId(),
                PolicyScope.ACCOUNT,
                null,
                PolicyType.ACCOUNT_BALANCE_LIMIT,
                new BigDecimal("500.0000"),
                null,
                "INR",
                true
        ));

        // 1. Initial reconciliation before attempts
        authenticateAs(aliceUser);
        ReconciliationResultDto initialAlice = reconciliationService.reconcileAccount(aliceAccount.getId());
        assertThat(initialAlice.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
        assertThat(initialAlice.snapshotBalance()).isEqualByComparingTo("10000.0000");

        authenticateAs(bobUser);
        ReconciliationResultDto initialBob = reconciliationService.reconcileAccount(bobAccount.getId());
        assertThat(initialBob.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
        assertThat(initialBob.snapshotBalance()).isEqualByComparingTo(BigDecimal.ZERO);

        // 2. Attempt Transfer exceeding Max Transaction Amount (5,000 > 1,000)
        authenticateAs(aliceUser);
        assertThatThrownBy(() -> transferService.executeTransfer("recon-rej-tx-1", new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("5000.0000"),
                "INR"
        ))).isInstanceOf(PolicyViolationException.class);

        // Verify policy usage was NOT consumed
        Optional<DailyPolicyUsage> aliceUsage = dailyPolicyUsageRepository.findByAccountIdAndTransactionTypeAndUsageDate(
                aliceAccount.getId(), TransactionType.TRANSFER, today
        );
        assertThat(aliceUsage).isEmpty();

        // Verify Alice and Bob balances and reconciliation are completely unchanged
        ReconciliationResultDto postRejAlice1 = reconciliationService.reconcileAccount(aliceAccount.getId());
        assertThat(postRejAlice1.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
        assertThat(postRejAlice1.difference()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(postRejAlice1.snapshotBalance()).isEqualByComparingTo("10000.0000");

        authenticateAs(bobUser);
        ReconciliationResultDto postRejBob1 = reconciliationService.reconcileAccount(bobAccount.getId());
        assertThat(postRejBob1.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
        assertThat(postRejBob1.difference()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(postRejBob1.snapshotBalance()).isEqualByComparingTo(BigDecimal.ZERO);

        // 3. Attempt Transfer violating Destination Balance Limit (800 > 500)
        authenticateAs(aliceUser);
        assertThatThrownBy(() -> transferService.executeTransfer("recon-rej-tx-2", new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("800.0000"),
                "INR"
        ))).isInstanceOf(PolicyViolationException.class);

        // Verify accounts remain strictly reconciled with 0 difference
        ReconciliationResultDto postRejAlice2 = reconciliationService.reconcileAccount(aliceAccount.getId());
        assertThat(postRejAlice2.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
        assertThat(postRejAlice2.difference()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(postRejAlice2.snapshotBalance()).isEqualByComparingTo("10000.0000");

        authenticateAs(bobUser);
        ReconciliationResultDto postRejBob2 = reconciliationService.reconcileAccount(bobAccount.getId());
        assertThat(postRejBob2.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
        assertThat(postRejBob2.difference()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(postRejBob2.snapshotBalance()).isEqualByComparingTo(BigDecimal.ZERO);

        // 4. User reconciliation confirms zero discrepancies across the evaluated accounts
        OverallReconciliationDto overall = reconciliationService.reconcileUserAccounts();
        assertThat(overall.discrepancyCount()).isEqualTo(0);
        assertThat(overall.consistentAccounts()).isGreaterThan(0);
    }
}
