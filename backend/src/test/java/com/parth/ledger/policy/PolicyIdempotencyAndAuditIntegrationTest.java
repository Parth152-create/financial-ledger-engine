package com.parth.ledger.policy;

import com.parth.ledger.BaseIntegrationTest;
import com.parth.ledger.account.Account;
import com.parth.ledger.account.AccountRepository;
import com.parth.ledger.audit.AuditEvent;
import com.parth.ledger.audit.AuditEventRepository;
import com.parth.ledger.audit.AuditEventType;
import com.parth.ledger.deposit.dto.DepositRequestDto;
import com.parth.ledger.deposit.service.DepositService;
import com.parth.ledger.policy.dto.CreatePolicyRequestDto;
import com.parth.ledger.policy.dto.FinancialPolicyResponseDto;
import com.parth.ledger.policy.dto.UpdatePolicyRequestDto;
import com.parth.ledger.system.SystemFundingService;
import com.parth.ledger.transaction.TransactionRepository;
import com.parth.ledger.transaction.TransactionType;
import com.parth.ledger.transaction.dto.TransactionResponseDto;
import com.parth.ledger.transaction.dto.TransferRequestDto;
import com.parth.ledger.transaction.dto.TransferResponseDto;
import com.parth.ledger.transaction.exception.IdempotencyConflictException;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PolicyIdempotencyAndAuditIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TransferService transferService;

    @Autowired
    private DepositService depositService;

    @Autowired
    private WithdrawalService withdrawalService;

    @Autowired
    private PolicyService policyService;

    @Autowired
    private SystemFundingService systemFundingService;

    @Autowired
    private FinancialPolicyRepository financialPolicyRepository;

    @Autowired
    private DailyPolicyUsageRepository dailyPolicyUsageRepository;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private com.parth.ledger.ledger.LedgerEntryRepository ledgerEntryRepository;

    private User aliceUser;
    private User bobUser;
    private User adminUser;
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

        aliceUser = userRepository.save(new User("alice.audit@ledger.com", "Alice"));
        bobUser = userRepository.save(new User("bob.audit@ledger.com", "Bob"));
        adminUser = userRepository.save(new User("admin.audit@ledger.com", "Admin"));

        aliceAccount = accountRepository.save(new Account(aliceUser, "INR", new BigDecimal("50000.0000")));
        bobAccount = accountRepository.save(new Account(bobUser, "INR", new BigDecimal("50000.0000")));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice.audit@ledger.com", null, Collections.emptyList())
        );
    }

    @Test
    @DisplayName("26 & 28. Successful idempotent replay returns original result and does NOT consume limit twice")
    void verifyIdempotentReplayDoesNotConsumeLimitTwice() {
        // Daily amount limit = 10,000, Count limit = 2
        financialPolicyRepository.save(new FinancialPolicy(
                aliceAccount.getId(),
                PolicyScope.ACCOUNT,
                TransactionType.TRANSFER,
                PolicyType.DAILY_TRANSACTION_AMOUNT,
                new BigDecimal("10000.0000"),
                null,
                "INR",
                true
        ));
        financialPolicyRepository.save(new FinancialPolicy(
                aliceAccount.getId(),
                PolicyScope.ACCOUNT,
                TransactionType.TRANSFER,
                PolicyType.DAILY_TRANSACTION_COUNT,
                null,
                2,
                "INR",
                true
        ));

        String key = "idem-tx-policy-01";
        TransferRequestDto request = new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("8000.0000"),
                "INR"
        );

        // First execution succeeds
        TransferResponseDto res1 = transferService.executeTransfer(key, request);
        assertThat(res1.status()).isEqualTo(com.parth.ledger.transaction.TransactionStatus.COMPLETED);

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        DailyPolicyUsage usageAfterFirst = dailyPolicyUsageRepository.findByAccountIdAndTransactionTypeAndUsageDate(
                aliceAccount.getId(),
                TransactionType.TRANSFER,
                today
        ).orElseThrow();
        assertThat(usageAfterFirst.getAmountUsed()).isEqualByComparingTo("8000.0000");
        assertThat(usageAfterFirst.getTransactionCount()).isEqualTo(1);

        // Clear Redis cache to force DB-level idempotency replay
        clearRedis();

        // Idempotent retry with the exact same key:
        // If it re-evaluated the policy, 8,000 + 8,000 = 16,000 would exceed 10,000!
        // But because idempotency lookup happens before policy evaluation, it returns original result!
        TransferResponseDto res2 = transferService.executeTransfer(key, request);
        assertThat(res2.transactionId()).isEqualTo(res1.transactionId());
        assertThat(res2.status()).isEqualTo(com.parth.ledger.transaction.TransactionStatus.COMPLETED);

        // Daily policy usage remains exactly 8,000 and count 1 (NOT consumed twice!)
        DailyPolicyUsage usageAfterRetry = dailyPolicyUsageRepository.findByAccountIdAndTransactionTypeAndUsageDate(
                aliceAccount.getId(),
                TransactionType.TRANSFER,
                today
        ).orElseThrow();
        assertThat(usageAfterRetry.getAmountUsed()).isEqualByComparingTo("8000.0000");
        assertThat(usageAfterRetry.getTransactionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("27. Failed policy request does NOT consume limit")
    void verifyFailedPolicyRequestDoesNotConsumeLimit() {
        // Daily amount limit = 5,000
        financialPolicyRepository.save(new FinancialPolicy(
                aliceAccount.getId(),
                PolicyScope.ACCOUNT,
                TransactionType.TRANSFER,
                PolicyType.DAILY_TRANSACTION_AMOUNT,
                new BigDecimal("5000.0000"),
                null,
                "INR",
                true
        ));

        // Attempt 6,000 (rejected by policy)
        assertThatThrownBy(() -> transferService.executeTransfer("tx-failed-policy", new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("6000.0000"),
                "INR"
        ))).isInstanceOf(PolicyViolationException.class);

        // Verify daily usage is NOT consumed
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Optional<DailyPolicyUsage> usage = dailyPolicyUsageRepository.findByAccountIdAndTransactionTypeAndUsageDate(
                aliceAccount.getId(),
                TransactionType.TRANSFER,
                today
        );
        if (usage.isPresent()) {
            assertThat(usage.get().getAmountUsed()).isEqualByComparingTo("0.0000");
            assertThat(usage.get().getTransactionCount()).isEqualTo(0);
        }

        // Subsequent valid transfer of 5,000 can still succeed completely
        TransferResponseDto res = transferService.executeTransfer("tx-after-failed", new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("5000.0000"),
                "INR"
        ));
        assertThat(res.status()).isEqualTo(com.parth.ledger.transaction.TransactionStatus.COMPLETED);
    }

    @Test
    @DisplayName("29. Same idempotency key cannot be used for different financial operation")
    void verifyIdempotencyConflictOnDifferentParameters() {
        TransferRequestDto req1 = new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("1000.0000"),
                "INR"
        );
        transferService.executeTransfer("key-conflict-check", req1);

        TransferRequestDto req2 = new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("2000.0000"), // Different amount
                "INR"
        );

        assertThatThrownBy(() -> transferService.executeTransfer("key-conflict-check", req2))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    @DisplayName("30. Policy configuration changes generate audit events (POLICY_CREATED, POLICY_UPDATED, POLICY_DELETED)")
    void verifyPolicyConfigGeneratesAuditEvents() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "admin.audit@ledger.com",
                        null,
                        Collections.singletonList(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))
                )
        );

        // 1. Create Policy
        FinancialPolicyResponseDto created = policyService.createPolicy(new CreatePolicyRequestDto(
                null,
                PolicyScope.GLOBAL,
                TransactionType.TRANSFER,
                PolicyType.MAX_TRANSACTION_AMOUNT,
                new BigDecimal("50000.0000"),
                null,
                "INR",
                true
        ));

        List<AuditEvent> createdEvents = auditEventRepository.findByEventType(AuditEventType.POLICY_CREATED);
        assertThat(createdEvents).hasSize(1);
        assertThat(createdEvents.get(0).getEntityId()).isEqualTo(created.id());
        assertThat(createdEvents.get(0).getMetadata()).containsEntry("policyType", "MAX_TRANSACTION_AMOUNT");

        // 2. Update Policy
        policyService.updatePolicy(created.id(), new UpdatePolicyRequestDto(new BigDecimal("75000.0000"), null, false));

        List<AuditEvent> updatedEvents = auditEventRepository.findByEventType(AuditEventType.POLICY_UPDATED);
        assertThat(updatedEvents).hasSize(1);
        assertThat(updatedEvents.get(0).getEntityId()).isEqualTo(created.id());

        // 3. Delete Policy
        policyService.deletePolicy(created.id());

        List<AuditEvent> deletedEvents = auditEventRepository.findByEventType(AuditEventType.POLICY_DELETED);
        assertThat(deletedEvents).hasSize(1);
        assertThat(deletedEvents.get(0).getEntityId()).isEqualTo(created.id());
    }

    @Test
    @DisplayName("31. Policy rejection produces correct audit event (TRANSFER_REJECTED_POLICY, DEPOSIT_REJECTED_POLICY, WITHDRAWAL_REJECTED_POLICY)")
    void verifyPolicyRejectionProducesAuditEvents() {
        // Configure strict policies for transfer, deposit, and withdrawal
        financialPolicyRepository.save(new FinancialPolicy(
                aliceAccount.getId(),
                PolicyScope.ACCOUNT,
                TransactionType.TRANSFER,
                PolicyType.MAX_TRANSACTION_AMOUNT,
                new BigDecimal("100.0000"),
                null,
                "INR",
                true
        ));
        financialPolicyRepository.save(new FinancialPolicy(
                aliceAccount.getId(),
                PolicyScope.ACCOUNT,
                TransactionType.DEPOSIT,
                PolicyType.MAX_TRANSACTION_AMOUNT,
                new BigDecimal("100.0000"),
                null,
                "INR",
                true
        ));
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

        // 1. Transfer violation
        assertThatThrownBy(() -> transferService.executeTransfer("tx-viol-1", new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("500.0000"),
                "INR"
        ))).isInstanceOf(PolicyViolationException.class);

        List<AuditEvent> transferRejections = auditEventRepository.findByEventType(AuditEventType.TRANSFER_REJECTED_POLICY);
        assertThat(transferRejections).hasSize(1);
        assertThat(transferRejections.get(0).getEntityId()).isEqualTo(aliceAccount.getId());
        assertThat(transferRejections.get(0).getMetadata()).containsEntry("policyType", "POLICY_TRANSACTION_LIMIT_EXCEEDED");

        // 2. Deposit violation
        assertThatThrownBy(() -> depositService.executeDeposit("dep-viol-1", new DepositRequestDto(
                aliceAccount.getId(),
                new BigDecimal("500.0000"),
                "INR"
        ))).isInstanceOf(PolicyViolationException.class);

        List<AuditEvent> depositRejections = auditEventRepository.findByEventType(AuditEventType.DEPOSIT_REJECTED_POLICY);
        assertThat(depositRejections).hasSize(1);
        assertThat(depositRejections.get(0).getEntityId()).isEqualTo(aliceAccount.getId());

        // 3. Withdrawal violation
        assertThatThrownBy(() -> withdrawalService.executeWithdrawal("with-viol-1", new WithdrawalRequestDto(
                aliceAccount.getId(),
                new BigDecimal("500.0000"),
                "INR"
        ))).isInstanceOf(PolicyViolationException.class);

        List<AuditEvent> withdrawalRejections = auditEventRepository.findByEventType(AuditEventType.WITHDRAWAL_REJECTED_POLICY);
        assertThat(withdrawalRejections).hasSize(1);
        assertThat(withdrawalRejections.get(0).getEntityId()).isEqualTo(aliceAccount.getId());
    }

    @Test
    @DisplayName("32. M2: Repeated policy-rejection requests with SAME idempotency key do NOT duplicate audit events")
    void verifyRepeatedPolicyRejectionsDoNotDuplicateAuditEvents() {
        // Configure strict transfer & deposit policy: max = 100
        financialPolicyRepository.save(new FinancialPolicy(
                aliceAccount.getId(),
                PolicyScope.ACCOUNT,
                TransactionType.TRANSFER,
                PolicyType.MAX_TRANSACTION_AMOUNT,
                new BigDecimal("100.0000"),
                null,
                "INR",
                true
        ));
        financialPolicyRepository.save(new FinancialPolicy(
                aliceAccount.getId(),
                PolicyScope.ACCOUNT,
                TransactionType.DEPOSIT,
                PolicyType.MAX_TRANSACTION_AMOUNT,
                new BigDecimal("100.0000"),
                null,
                "INR",
                true
        ));

        String idempotencyKey1 = "idemp-policy-rej-key-1";
        long initialLedgerCount = ledgerEntryRepository.count();

        // 1. First transfer attempt: fails policy check, emits exactly 1 audit event
        assertThatThrownBy(() -> transferService.executeTransfer(idempotencyKey1, new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("500.0000"),
                "INR"
        ))).isInstanceOf(PolicyViolationException.class);

        List<AuditEvent> transferRejectionsFirst = auditEventRepository.findByEventType(AuditEventType.TRANSFER_REJECTED_POLICY);
        assertThat(transferRejectionsFirst).hasSize(1);
        AuditEvent firstEvent = transferRejectionsFirst.get(0);
        assertThat(firstEvent.getMetadata()).containsKey("correlationId");

        // REQUIREMENT 11: Raw idempotency key MUST NOT appear in audit metadata
        assertThat(firstEvent.getMetadata()).doesNotContainKey("idempotencyKey");
        assertThat(firstEvent.getMetadata()).doesNotContainKey("cleanIdempotencyKey");
        assertThat(firstEvent.getMetadata().values()).noneMatch(v -> idempotencyKey1.equals(String.valueOf(v)));

        // REQUIREMENT 8, 9, 10: Rejected request leaves NO financial transaction, NO ledger entries, and NO policy usage
        assertThat(transactionRepository.findByIdempotencyKey(idempotencyKey1)).isEmpty();
        assertThat(ledgerEntryRepository.count()).isEqualTo(initialLedgerCount);
        assertThat(dailyPolicyUsageRepository.count()).isEqualTo(0);

        // 2. REQUIREMENT 2: Identical retry with SAME idempotency key & payload: fails policy check, but DOES NOT duplicate audit event
        assertThatThrownBy(() -> transferService.executeTransfer(idempotencyKey1, new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("500.0000"),
                "INR"
        ))).isInstanceOf(PolicyViolationException.class);

        List<AuditEvent> transferRejectionsRetry = auditEventRepository.findByEventType(AuditEventType.TRANSFER_REJECTED_POLICY);
        assertThat(transferRejectionsRetry).hasSize(1);

        // 3. REQUIREMENT 3: SAME key + materially DIFFERENT AMOUNT -> separate audit event (fingerprint differs)
        assertThatThrownBy(() -> transferService.executeTransfer(idempotencyKey1, new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("750.0000"),
                "INR"
        ))).isInstanceOf(PolicyViolationException.class);

        List<AuditEvent> transferRejectionsDiffAmount = auditEventRepository.findByEventType(AuditEventType.TRANSFER_REJECTED_POLICY);
        assertThat(transferRejectionsDiffAmount).hasSize(2);

        // 4. REQUIREMENT 4: SAME key + DIFFERENT DESTINATION -> separate audit event
        Account charlieAccount = accountRepository.save(new Account(bobUser, "INR", BigDecimal.ZERO));
        assertThatThrownBy(() -> transferService.executeTransfer(idempotencyKey1, new TransferRequestDto(
                aliceAccount.getId(),
                charlieAccount.getId(),
                new BigDecimal("500.0000"),
                "INR"
        ))).isInstanceOf(PolicyViolationException.class);

        List<AuditEvent> transferRejectionsDiffDest = auditEventRepository.findByEventType(AuditEventType.TRANSFER_REJECTED_POLICY);
        assertThat(transferRejectionsDiffDest).hasSize(3);

        // 5. REQUIREMENT 5: SAME key + DIFFERENT TRANSACTION TYPE (e.g. DEPOSIT) -> isolated namespace and emits its own deposit rejection
        assertThatThrownBy(() -> depositService.executeDeposit(idempotencyKey1, new DepositRequestDto(
                aliceAccount.getId(),
                new BigDecimal("500.0000"),
                "INR"
        ))).isInstanceOf(PolicyViolationException.class);

        List<AuditEvent> depositRejections = auditEventRepository.findByEventType(AuditEventType.DEPOSIT_REJECTED_POLICY);
        assertThat(depositRejections).hasSize(1);

        // Retry deposit with SAME key & payload: does not duplicate deposit audit event
        assertThatThrownBy(() -> depositService.executeDeposit(idempotencyKey1, new DepositRequestDto(
                aliceAccount.getId(),
                new BigDecimal("500.0000"),
                "INR"
        ))).isInstanceOf(PolicyViolationException.class);

        List<AuditEvent> depositRejectionsRetry = auditEventRepository.findByEventType(AuditEventType.DEPOSIT_REJECTED_POLICY);
        assertThat(depositRejectionsRetry).hasSize(1);
    }

    @Test
    @DisplayName("33. Audit: Concurrent identical rejected requests produce exactly 1 audit event via DB unique index")
    void testConcurrentIdenticalRejectedRequestsProduceSingleAuditEvent() throws Exception {
        financialPolicyRepository.save(new FinancialPolicy(
                aliceAccount.getId(),
                PolicyScope.ACCOUNT,
                TransactionType.TRANSFER,
                PolicyType.MAX_TRANSACTION_AMOUNT,
                new BigDecimal("100.0000"),
                null,
                "INR",
                true
        ));

        String concurrentKey = "concurrent-policy-rej-key-" + UUID.randomUUID();
        int threads = 4;
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch readyLatch = new java.util.concurrent.CountDownLatch(threads);
        java.util.concurrent.CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger policyExceptionsCount = new java.util.concurrent.atomic.AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken("alice.audit@ledger.com", null, Collections.emptyList())
                );
                readyLatch.countDown();
                try {
                    startLatch.await();
                    transferService.executeTransfer(concurrentKey, new TransferRequestDto(
                            aliceAccount.getId(),
                            bobAccount.getId(),
                            new BigDecimal("500.0000"),
                            "INR"
                    ));
                } catch (PolicyViolationException pve) {
                    policyExceptionsCount.incrementAndGet();
                } catch (Exception e) {
                    // unexpected exception
                }
            });
        }

        readyLatch.await(5, java.util.concurrent.TimeUnit.SECONDS);
        startLatch.countDown();
        executor.shutdown();
        boolean finished = executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(finished).isTrue();

        // All threads must fail policy evaluation cleanly
        assertThat(policyExceptionsCount.get()).isEqualTo(threads);

        // Database unique index ensures exactly ONE policy rejection audit event persisted
        List<AuditEvent> events = auditEventRepository.findByEventType(AuditEventType.TRANSFER_REJECTED_POLICY);
        assertThat(events).hasSize(1);
    }

    @Test
    @DisplayName("34. Audit: Concurrent materially different rejected requests produce separate audit events")
    void testConcurrentMateriallyDifferentRejectedRequestsProduceSeparateEvents() throws Exception {
        financialPolicyRepository.save(new FinancialPolicy(
                aliceAccount.getId(),
                PolicyScope.ACCOUNT,
                TransactionType.TRANSFER,
                PolicyType.MAX_TRANSACTION_AMOUNT,
                new BigDecimal("100.0000"),
                null,
                "INR",
                true
        ));

        String sharedKey = "shared-key-" + UUID.randomUUID();
        Account charlieAccount = accountRepository.save(new Account(bobUser, "INR", BigDecimal.ZERO));

        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch readyLatch = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger exceptionsCount = new java.util.concurrent.atomic.AtomicInteger(0);

        // Task 1: transfer to bobAccount for 500
        executor.submit(() -> {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken("alice.audit@ledger.com", null, Collections.emptyList())
            );
            readyLatch.countDown();
            try {
                startLatch.await();
                transferService.executeTransfer(sharedKey, new TransferRequestDto(
                        aliceAccount.getId(),
                        bobAccount.getId(),
                        new BigDecimal("500.0000"),
                        "INR"
                ));
            } catch (PolicyViolationException pve) {
                exceptionsCount.incrementAndGet();
            } catch (Exception ignored) {
            }
        });

        // Task 2: transfer to charlieAccount for 900
        executor.submit(() -> {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken("alice.audit@ledger.com", null, Collections.emptyList())
            );
            readyLatch.countDown();
            try {
                startLatch.await();
                transferService.executeTransfer(sharedKey, new TransferRequestDto(
                        aliceAccount.getId(),
                        charlieAccount.getId(),
                        new BigDecimal("900.0000"),
                        "INR"
                ));
            } catch (PolicyViolationException pve) {
                exceptionsCount.incrementAndGet();
            } catch (Exception ignored) {
            }
        });

        readyLatch.await(5, java.util.concurrent.TimeUnit.SECONDS);
        startLatch.countDown();
        executor.shutdown();
        executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);

        assertThat(exceptionsCount.get()).isEqualTo(2);

        // Materially different requests using same key produce distinct audit events
        List<AuditEvent> events = auditEventRepository.findByEventType(AuditEventType.TRANSFER_REJECTED_POLICY);
        assertThat(events).hasSize(2);
    }
}

