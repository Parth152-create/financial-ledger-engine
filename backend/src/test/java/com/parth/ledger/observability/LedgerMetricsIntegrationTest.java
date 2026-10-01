package com.parth.ledger.observability;

import com.parth.ledger.BaseIntegrationTest;
import com.parth.ledger.account.Account;
import com.parth.ledger.account.AccountNotFoundException;
import com.parth.ledger.account.AccountRepository;
import com.parth.ledger.security.AccountOwnershipException;
import com.parth.ledger.deposit.dto.DepositRequestDto;
import com.parth.ledger.deposit.service.DepositService;
import com.parth.ledger.observability.metrics.LedgerMetrics;
import com.parth.ledger.policy.FinancialPolicy;
import com.parth.ledger.policy.FinancialPolicyRepository;
import com.parth.ledger.policy.PolicyType;
import com.parth.ledger.policy.PolicyScope;
import com.parth.ledger.policy.PolicyViolationException;
import com.parth.ledger.reconciliation.service.ReconciliationService;
import com.parth.ledger.security.ratelimit.RedisRateLimiterService;
import com.parth.ledger.system.SystemFundingService;
import com.parth.ledger.transaction.Transaction;
import com.parth.ledger.transaction.TransactionRepository;
import com.parth.ledger.transaction.TransactionStatus;
import com.parth.ledger.transaction.dto.ReversalRequestDto;
import com.parth.ledger.transaction.dto.TransferRequestDto;
import com.parth.ledger.transaction.dto.TransferResponseDto;
import com.parth.ledger.transaction.exception.IdempotencyConflictException;
import com.parth.ledger.transaction.service.TransactionReversalService;
import com.parth.ledger.transaction.service.TransferService;
import com.parth.ledger.user.User;
import com.parth.ledger.user.UserRepository;
import com.parth.ledger.withdrawal.dto.WithdrawalRequestDto;
import com.parth.ledger.withdrawal.service.WithdrawalService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("V2.4 Financial Operation & Observability Metrics Integration Tests")
class LedgerMetricsIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private TransferService transferService;

    @Autowired
    private DepositService depositService;

    @Autowired
    private WithdrawalService withdrawalService;

    @Autowired
    private TransactionReversalService reversalService;

    @Autowired
    private ReconciliationService reconciliationService;

    @Autowired
    private SystemFundingService systemFundingService;

    @Autowired
    private RedisRateLimiterService rateLimiterService;

    @Autowired
    private FinancialPolicyRepository policyRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    private User aliceUser;
    private User bobUser;
    private Account aliceAccount;
    private Account bobAccount;

    @BeforeEach
    void setUp() {
        clearRedis();
        if (jdbcTemplate != null) {
            jdbcTemplate.execute("TRUNCATE TABLE transactions CASCADE");
            jdbcTemplate.execute("TRUNCATE TABLE accounts CASCADE");
            jdbcTemplate.execute("TRUNCATE TABLE users CASCADE");
        }

        systemFundingService.bootstrapSystemFunding(SystemFundingService.DEFAULT_BOOTSTRAP_AMOUNT);

        aliceUser = userRepository.save(new User("alice.metrics@ledger.com", "Alice"));
        bobUser = userRepository.save(new User("bob.metrics@ledger.com", "Bob"));

        aliceAccount = accountRepository.save(new Account(aliceUser, "INR"));
        bobAccount = accountRepository.save(new Account(bobUser, "INR"));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice.metrics@ledger.com", null, Collections.emptyList())
        );
        depositService.executeDeposit("fund-alice-setup-" + UUID.randomUUID(),
                new DepositRequestDto(aliceAccount.getId(), new BigDecimal("10000.0000"), "INR", "Initial funding"));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("bob.metrics@ledger.com", null, Collections.emptyList())
        );
        depositService.executeDeposit("fund-bob-setup-" + UUID.randomUUID(),
                new DepositRequestDto(bobAccount.getId(), new BigDecimal("5000.0000"), "INR", "Initial funding"));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice.metrics@ledger.com", null, Collections.emptyList())
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private double getCounterValue(String name, String... tags) {
        Counter counter = meterRegistry.find(name).tags(tags).counter();
        return counter != null ? counter.count() : 0.0;
    }

    private long getTimerCount(String name, String... tags) {
        Timer timer = meterRegistry.find(name).tags(tags).timer();
        return timer != null ? timer.count() : 0L;
    }

    @Test
    @DisplayName("7. Metric counters and timers increment on transfer attempted and completed")
    void transferMetricsIncrement() {
        double attemptedBefore = getCounterValue("ledger.operation.total", "operation", "TRANSFER", "status", "ATTEMPTED");
        double completedBefore = getCounterValue("ledger.operation.total", "operation", "TRANSFER", "status", "COMPLETED");
        long timerBefore = getTimerCount("ledger.operation.duration", "operation", "TRANSFER", "status", "COMPLETED");

        String key = "tx-transfer-metric-" + UUID.randomUUID();
        TransferRequestDto request = new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("500.0000"),
                "INR"
        );

        TransferResponseDto response = transferService.executeTransfer(key, request);
        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(TransactionStatus.COMPLETED);

        double attemptedAfter = getCounterValue("ledger.operation.total", "operation", "TRANSFER", "status", "ATTEMPTED");
        double completedAfter = getCounterValue("ledger.operation.total", "operation", "TRANSFER", "status", "COMPLETED");
        long timerAfter = getTimerCount("ledger.operation.duration", "operation", "TRANSFER", "status", "COMPLETED");

        assertThat(attemptedAfter).isEqualTo(attemptedBefore + 1.0);
        assertThat(completedAfter).isEqualTo(completedBefore + 1.0);
        assertThat(timerAfter).isEqualTo(timerBefore + 1L);
    }

    @Test
    @DisplayName("8. Metric counters increment on deposit attempted and completed")
    void depositMetricsIncrement() {
        double attemptedBefore = getCounterValue("ledger.operation.total", "operation", "DEPOSIT", "status", "ATTEMPTED");
        double completedBefore = getCounterValue("ledger.operation.total", "operation", "DEPOSIT", "status", "COMPLETED");

        String key = "tx-deposit-metric-" + UUID.randomUUID();
        DepositRequestDto request = new DepositRequestDto(
                aliceAccount.getId(),
                new BigDecimal("1000.0000"),
                "INR",
                "Deposit test"
        );

        depositService.executeDeposit(key, request);

        double attemptedAfter = getCounterValue("ledger.operation.total", "operation", "DEPOSIT", "status", "ATTEMPTED");
        double completedAfter = getCounterValue("ledger.operation.total", "operation", "DEPOSIT", "status", "COMPLETED");

        assertThat(attemptedAfter).isEqualTo(attemptedBefore + 1.0);
        assertThat(completedAfter).isEqualTo(completedBefore + 1.0);
    }

    @Test
    @DisplayName("9. Metric counters increment on withdrawal attempted and completed")
    void withdrawalMetricsIncrement() {
        double attemptedBefore = getCounterValue("ledger.operation.total", "operation", "WITHDRAWAL", "status", "ATTEMPTED");
        double completedBefore = getCounterValue("ledger.operation.total", "operation", "WITHDRAWAL", "status", "COMPLETED");

        String key = "tx-withdrawal-metric-" + UUID.randomUUID();
        WithdrawalRequestDto request = new WithdrawalRequestDto(
                aliceAccount.getId(),
                new BigDecimal("300.0000"),
                "INR",
                "Withdrawal test"
        );

        withdrawalService.executeWithdrawal(key, request);

        double attemptedAfter = getCounterValue("ledger.operation.total", "operation", "WITHDRAWAL", "status", "ATTEMPTED");
        double completedAfter = getCounterValue("ledger.operation.total", "operation", "WITHDRAWAL", "status", "COMPLETED");

        assertThat(attemptedAfter).isEqualTo(attemptedBefore + 1.0);
        assertThat(completedAfter).isEqualTo(completedBefore + 1.0);
    }

    @Test
    @DisplayName("10. Metric counters increment on reversal attempted and completed")
    void reversalMetricsIncrement() {
        // Setup initial transfer
        String transferKey = "tx-for-reversal-" + UUID.randomUUID();
        TransferRequestDto transferReq = new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("400.0000"),
                "INR"
        );
        TransferResponseDto transferResp = transferService.executeTransfer(transferKey, transferReq);

        // Switch to Bob as destination owner authorizing reversal
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("bob.metrics@ledger.com", null, Collections.emptyList())
        );

        double attemptedBefore = getCounterValue("ledger.operation.total", "operation", "REVERSAL", "status", "ATTEMPTED");
        double completedBefore = getCounterValue("ledger.operation.total", "operation", "REVERSAL", "status", "COMPLETED");

        String reversalKey = "rev-metric-" + UUID.randomUUID();
        reversalService.executeReversal(transferResp.transactionId(), reversalKey, new ReversalRequestDto("Mistake transfer"));

        double attemptedAfter = getCounterValue("ledger.operation.total", "operation", "REVERSAL", "status", "ATTEMPTED");
        double completedAfter = getCounterValue("ledger.operation.total", "operation", "REVERSAL", "status", "COMPLETED");

        assertThat(attemptedAfter).isEqualTo(attemptedBefore + 1.0);
        assertThat(completedAfter).isEqualTo(completedBefore + 1.0);
    }

    @Test
    @DisplayName("11. Policy rejection increments policy metrics and does NOT increment completed transactions")
    void policyRejectionMetrics() {
        // Create policy: Max transfer amount 200.0000 INR
        FinancialPolicy policy = new FinancialPolicy(
                aliceAccount.getId(),
                PolicyScope.ACCOUNT,
                com.parth.ledger.transaction.TransactionType.TRANSFER,
                PolicyType.MAX_TRANSACTION_AMOUNT,
                new BigDecimal("200.0000"),
                null,
                "INR",
                true
        );
        policyRepository.save(policy);

        double policyRejectionsBefore = getCounterValue("ledger.policy.rejections.total", "policy_type", "MAX_TRANSACTION_AMOUNT", "tx_type", "TRANSFER");
        double completedTransfersBefore = getCounterValue("ledger.operation.total", "operation", "TRANSFER", "status", "COMPLETED");
        double rejectedTransfersBefore = getCounterValue("ledger.operation.total", "operation", "TRANSFER", "status", "REJECTED");

        String key = "tx-policy-reject-" + UUID.randomUUID();
        TransferRequestDto request = new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("500.0000"),
                "INR"
        );

        assertThatThrownBy(() -> transferService.executeTransfer(key, request))
                .isInstanceOf(PolicyViolationException.class);

        double policyRejectionsAfter = getCounterValue("ledger.policy.rejections.total", "policy_type", "MAX_TRANSACTION_AMOUNT", "tx_type", "TRANSFER");
        double completedTransfersAfter = getCounterValue("ledger.operation.total", "operation", "TRANSFER", "status", "COMPLETED");
        double rejectedTransfersAfter = getCounterValue("ledger.operation.total", "operation", "TRANSFER", "status", "REJECTED");

        assertThat(policyRejectionsAfter).isEqualTo(policyRejectionsBefore + 1.0);
        assertThat(rejectedTransfersAfter).isEqualTo(rejectedTransfersBefore + 1.0);
        assertThat(completedTransfersAfter).isEqualTo(completedTransfersBefore);
    }

    @Test
    @DisplayName("12. Rate-limited request increments rate limit metric and does NOT increment financial transaction metrics")
    void rateLimitingMetrics() {
        double rateLimitRejectionsBefore = getCounterValue("ledger.ratelimit.rejections.total", "category", "FINANCIAL");
        double completedTransfersBefore = getCounterValue("ledger.operation.total", "operation", "TRANSFER", "status", "COMPLETED");

        String rateLimitKey = "test-ratelimit-" + UUID.randomUUID();
        // Exhaust 100 permits limit for FINANCIAL category
        for (int i = 0; i < 100; i++) {
            rateLimiterService.checkAndRecordFinancial(rateLimitKey);
        }
        // 101st attempt must throw RateLimitExceededException
        assertThatThrownBy(() -> rateLimiterService.checkAndRecordFinancial(rateLimitKey))
                .isInstanceOf(com.parth.ledger.security.ratelimit.RateLimitExceededException.class);

        double rateLimitRejectionsAfter = getCounterValue("ledger.ratelimit.rejections.total", "category", "FINANCIAL");
        double completedTransfersAfter = getCounterValue("ledger.operation.total", "operation", "TRANSFER", "status", "COMPLETED");

        assertThat(rateLimitRejectionsAfter).isGreaterThanOrEqualTo(rateLimitRejectionsBefore + 1.0);
        assertThat(completedTransfersAfter).isEqualTo(completedTransfersBefore);
    }

    @Test
    @DisplayName("13 & 14. Idempotent replay does NOT increment completed count, but DOES increment replay metric")
    void idempotentReplayMetrics() {
        double completedBefore = getCounterValue("ledger.operation.total", "operation", "TRANSFER", "status", "COMPLETED");
        double replayBefore = getCounterValue("ledger.idempotency.total", "operation", "TRANSFER", "outcome", "REPLAY");

        String key = "tx-replay-metric-" + UUID.randomUUID();
        TransferRequestDto request = new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("100.0000"),
                "INR"
        );

        // First execution
        TransferResponseDto resp1 = transferService.executeTransfer(key, request);
        assertThat(resp1).isNotNull();

        double completedAfterFirst = getCounterValue("ledger.operation.total", "operation", "TRANSFER", "status", "COMPLETED");
        assertThat(completedAfterFirst).isEqualTo(completedBefore + 1.0);

        // Second execution (replay)
        TransferResponseDto resp2 = transferService.executeTransfer(key, request);
        assertThat(resp2.transactionId()).isEqualTo(resp1.transactionId());

        double completedAfterSecond = getCounterValue("ledger.operation.total", "operation", "TRANSFER", "status", "COMPLETED");
        double replayAfterSecond = getCounterValue("ledger.idempotency.total", "operation", "TRANSFER", "outcome", "REPLAY");

        // Completed count MUST NOT increase on replay
        assertThat(completedAfterSecond).isEqualTo(completedAfterFirst);
        // Replay metric MUST increment
        assertThat(replayAfterSecond).isEqualTo(replayBefore + 1.0);
    }

    @Test
    @DisplayName("15. Idempotent conflict increments rejection/conflict metrics")
    void idempotentConflictMetrics() {
        double conflictBefore = getCounterValue("ledger.idempotency.total", "operation", "TRANSFER", "outcome", "CONFLICT");
        double rejectedBefore = getCounterValue("ledger.operation.total", "operation", "TRANSFER", "status", "REJECTED");

        String key = "tx-conflict-metric-" + UUID.randomUUID();
        TransferRequestDto request1 = new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("100.0000"),
                "INR"
        );
        transferService.executeTransfer(key, request1);

        // Conflict request with different amount
        TransferRequestDto request2 = new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("999.0000"),
                "INR"
        );

        assertThatThrownBy(() -> transferService.executeTransfer(key, request2))
                .isInstanceOf(IdempotencyConflictException.class);

        double conflictAfter = getCounterValue("ledger.idempotency.total", "operation", "TRANSFER", "outcome", "CONFLICT");
        double rejectedAfter = getCounterValue("ledger.operation.total", "operation", "TRANSFER", "status", "REJECTED");

        assertThat(conflictAfter).isEqualTo(conflictBefore + 1.0);
        assertThat(rejectedAfter).isEqualTo(rejectedBefore + 1.0);
    }

    @Test
    @DisplayName("16. Reconciliation run increments reconciliation metrics")
    void reconciliationRunMetrics() {
        double runsBefore = getCounterValue("ledger.reconciliation.runs.total", "scope", "USER_ACCOUNTS");
        double accountsCheckedBefore = getCounterValue("ledger.reconciliation.accounts.checked.total", "scope", "USER_ACCOUNTS");
        double consistentBefore = getCounterValue("ledger.reconciliation.accounts.consistent.total", "scope", "USER_ACCOUNTS");

        reconciliationService.reconcileUserAccounts();

        double runsAfter = getCounterValue("ledger.reconciliation.runs.total", "scope", "USER_ACCOUNTS");
        double accountsCheckedAfter = getCounterValue("ledger.reconciliation.accounts.checked.total", "scope", "USER_ACCOUNTS");
        double consistentAfter = getCounterValue("ledger.reconciliation.accounts.consistent.total", "scope", "USER_ACCOUNTS");

        assertThat(runsAfter).isEqualTo(runsBefore + 1.0);
        assertThat(accountsCheckedAfter).isGreaterThanOrEqualTo(accountsCheckedBefore + 1.0);
        assertThat(consistentAfter).isGreaterThanOrEqualTo(consistentBefore + 1.0);
    }

    @Test
    @DisplayName("17. Reconciliation discrepancy increments discrepancy metrics")
    void reconciliationDiscrepancyMetrics() {
        double discrepanciesBefore = getCounterValue("ledger.reconciliation.discrepancies.total", "scope", "SINGLE_ACCOUNT");

        // Manually introduce balance divergence in database for aliceAccount
        jdbcTemplate.update("UPDATE accounts SET balance = balance + 500 WHERE id = ?", aliceAccount.getId());

        reconciliationService.reconcileAccount(aliceAccount.getId());

        double discrepanciesAfter = getCounterValue("ledger.reconciliation.discrepancies.total", "scope", "SINGLE_ACCOUNT");
        assertThat(discrepanciesAfter).isEqualTo(discrepanciesBefore + 1.0);
    }

    @Test
    @DisplayName("Finding 3: Metric emission matches transaction commit semantics; rollbacks do NOT increment completed metric")
    void testCommitVsRollbackSemantics() {
        double completedBefore = getCounterValue("ledger.operation.total", "operation", "TRANSFER", "status", "COMPLETED");
        double rejectedBefore = getCounterValue("ledger.operation.total", "operation", "TRANSFER", "status", "REJECTED");

        // 1. Transaction that fails / rolls back due to insufficient balance
        TransferRequestDto excessRequest = new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("999999.0000"), // Alice only has 10,000
                "INR"
        );
        String failedKey = "tx-rollback-" + UUID.randomUUID();

        assertThatThrownBy(() -> transferService.executeTransfer(failedKey, excessRequest))
                .isInstanceOf(com.parth.ledger.transaction.exception.InsufficientBalanceException.class);

        double completedAfterRollback = getCounterValue("ledger.operation.total", "operation", "TRANSFER", "status", "COMPLETED");
        double rejectedAfterRollback = getCounterValue("ledger.operation.total", "operation", "TRANSFER", "status", "REJECTED");

        // COMPLETED metric MUST NOT increment on rollback/failure
        assertThat(completedAfterRollback).isEqualTo(completedBefore);
        // REJECTED metric increments
        assertThat(rejectedAfterRollback).isEqualTo(rejectedBefore + 1.0);

        // 2. Successful transaction that commits
        TransferRequestDto validRequest = new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("250.0000"),
                "INR"
        );
        String successKey = "tx-commit-" + UUID.randomUUID();
        transferService.executeTransfer(successKey, validRequest);

        double completedAfterCommit = getCounterValue("ledger.operation.total", "operation", "TRANSFER", "status", "COMPLETED");
        assertThat(completedAfterCommit).isEqualTo(completedBefore + 1.0);
    }

    @Test
    @DisplayName("Finding 4: Replay latency is recorded separately and does not distort execution latency")
    void testReplayLatencySeparatedFromExecutionLatency() {
        long executionTimerBefore = getTimerCount("ledger.operation.duration", "operation", "TRANSFER", "status", "COMPLETED");
        long replayTimerBefore = getTimerCount("ledger.idempotency.duration", "operation", "TRANSFER", "outcome", "REPLAY");
        double completedBefore = getCounterValue("ledger.operation.total", "operation", "TRANSFER", "status", "COMPLETED");
        double replayCountBefore = getCounterValue("ledger.idempotency.total", "operation", "TRANSFER", "outcome", "REPLAY");

        String key = "tx-separate-latency-" + UUID.randomUUID();
        TransferRequestDto request = new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("120.0000"),
                "INR"
        );

        // First execution (Fresh execution)
        TransferResponseDto resp1 = transferService.executeTransfer(key, request);
        assertThat(resp1).isNotNull();

        long executionTimerAfterFirst = getTimerCount("ledger.operation.duration", "operation", "TRANSFER", "status", "COMPLETED");
        long replayTimerAfterFirst = getTimerCount("ledger.idempotency.duration", "operation", "TRANSFER", "outcome", "REPLAY");
        double completedAfterFirst = getCounterValue("ledger.operation.total", "operation", "TRANSFER", "status", "COMPLETED");

        assertThat(executionTimerAfterFirst).isEqualTo(executionTimerBefore + 1L);
        assertThat(replayTimerAfterFirst).isEqualTo(replayTimerBefore);
        assertThat(completedAfterFirst).isEqualTo(completedBefore + 1.0);

        // Second execution (Idempotent replay)
        TransferResponseDto resp2 = transferService.executeTransfer(key, request);
        assertThat(resp2.transactionId()).isEqualTo(resp1.transactionId());

        long executionTimerAfterReplay = getTimerCount("ledger.operation.duration", "operation", "TRANSFER", "status", "COMPLETED");
        long replayTimerAfterReplay = getTimerCount("ledger.idempotency.duration", "operation", "TRANSFER", "outcome", "REPLAY");
        double completedAfterReplay = getCounterValue("ledger.operation.total", "operation", "TRANSFER", "status", "COMPLETED");
        double replayCountAfterReplay = getCounterValue("ledger.idempotency.total", "operation", "TRANSFER", "outcome", "REPLAY");

        // Execution duration timer MUST NOT increment on replay!
        assertThat(executionTimerAfterReplay).isEqualTo(executionTimerAfterFirst);
        // Completed count MUST NOT increment on replay!
        assertThat(completedAfterReplay).isEqualTo(completedAfterFirst);
        // Replay duration timer MUST increment!
        assertThat(replayTimerAfterReplay).isEqualTo(replayTimerBefore + 1L);
        // Replay total counter MUST increment!
        assertThat(replayCountAfterReplay).isEqualTo(replayCountBefore + 1.0);
    }

    @Test
    @DisplayName("Finding 5: Reconciliation accounts checked metric reflects actual work performed")
    void testReconciliationAccountsCheckedReflectsActualWork() {
        double checkedBefore = getCounterValue("ledger.reconciliation.accounts.checked.total", "scope", "SINGLE_ACCOUNT");
        double systemCheckedBefore = getCounterValue("ledger.reconciliation.accounts.checked.total", "scope", "SYSTEM");

        // 1. Nonexistent account reconciliation -> throws AccountNotFoundException, checked does NOT increment
        UUID randomId = UUID.randomUUID();
        assertThatThrownBy(() -> reconciliationService.reconcileAccount(randomId))
                .isInstanceOf(AccountNotFoundException.class);
        double checkedAfterMissing = getCounterValue("ledger.reconciliation.accounts.checked.total", "scope", "SINGLE_ACCOUNT");
        assertThat(checkedAfterMissing).isEqualTo(checkedBefore);

        // 2. Unauthorized account reconciliation -> Alice tries to reconcile Bob's account
        assertThatThrownBy(() -> reconciliationService.reconcileAccount(bobAccount.getId()))
                .isInstanceOf(AccountOwnershipException.class);
        double checkedAfterUnauthorized = getCounterValue("ledger.reconciliation.accounts.checked.total", "scope", "SINGLE_ACCOUNT");
        assertThat(checkedAfterUnauthorized).isEqualTo(checkedBefore);

        // 3. Successful reconciliation of Alice's account -> increments by EXACTLY 1
        reconciliationService.reconcileAccount(aliceAccount.getId());
        double checkedAfterValid = getCounterValue("ledger.reconciliation.accounts.checked.total", "scope", "SINGLE_ACCOUNT");
        assertThat(checkedAfterValid).isEqualTo(checkedBefore + 1.0);

        // 4. Direct reconciliation of nonexistent account -> does NOT increment SYSTEM checked
        assertThatThrownBy(() -> reconciliationService.reconcileAccountDirectly(randomId))
                .isInstanceOf(AccountNotFoundException.class);
        double systemCheckedAfterMissing = getCounterValue("ledger.reconciliation.accounts.checked.total", "scope", "SYSTEM");
        assertThat(systemCheckedAfterMissing).isEqualTo(systemCheckedBefore);

        // 5. Direct reconciliation of existing clearing account -> increments SYSTEM checked by EXACTLY 1
        reconciliationService.reconcileAccountDirectly(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        double systemCheckedAfterValid = getCounterValue("ledger.reconciliation.accounts.checked.total", "scope", "SYSTEM");
        assertThat(systemCheckedAfterValid).isEqualTo(systemCheckedBefore + 1.0);
    }
}
