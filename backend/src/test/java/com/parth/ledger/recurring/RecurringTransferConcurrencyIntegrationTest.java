package com.parth.ledger.recurring;

import com.parth.ledger.BaseIntegrationTest;
import com.parth.ledger.account.Account;
import com.parth.ledger.account.AccountRepository;
import com.parth.ledger.system.SystemFundingService;
import com.parth.ledger.transaction.Transaction;
import com.parth.ledger.transaction.TransactionRepository;
import com.parth.ledger.transaction.TransactionType;
import com.parth.ledger.user.User;
import com.parth.ledger.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = "ledger.recurring-transfers.enabled=false")
@DisplayName("V2.6 Recurring Transfer Concurrency & Skip-Locked Integration Tests")
class RecurringTransferConcurrencyIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private RecurringTransferRepository recurringTransferRepository;

    @Autowired
    private RecurringTransferExecutionRepository executionRepository;

    @Autowired
    private RecurringTransferProcessor processor;

    @Autowired
    private SystemFundingService systemFundingService;

    private User alice;
    private User bob;
    private Account aliceAccount;
    private Account bobAccount;

    @BeforeEach
    void setUp() {
        if (jdbcTemplate != null) {
            jdbcTemplate.execute("TRUNCATE TABLE recurring_transfer_executions CASCADE");
            jdbcTemplate.execute("TRUNCATE TABLE recurring_transfers CASCADE");
            jdbcTemplate.execute("TRUNCATE TABLE outbox_events CASCADE");
            jdbcTemplate.execute("TRUNCATE TABLE ledger_entries CASCADE");
            jdbcTemplate.execute("TRUNCATE TABLE transactions CASCADE");
            jdbcTemplate.execute("TRUNCATE TABLE audit_events CASCADE");
            jdbcTemplate.execute("TRUNCATE TABLE accounts CASCADE");
            jdbcTemplate.execute("TRUNCATE TABLE users CASCADE");
        }
        systemFundingService.bootstrapSystemFunding(SystemFundingService.DEFAULT_BOOTSTRAP_AMOUNT);

        String runId = UUID.randomUUID().toString().substring(0, 8);
        alice = userRepository.save(new User("alice.concur." + runId + "@ledger.com", "Alice"));
        bob = userRepository.save(new User("bob.concur." + runId + "@ledger.com", "Bob"));

        aliceAccount = accountRepository.save(new Account(alice, "INR", new BigDecimal("10000.0000")));
        bobAccount = accountRepository.save(new Account(bob, "INR", new BigDecimal("2000.0000")));
    }

    @Test
    @DisplayName("Multiple concurrent workers contending for same due schedule execute exactly once without financial duplication")
    void testConcurrentWorkersDoNotDuplicateExecution() throws Exception {
        int workerCount = 5;
        Instant dueSlot = Instant.now().minusSeconds(30);

        RecurringTransfer schedule = new RecurringTransfer(
                alice,
                aliceAccount,
                bobAccount,
                new BigDecimal("500.0000"),
                "INR",
                RecurringFrequency.WEEKLY,
                LocalDate.now(ZoneOffset.UTC),
                null,
                dueSlot
        );
        RecurringTransfer saved = recurringTransferRepository.save(schedule);

        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        CountDownLatch readyLatch = new CountDownLatch(workerCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();

        for (int i = 0; i < workerCount; i++) {
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                startLatch.await(5, TimeUnit.SECONDS);
                // All workers race to process the same schedule under row lock
                return processor.processSingleSchedule(saved.getId());
            }));
        }

        // Wait until all workers are ready, then fire
        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();

        int successCount = 0;
        for (Future<Boolean> future : futures) {
            if (Boolean.TRUE.equals(future.get(10, TimeUnit.SECONDS))) {
                successCount++;
            }
        }
        executor.shutdown();

        // Exactly one worker processed the due slot
        assertThat(successCount).isEqualTo(1);

        // Financial Invariants Verification
        Account reloadedAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        Account reloadedBob = accountRepository.findById(bobAccount.getId()).orElseThrow();

        // Alice debited by exactly 500, Bob credited by exactly 500
        assertThat(reloadedAlice.getBalance()).isEqualByComparingTo(new BigDecimal("9500.0000"));
        assertThat(reloadedBob.getBalance()).isEqualByComparingTo(new BigDecimal("2500.0000"));

        // Exactly 1 transfer transaction created
        List<Transaction> transfers = transactionRepository.findAll().stream()
                .filter(t -> t.getTransactionType() == TransactionType.TRANSFER)
                .toList();
        assertThat(transfers).hasSize(1);

        // Exactly 1 execution record
        List<RecurringTransferExecution> executions = executionRepository.findByRecurringTransferId(saved.getId());
        assertThat(executions).hasSize(1);
        assertThat(executions.getFirst().getStatus()).isEqualTo(RecurringExecutionStatus.SUCCESS);

        // Schedule execution count is 1, next execution is in the future
        RecurringTransfer reloadedSchedule = recurringTransferRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloadedSchedule.getExecutionCount()).isEqualTo(1);
        assertThat(reloadedSchedule.getNextExecutionAt()).isAfter(Instant.now());
    }

    @Test
    @DisplayName("Multiple concurrent workers executing processEligibleSchedules batch claim disjoint schedules without duplication")
    void testConcurrentBatchProcessingDisjointClaim() throws Exception {
        int scheduleCount = 6;
        int workerCount = 3;
        Instant dueSlot = Instant.now().minusSeconds(20);

        List<RecurringTransfer> createdSchedules = new ArrayList<>();
        for (int i = 0; i < scheduleCount; i++) {
            createdSchedules.add(recurringTransferRepository.save(new RecurringTransfer(
                    alice,
                    aliceAccount,
                    bobAccount,
                    new BigDecimal("100.0000"),
                    "INR",
                    RecurringFrequency.DAILY,
                    LocalDate.now(ZoneOffset.UTC),
                    null,
                    dueSlot
            )));
        }

        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        CountDownLatch readyLatch = new CountDownLatch(workerCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<Integer>> futures = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < workerCount; i++) {
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                startLatch.await(5, TimeUnit.SECONDS);
                return processor.processBatch(10);
            }));
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();

        int totalClaimedAcrossWorkers = 0;
        for (Future<Integer> f : futures) {
            totalClaimedAcrossWorkers += f.get(15, TimeUnit.SECONDS);
        }
        executor.shutdown();

        // Exactly 6 total schedules claimed and processed across all workers
        assertThat(totalClaimedAcrossWorkers).isEqualTo(scheduleCount);

        // Exactly 6 transfer transactions created
        List<Transaction> transfers = transactionRepository.findAll().stream()
                .filter(t -> t.getTransactionType() == TransactionType.TRANSFER)
                .toList();
        assertThat(transfers).hasSize(scheduleCount);

        // Alice debited 6 * 100 = 600
        Account reloadedAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        assertThat(reloadedAlice.getBalance()).isEqualByComparingTo(new BigDecimal("9400.0000"));
    }
}
