package com.parth.ledger.transaction;

import com.parth.ledger.BaseIntegrationTest;
import com.parth.ledger.account.Account;
import com.parth.ledger.account.AccountRepository;
import com.parth.ledger.idempotency.IdempotencyCacheService;
import com.parth.ledger.ledger.LedgerEntry;
import com.parth.ledger.ledger.LedgerEntryRepository;
import com.parth.ledger.ledger.LedgerEntryType;
import com.parth.ledger.reconciliation.ReconciliationStatus;
import com.parth.ledger.reconciliation.dto.ReconciliationResultDto;
import com.parth.ledger.reconciliation.service.ReconciliationService;
import com.parth.ledger.transaction.dto.TransferRequestDto;
import com.parth.ledger.transaction.dto.TransferResponseDto;
import com.parth.ledger.transaction.exception.IdempotencyConflictException;
import com.parth.ledger.transaction.service.TransferService;
import com.parth.ledger.user.User;
import com.parth.ledger.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

class TransferFailureIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TransferService transferService;

    @Autowired
    private ReconciliationService reconciliationService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @MockitoSpyBean
    private IdempotencyCacheService spyIdempotencyCacheService;

    private User funderUser;
    private User aliceUser;
    private User bobUser;

    private Account funderAccount;
    private Account aliceAccount;
    private Account bobAccount;

    @BeforeEach
    void setUp() {
        clearRedis();
        ledgerEntryRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();

        reset(spyIdempotencyCacheService);

        funderUser = userRepository.save(new User("funder.failure@ledger.com", "Funder"));
        aliceUser = userRepository.save(new User("alice.failure@ledger.com", "Alice"));
        bobUser = userRepository.save(new User("bob.failure@ledger.com", "Bob"));

        funderAccount = accountRepository.save(new Account(funderUser, "INR", new BigDecimal("100000.0000")));
        aliceAccount = accountRepository.save(new Account(aliceUser, "INR"));
        bobAccount = accountRepository.save(new Account(bobUser, "INR"));

        // Fund Alice with 2000.0000 and Bob with 1000.0000 via double-entry transfers
        executeAsUser("funder.failure@ledger.com", () -> {
            transferService.executeTransfer("fund-alice-fail-test",
                    new TransferRequestDto(funderAccount.getId(), aliceAccount.getId(), new BigDecimal("2000.0000"), "INR"));
            transferService.executeTransfer("fund-bob-fail-test",
                    new TransferRequestDto(funderAccount.getId(), bobAccount.getId(), new BigDecimal("1000.0000"), "INR"));
        });
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        reset(spyIdempotencyCacheService);
    }

    @Test
    @DisplayName("Scenario A: Redis unavailable before transfer fails open to PostgreSQL and completes cleanly")
    void verifyRedisUnavailableBeforeTransferFailsOpen() {
        String idempotencyKey = "tx-redis-down-001";
        BigDecimal transferAmount = new BigDecimal("150.0000");

        TransferRequestDto request = new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                transferAmount,
                "INR"
        );

        // Simulate complete Redis outage on GET
        doThrow(new RedisConnectionFailureException("Simulated Redis connection outage"))
                .when(spyIdempotencyCacheService).get(anyString());

        long initialTxCount = transactionRepository.count();
        long initialLedgerCount = ledgerEntryRepository.count();

        // Transfer succeeds through PostgreSQL despite Redis outage
        TransferResponseDto response = executeAsUser("alice.failure@ledger.com",
                () -> transferService.executeTransfer(idempotencyKey, request));

        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(TransactionStatus.COMPLETED);

        // Verify balances changed correctly
        Account currentAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        Account currentBob = accountRepository.findById(bobAccount.getId()).orElseThrow();
        assertThat(currentAlice.getBalance()).isEqualByComparingTo(new BigDecimal("1850.0000"));
        assertThat(currentBob.getBalance()).isEqualByComparingTo(new BigDecimal("1150.0000"));

        // Exactly one new transaction and two new ledger entries
        assertThat(transactionRepository.count()).isEqualTo(initialTxCount + 1);
        assertThat(ledgerEntryRepository.count()).isEqualTo(initialLedgerCount + 2);

        // Reconcile both accounts: confirms 100% consistency
        executeAsUser("alice.failure@ledger.com", () -> {
            ReconciliationResultDto recon = reconciliationService.reconcileAccount(aliceAccount.getId());
            assertThat(recon.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
            assertThat(recon.difference()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(recon.snapshotBalance()).isEqualByComparingTo(new BigDecimal("1850.0000"));
            assertThat(recon.ledgerBalance()).isEqualByComparingTo(new BigDecimal("1850.0000"));
        });

        executeAsUser("bob.failure@ledger.com", () -> {
            ReconciliationResultDto recon = reconciliationService.reconcileAccount(bobAccount.getId());
            assertThat(recon.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
            assertThat(recon.difference()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(recon.snapshotBalance()).isEqualByComparingTo(new BigDecimal("1150.0000"));
            assertThat(recon.ledgerBalance()).isEqualByComparingTo(new BigDecimal("1150.0000"));
        });
    }

    @Test
    @DisplayName("Scenario B: Redis failure after PostgreSQL commit leaves financial state committed and safe")
    void verifyRedisFailureAfterCommitLeavesStateCommitted() {
        String idempotencyKey = "tx-redis-set-fail-002";
        BigDecimal transferAmount = new BigDecimal("80.0000");

        TransferRequestDto request = new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                transferAmount,
                "INR"
        );

        // Simulate Redis failure during post-commit caching
        doThrow(new RedisConnectionFailureException("Simulated Redis write timeout after commit"))
                .when(spyIdempotencyCacheService).set(anyString(), any(TransferResponseDto.class));

        long initialTxCount = transactionRepository.count();
        long initialLedgerCount = ledgerEntryRepository.count();

        // Transfer succeeds and returns COMPLETED response
        TransferResponseDto response = executeAsUser("alice.failure@ledger.com",
                () -> transferService.executeTransfer(idempotencyKey, request));

        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(TransactionStatus.COMPLETED);

        // Balances in PostgreSQL are committed and correct
        Account currentAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        Account currentBob = accountRepository.findById(bobAccount.getId()).orElseThrow();
        assertThat(currentAlice.getBalance()).isEqualByComparingTo(new BigDecimal("1920.0000"));
        assertThat(currentBob.getBalance()).isEqualByComparingTo(new BigDecimal("1080.0000"));

        // Transaction and ledger entries committed in DB
        assertThat(transactionRepository.count()).isEqualTo(initialTxCount + 1);
        assertThat(ledgerEntryRepository.count()).isEqualTo(initialLedgerCount + 2);

        // Subsequent retry with the same key is safely resolved through PostgreSQL
        TransferResponseDto retryResponse = executeAsUser("alice.failure@ledger.com",
                () -> transferService.executeTransfer(idempotencyKey, request));

        assertThat(retryResponse.transactionId()).isEqualTo(response.transactionId());
        assertThat(retryResponse.status()).isEqualTo(TransactionStatus.COMPLETED);

        // No duplicate debit or credit occurred
        currentAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        currentBob = accountRepository.findById(bobAccount.getId()).orElseThrow();
        assertThat(currentAlice.getBalance()).isEqualByComparingTo(new BigDecimal("1920.0000"));
        assertThat(currentBob.getBalance()).isEqualByComparingTo(new BigDecimal("1080.0000"));

        // Total count still initial + 1
        assertThat(transactionRepository.count()).isEqualTo(initialTxCount + 1);
        assertThat(ledgerEntryRepository.count()).isEqualTo(initialLedgerCount + 2);

        // Reconciliation remains CONSISTENT
        executeAsUser("alice.failure@ledger.com", () -> {
            ReconciliationResultDto recon = reconciliationService.reconcileAccount(aliceAccount.getId());
            assertThat(recon.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
            assertThat(recon.difference()).isEqualByComparingTo(BigDecimal.ZERO);
        });
        executeAsUser("bob.failure@ledger.com", () -> {
            ReconciliationResultDto recon = reconciliationService.reconcileAccount(bobAccount.getId());
            assertThat(recon.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
            assertThat(recon.difference()).isEqualByComparingTo(BigDecimal.ZERO);
        });
    }

    @Test
    @DisplayName("Scenario F1: Concurrent duplicate retries with Redis enabled produce single financial effect")
    void verifyConcurrentDuplicateRetriesWithRedisEnabled() throws InterruptedException {
        String idempotencyKey = "tx-concurrent-dup-redis-on";
        BigDecimal transferAmount = new BigDecimal("50.0000");

        TransferRequestDto request = new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                transferAmount,
                "INR"
        );

        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);

        Set<UUID> transactionIds = Collections.newSetFromMap(new ConcurrentHashMap<>());
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        long initialTxCount = transactionRepository.count();
        long initialLedgerCount = ledgerEntryRepository.count();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken("alice.failure@ledger.com", null, Collections.emptyList())
                    );
                    startLatch.await();
                    TransferResponseDto response = transferService.executeTransfer(idempotencyKey, request);
                    transactionIds.add(response.transactionId());
                } catch (Throwable t) {
                    failures.add(t);
                } finally {
                    SecurityContextHolder.clearContext();
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = endLatch.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(failures).isEmpty();

        // Exactly one unique transaction ID across all 20 concurrent callers
        assertThat(transactionIds).hasSize(1);

        // Balances debited/credited exactly once (single financial effect)
        Account currentAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        Account currentBob = accountRepository.findById(bobAccount.getId()).orElseThrow();
        assertThat(currentAlice.getBalance()).isEqualByComparingTo(new BigDecimal("1950.0000"));
        assertThat(currentBob.getBalance()).isEqualByComparingTo(new BigDecimal("1050.0000"));

        // Exactly 1 new transaction and 2 new ledger entries in PostgreSQL
        assertThat(transactionRepository.count()).isEqualTo(initialTxCount + 1);
        assertThat(ledgerEntryRepository.count()).isEqualTo(initialLedgerCount + 2);

        // Reconciliation confirms perfect consistency
        executeAsUser("alice.failure@ledger.com", () -> {
            ReconciliationResultDto recon = reconciliationService.reconcileAccount(aliceAccount.getId());
            assertThat(recon.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
            assertThat(recon.difference()).isEqualByComparingTo(BigDecimal.ZERO);
        });
        executeAsUser("bob.failure@ledger.com", () -> {
            ReconciliationResultDto recon = reconciliationService.reconcileAccount(bobAccount.getId());
            assertThat(recon.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
            assertThat(recon.difference()).isEqualByComparingTo(BigDecimal.ZERO);
        });
    }

    @Test
    @DisplayName("Scenario F2: Concurrent duplicate retries with Redis UNAVAILABLE produce single financial effect via PostgreSQL")
    void verifyConcurrentDuplicateRetriesWithRedisUnavailable() throws InterruptedException {
        String idempotencyKey = "tx-concurrent-dup-redis-down";
        BigDecimal transferAmount = new BigDecimal("60.0000");

        TransferRequestDto request = new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                transferAmount,
                "INR"
        );

        // Simulate complete Redis outage across both GET and SET
        doThrow(new RedisConnectionFailureException("Simulated Redis outage"))
                .when(spyIdempotencyCacheService).get(anyString());
        doThrow(new RedisConnectionFailureException("Simulated Redis outage"))
                .when(spyIdempotencyCacheService).set(anyString(), any(TransferResponseDto.class));

        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);

        Set<UUID> transactionIds = Collections.newSetFromMap(new ConcurrentHashMap<>());
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        long initialTxCount = transactionRepository.count();
        long initialLedgerCount = ledgerEntryRepository.count();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken("alice.failure@ledger.com", null, Collections.emptyList())
                    );
                    startLatch.await();
                    TransferResponseDto response = transferService.executeTransfer(idempotencyKey, request);
                    transactionIds.add(response.transactionId());
                } catch (Throwable t) {
                    failures.add(t);
                } finally {
                    SecurityContextHolder.clearContext();
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = endLatch.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(failures).isEmpty();

        // Exactly one unique transaction ID returned across all 20 threads
        assertThat(transactionIds).hasSize(1);

        // Balances moved exactly once
        Account currentAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        Account currentBob = accountRepository.findById(bobAccount.getId()).orElseThrow();
        assertThat(currentAlice.getBalance()).isEqualByComparingTo(new BigDecimal("1940.0000"));
        assertThat(currentBob.getBalance()).isEqualByComparingTo(new BigDecimal("1060.0000"));

        // PostgreSQL table counts verify no duplicate rows
        assertThat(transactionRepository.count()).isEqualTo(initialTxCount + 1);
        assertThat(ledgerEntryRepository.count()).isEqualTo(initialLedgerCount + 2);

        // Reconciliation confirms consistency
        executeAsUser("alice.failure@ledger.com", () -> {
            ReconciliationResultDto recon = reconciliationService.reconcileAccount(aliceAccount.getId());
            assertThat(recon.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
            assertThat(recon.difference()).isEqualByComparingTo(BigDecimal.ZERO);
        });
        executeAsUser("bob.failure@ledger.com", () -> {
            ReconciliationResultDto recon = reconciliationService.reconcileAccount(bobAccount.getId());
            assertThat(recon.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
            assertThat(recon.difference()).isEqualByComparingTo(BigDecimal.ZERO);
        });
    }

    @Test
    @DisplayName("Scenario G: Concurrent opposing transfers execute without deadlock and maintain reconciliation consistency")
    void verifyConcurrentOpposingTransfersMaintainReconciliationConsistency() throws InterruptedException {
        int transfersPerDirection = 20;
        int totalTransfers = transfersPerDirection * 2;
        BigDecimal transferAmount = new BigDecimal("10.0000");

        ExecutorService executor = Executors.newFixedThreadPool(totalTransfers);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(totalTransfers);

        AtomicInteger successCount = new AtomicInteger(0);
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        long initialTxCount = transactionRepository.count();
        long initialLedgerCount = ledgerEntryRepository.count();

        // A -> B transfers
        for (int i = 0; i < transfersPerDirection; i++) {
            final String key = "tx-opp-ab-recon-" + i;
            executor.submit(() -> {
                try {
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken("alice.failure@ledger.com", null, Collections.emptyList())
                    );
                    startLatch.await();
                    transferService.executeTransfer(key, new TransferRequestDto(
                            aliceAccount.getId(),
                            bobAccount.getId(),
                            transferAmount,
                            "INR"
                    ));
                    successCount.incrementAndGet();
                } catch (Throwable t) {
                    failures.add(t);
                } finally {
                    SecurityContextHolder.clearContext();
                    endLatch.countDown();
                }
            });
        }

        // B -> A transfers concurrently
        for (int i = 0; i < transfersPerDirection; i++) {
            final String key = "tx-opp-ba-recon-" + i;
            executor.submit(() -> {
                try {
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken("bob.failure@ledger.com", null, Collections.emptyList())
                    );
                    startLatch.await();
                    transferService.executeTransfer(key, new TransferRequestDto(
                            bobAccount.getId(),
                            aliceAccount.getId(),
                            transferAmount,
                            "INR"
                    ));
                    successCount.incrementAndGet();
                } catch (Throwable t) {
                    failures.add(t);
                } finally {
                    SecurityContextHolder.clearContext();
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

        // 200 moved A->B and 200 moved B->A; net balance is unchanged
        Account currentAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        Account currentBob = accountRepository.findById(bobAccount.getId()).orElseThrow();
        assertThat(currentAlice.getBalance()).isEqualByComparingTo(new BigDecimal("2000.0000"));
        assertThat(currentBob.getBalance()).isEqualByComparingTo(new BigDecimal("1000.0000"));

        // All 40 transactions completed
        assertThat(transactionRepository.count()).isEqualTo(initialTxCount + totalTransfers);
        assertThat(ledgerEntryRepository.count()).isEqualTo(initialLedgerCount + (totalTransfers * 2));

        // System-wide debits and credits balance
        List<LedgerEntry> entries = ledgerEntryRepository.findAll();
        BigDecimal totalDebits = entries.stream()
                .filter(e -> e.getEntryType() == LedgerEntryType.DEBIT)
                .map(LedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCredits = entries.stream()
                .filter(e -> e.getEntryType() == LedgerEntryType.CREDIT)
                .map(LedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(totalDebits).isEqualByComparingTo(totalCredits);

        // Both accounts reconcile as CONSISTENT
        executeAsUser("alice.failure@ledger.com", () -> {
            ReconciliationResultDto reconAlice = reconciliationService.reconcileAccount(aliceAccount.getId());
            assertThat(reconAlice.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
            assertThat(reconAlice.snapshotBalance()).isEqualByComparingTo(new BigDecimal("2000.0000"));
            assertThat(reconAlice.ledgerBalance()).isEqualByComparingTo(new BigDecimal("2000.0000"));
            assertThat(reconAlice.difference()).isEqualByComparingTo(BigDecimal.ZERO);
        });

        executeAsUser("bob.failure@ledger.com", () -> {
            ReconciliationResultDto reconBob = reconciliationService.reconcileAccount(bobAccount.getId());
            assertThat(reconBob.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
            assertThat(reconBob.snapshotBalance()).isEqualByComparingTo(new BigDecimal("1000.0000"));
            assertThat(reconBob.ledgerBalance()).isEqualByComparingTo(new BigDecimal("1000.0000"));
            assertThat(reconBob.difference()).isEqualByComparingTo(BigDecimal.ZERO);
        });
    }

    @Test
    @DisplayName("Idempotency conflict detection: retry with altered amount throws 409 and leaves balances untouched")
    void verifyIdempotencyConflictDetectionOnRetry() {
        String idempotencyKey = "tx-conflict-fail-001";
        BigDecimal originalAmount = new BigDecimal("100.0000");

        TransferRequestDto request1 = new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                originalAmount,
                "INR"
        );

        // Transfer 1 succeeds
        TransferResponseDto response1 = executeAsUser("alice.failure@ledger.com",
                () -> transferService.executeTransfer(idempotencyKey, request1));
        assertThat(response1.status()).isEqualTo(TransactionStatus.COMPLETED);

        // Transfer 2 with same key but different amount must throw IdempotencyConflictException
        TransferRequestDto requestConflict = new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("250.0000"),
                "INR"
        );

        executeAsUser("alice.failure@ledger.com", () -> {
            assertThatThrownBy(() -> transferService.executeTransfer(idempotencyKey, requestConflict))
                    .isInstanceOf(IdempotencyConflictException.class)
                    .hasMessageContaining("different parameters");
        });

        // Balances reflect only the first transfer
        Account currentAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        Account currentBob = accountRepository.findById(bobAccount.getId()).orElseThrow();
        assertThat(currentAlice.getBalance()).isEqualByComparingTo(new BigDecimal("1900.0000"));
        assertThat(currentBob.getBalance()).isEqualByComparingTo(new BigDecimal("1100.0000"));

        // Reconciliation confirms consistency
        executeAsUser("alice.failure@ledger.com", () -> {
            ReconciliationResultDto recon = reconciliationService.reconcileAccount(aliceAccount.getId());
            assertThat(recon.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
        });
    }

    private void executeAsUser(String email, Runnable action) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(email, null, Collections.emptyList())
        );
        try {
            action.run();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private <T> T executeAsUser(String email, ThrowingSupplier<T> action) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(email, null, Collections.emptyList())
        );
        try {
            return action.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @FunctionalInterface
    interface ThrowingSupplier<T> {
        T get();
    }
}
