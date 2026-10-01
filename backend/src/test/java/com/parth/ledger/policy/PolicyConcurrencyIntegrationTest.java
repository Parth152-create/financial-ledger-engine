package com.parth.ledger.policy;

import com.parth.ledger.BaseIntegrationTest;
import com.parth.ledger.account.Account;
import com.parth.ledger.account.AccountRepository;
import com.parth.ledger.ledger.LedgerEntryRepository;
import com.parth.ledger.reconciliation.ReconciliationStatus;
import com.parth.ledger.reconciliation.service.ReconciliationService;
import com.parth.ledger.transaction.TransactionRepository;
import com.parth.ledger.transaction.TransactionType;
import com.parth.ledger.transaction.dto.TransferRequestDto;
import com.parth.ledger.transaction.service.TransferService;
import com.parth.ledger.user.User;
import com.parth.ledger.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyConcurrencyIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TransferService transferService;

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

        aliceUser = userRepository.save(new User("alice.concurrent@ledger.com", "Alice Concurrent"));
        bobUser = userRepository.save(new User("bob.concurrent@ledger.com", "Bob Concurrent"));

        aliceAccount = accountRepository.save(new Account(aliceUser, "INR", new BigDecimal("100000.0000")));
        bobAccount = accountRepository.save(new Account(bobUser, "INR", new BigDecimal("100000.0000")));
    }

    @Test
    @DisplayName("27.1 Mandatory: Concurrent requests against shared DAILY_TRANSACTION_AMOUNT limit (10 concurrent requests of ₹2000 against ₹10,000 limit -> exactly 5 succeed, 5 fail, usage = ₹10,000)")
    void verifyConcurrentDailyAmountLimitEnforcement() throws InterruptedException {
        // Daily transfer limit = 10,000 INR
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

        int totalThreads = 10;
        BigDecimal requestAmount = new BigDecimal("2000.0000");

        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(totalThreads);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger policyRejectionCount = new AtomicInteger(0);
        List<Throwable> otherFailures = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < totalThreads; i++) {
            final String key = "conc-amt-tx-" + i;
            executor.submit(() -> {
                try {
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken("alice.concurrent@ledger.com", null, Collections.emptyList())
                    );
                    startLatch.await();
                    transferService.executeTransfer(key, new TransferRequestDto(
                            aliceAccount.getId(),
                            bobAccount.getId(),
                            requestAmount,
                            "INR"
                    ));
                    successCount.incrementAndGet();
                } catch (PolicyViolationException ex) {
                    if (ex.getErrorCode() == PolicyErrorCode.POLICY_DAILY_AMOUNT_LIMIT_EXCEEDED) {
                        policyRejectionCount.incrementAndGet();
                    } else {
                        otherFailures.add(ex);
                    }
                } catch (Throwable t) {
                    otherFailures.add(t);
                } finally {
                    endLatch.countDown();
                }
            });
        }

        // Fire all threads simultaneously
        startLatch.countDown();
        boolean completed = endLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(otherFailures).isEmpty();

        // Exactly 5 requests can succeed (5 * 2,000 = 10,000)
        assertThat(successCount.get()).isEqualTo(5);
        assertThat(policyRejectionCount.get()).isEqualTo(5);

        // Authoritative Database Validation:
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        DailyPolicyUsage usage = dailyPolicyUsageRepository.findByAccountIdAndTransactionTypeAndUsageDate(
                aliceAccount.getId(),
                TransactionType.TRANSFER,
                today
        ).orElseThrow();

        // Usage must NEVER overshoot 10,000 (never 12,000, 14,000, 20,000)
        assertThat(usage.getAmountUsed()).isEqualByComparingTo("10000.0000");
        assertThat(usage.getTransactionCount()).isEqualTo(5);

        // Balance check: exactly 10,000 debited from Alice and credited to Bob
        Account updatedAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        assertThat(updatedAlice.getBalance()).isEqualByComparingTo("90000.0000");
        Account updatedBob = accountRepository.findById(bobAccount.getId()).orElseThrow();
        assertThat(updatedBob.getBalance()).isEqualByComparingTo("110000.0000");
    }

    @Test
    @DisplayName("27.2 Mandatory: Concurrent requests against shared DAILY_TRANSACTION_COUNT limit (10 concurrent requests against count limit of 5 -> exactly 5 succeed, 5 fail, final count = 5)")
    void verifyConcurrentDailyCountLimitEnforcement() throws InterruptedException {
        // Daily transfer count limit = 5
        financialPolicyRepository.save(new FinancialPolicy(
                aliceAccount.getId(),
                PolicyScope.ACCOUNT,
                TransactionType.TRANSFER,
                PolicyType.DAILY_TRANSACTION_COUNT,
                null,
                5,
                "INR",
                true
        ));

        int totalThreads = 10;
        BigDecimal requestAmount = new BigDecimal("100.0000");

        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(totalThreads);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger policyRejectionCount = new AtomicInteger(0);
        List<Throwable> otherFailures = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < totalThreads; i++) {
            final String key = "conc-count-tx-" + i;
            executor.submit(() -> {
                try {
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken("alice.concurrent@ledger.com", null, Collections.emptyList())
                    );
                    startLatch.await();
                    transferService.executeTransfer(key, new TransferRequestDto(
                            aliceAccount.getId(),
                            bobAccount.getId(),
                            requestAmount,
                            "INR"
                    ));
                    successCount.incrementAndGet();
                } catch (PolicyViolationException ex) {
                    if (ex.getErrorCode() == PolicyErrorCode.POLICY_DAILY_COUNT_LIMIT_EXCEEDED) {
                        policyRejectionCount.incrementAndGet();
                    } else {
                        otherFailures.add(ex);
                    }
                } catch (Throwable t) {
                    otherFailures.add(t);
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = endLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(otherFailures).isEmpty();

        // Exactly 5 requests can succeed
        assertThat(successCount.get()).isEqualTo(5);
        assertThat(policyRejectionCount.get()).isEqualTo(5);

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        DailyPolicyUsage usage = dailyPolicyUsageRepository.findByAccountIdAndTransactionTypeAndUsageDate(
                aliceAccount.getId(),
                TransactionType.TRANSFER,
                today
        ).orElseThrow();

        assertThat(usage.getTransactionCount()).isEqualTo(5);
        assertThat(usage.getAmountUsed()).isEqualByComparingTo("500.0000");

        Account updatedAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        assertThat(updatedAlice.getBalance()).isEqualByComparingTo("99500.0000");
        Account updatedBob = accountRepository.findById(bobAccount.getId()).orElseThrow();
        assertThat(updatedBob.getBalance()).isEqualByComparingTo("100500.0000");
    }

    @Test
    @DisplayName("27.3 Mandatory: Concurrent transfers to same destination with ACCOUNT_BALANCE_LIMIT (balance never exceeds configured maximum)")
    void verifyConcurrentDestinationBalanceLimitEnforcement() throws InterruptedException {
        // Bob starts with balance 200. Max balance limit is 1,000 INR.
        Account targetAccount = accountRepository.save(new Account(bobUser, "INR", new BigDecimal("200.0000")));

        financialPolicyRepository.save(new FinancialPolicy(
                targetAccount.getId(),
                PolicyScope.ACCOUNT,
                null,
                PolicyType.ACCOUNT_BALANCE_LIMIT,
                new BigDecimal("1000.0000"),
                null,
                "INR",
                true
        ));

        // Create 10 distinct funded sender accounts with their own users
        int senderCount = 10;
        List<Account> senders = new ArrayList<>();
        List<User> senderUsers = new ArrayList<>();
        for (int i = 0; i < senderCount; i++) {
            User senderUser = userRepository.save(new User("sender" + i + "@ledger.com", "Sender " + i));
            senderUsers.add(senderUser);
            senders.add(accountRepository.save(new Account(senderUser, "INR", new BigDecimal("5000.0000"))));
        }

        BigDecimal transferAmount = new BigDecimal("200.0000");
        ExecutorService executor = Executors.newFixedThreadPool(senderCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(senderCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger policyRejectionCount = new AtomicInteger(0);
        List<Throwable> otherFailures = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < senderCount; i++) {
            final int idx = i;
            final String key = "conc-dest-bal-tx-" + i;
            executor.submit(() -> {
                try {
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken(senderUsers.get(idx).getEmail(), null, Collections.emptyList())
                    );
                    startLatch.await();
                    transferService.executeTransfer(key, new TransferRequestDto(
                            senders.get(idx).getId(),
                            targetAccount.getId(),
                            transferAmount,
                            "INR"
                    ));
                    successCount.incrementAndGet();
                } catch (PolicyViolationException ex) {
                    if (ex.getErrorCode() == PolicyErrorCode.POLICY_BALANCE_LIMIT_EXCEEDED) {
                        policyRejectionCount.incrementAndGet();
                    } else {
                        otherFailures.add(ex);
                    }
                } catch (Throwable t) {
                    otherFailures.add(t);
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = endLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(otherFailures).isEmpty();

        // Initial 200 + 4 * 200 = 1000. Exactly 4 transfers must succeed, 6 must be rejected!
        assertThat(successCount.get()).isEqualTo(4);
        assertThat(policyRejectionCount.get()).isEqualTo(6);

        Account finalTarget = accountRepository.findById(targetAccount.getId()).orElseThrow();
        // Balance must NEVER exceed 1000
        assertThat(finalTarget.getBalance()).isEqualByComparingTo("1000.0000");
    }

    @Test
    @DisplayName("27.4 Mandatory: Concurrent transfers in opposite directions (A->B and B->A) maintain deterministic locking without deadlock")
    void verifyOppositeDirectionTransfersWithoutDeadlock() throws InterruptedException {
        Account accA = accountRepository.save(new Account(aliceUser, "INR", new BigDecimal("5000.0000")));
        Account accB = accountRepository.save(new Account(bobUser, "INR", new BigDecimal("5000.0000")));

        // Global limits configured
        financialPolicyRepository.save(new FinancialPolicy(
                null,
                PolicyScope.GLOBAL,
                TransactionType.TRANSFER,
                PolicyType.DAILY_TRANSACTION_AMOUNT,
                new BigDecimal("100000.0000"),
                null,
                "INR",
                true
        ));

        int transfersPerDirection = 10;
        int totalTransfers = transfersPerDirection * 2;
        BigDecimal transferAmount = new BigDecimal("50.0000");

        ExecutorService executor = Executors.newFixedThreadPool(totalTransfers);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(totalTransfers);

        AtomicInteger successCount = new AtomicInteger(0);
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < transfersPerDirection; i++) {
            final String keyAB = "opp-ab-" + i;
            executor.submit(() -> {
                try {
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken("alice.concurrent@ledger.com", null, Collections.emptyList())
                    );
                    startLatch.await();
                    transferService.executeTransfer(keyAB, new TransferRequestDto(
                            accA.getId(),
                            accB.getId(),
                            transferAmount,
                            "INR"
                    ));
                    successCount.incrementAndGet();
                } catch (Throwable t) {
                    failures.add(t);
                } finally {
                    endLatch.countDown();
                }
            });

            final String keyBA = "opp-ba-" + i;
            executor.submit(() -> {
                try {
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken("bob.concurrent@ledger.com", null, Collections.emptyList())
                    );
                    startLatch.await();
                    transferService.executeTransfer(keyBA, new TransferRequestDto(
                            accB.getId(),
                            accA.getId(),
                            transferAmount,
                            "INR"
                    ));
                    successCount.incrementAndGet();
                } catch (Throwable t) {
                    failures.add(t);
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = endLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(failures).isEmpty();
        assertThat(successCount.get()).isEqualTo(totalTransfers);

        Account finalA = accountRepository.findById(accA.getId()).orElseThrow();
        Account finalB = accountRepository.findById(accB.getId()).orElseThrow();

        // 10 transfers each way with amount 50 means net change is 0
        assertThat(finalA.getBalance()).isEqualByComparingTo("5000.0000");
        assertThat(finalB.getBalance()).isEqualByComparingTo("5000.0000");

        // Sum of balances is strictly conserved
        assertThat(finalA.getBalance().add(finalB.getBalance())).isEqualByComparingTo("10000.0000");

        // Verify all 20 transactions produced 40 ledger entries exactly
        assertThat(ledgerEntryRepository.count()).isEqualTo(40);
    }
}
