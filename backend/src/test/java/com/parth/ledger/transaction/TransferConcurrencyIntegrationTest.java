package com.parth.ledger.transaction;

import com.parth.ledger.BaseIntegrationTest;
import com.parth.ledger.account.Account;
import com.parth.ledger.account.AccountRepository;
import com.parth.ledger.ledger.LedgerEntry;
import com.parth.ledger.ledger.LedgerEntryRepository;
import com.parth.ledger.ledger.LedgerEntryType;
import com.parth.ledger.transaction.dto.TransferRequestDto;
import com.parth.ledger.transaction.dto.TransferResponseDto;
import com.parth.ledger.transaction.exception.InsufficientBalanceException;
import com.parth.ledger.transaction.service.TransferService;
import com.parth.ledger.user.User;
import com.parth.ledger.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

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

class TransferConcurrencyIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TransferService transferService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    private User aliceUser;
    private User bobUser;

    @BeforeEach
    void setUp() {
        clearRedis();
        ledgerEntryRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();

        aliceUser = userRepository.save(new User("alice.concurrency@ledger.com", "Alice"));
        bobUser = userRepository.save(new User("bob.concurrency@ledger.com", "Bob"));
    }

    @Test
    @DisplayName("Opposite-direction transfers: concurrent A->B and B->A acquire locks deterministically without deadlock")
    void verifyOppositeDirectionTransfersWithoutDeadlock() throws InterruptedException {
        Account accountA = accountRepository.save(new Account(aliceUser, "USD", new BigDecimal("1000.0000")));
        Account accountB = accountRepository.save(new Account(bobUser, "USD", new BigDecimal("1000.0000")));

        int transfersPerDirection = 20;
        int totalTransfers = transfersPerDirection * 2;
        BigDecimal transferAmount = new BigDecimal("10.0000");

        ExecutorService executor = Executors.newFixedThreadPool(totalTransfers);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(totalTransfers);

        AtomicInteger successCount = new AtomicInteger(0);
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());

        // Submit A -> B transfers
        for (int i = 0; i < transfersPerDirection; i++) {
            final String key = "tx-opp-ab-" + i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    transferService.executeTransfer(key, new TransferRequestDto(
                            accountA.getId(),
                            accountB.getId(),
                            transferAmount,
                            "USD"
                    ));
                    successCount.incrementAndGet();
                } catch (Throwable t) {
                    failures.add(t);
                } finally {
                    endLatch.countDown();
                }
            });
        }

        // Submit B -> A transfers concurrently
        for (int i = 0; i < transfersPerDirection; i++) {
            final String key = "tx-opp-ba-" + i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    transferService.executeTransfer(key, new TransferRequestDto(
                            accountB.getId(),
                            accountA.getId(),
                            transferAmount,
                            "USD"
                    ));
                    successCount.incrementAndGet();
                } catch (Throwable t) {
                    failures.add(t);
                } finally {
                    endLatch.countDown();
                }
            });
        }

        // Release all threads simultaneously
        startLatch.countDown();
        boolean completed = endLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(failures).isEmpty();
        assertThat(successCount.get()).isEqualTo(totalTransfers);

        // Verify balances: 200 moved A->B, 200 moved B->A; net change is zero
        Account finalA = accountRepository.findById(accountA.getId()).orElseThrow();
        Account finalB = accountRepository.findById(accountB.getId()).orElseThrow();
        assertThat(finalA.getBalance()).isEqualByComparingTo(new BigDecimal("1000.0000"));
        assertThat(finalB.getBalance()).isEqualByComparingTo(new BigDecimal("1000.0000"));

        // Verify all transactions completed
        assertThat(transactionRepository.count()).isEqualTo(totalTransfers);

        // Verify ledger entries
        List<LedgerEntry> entries = ledgerEntryRepository.findAll();
        assertThat(entries).hasSize(totalTransfers * 2);

        BigDecimal totalDebits = entries.stream()
                .filter(e -> e.getEntryType() == LedgerEntryType.DEBIT)
                .map(LedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalCredits = entries.stream()
                .filter(e -> e.getEntryType() == LedgerEntryType.CREDIT)
                .map(LedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal expectedVolume = transferAmount.multiply(BigDecimal.valueOf(totalTransfers));
        assertThat(totalDebits).isEqualByComparingTo(expectedVolume);
        assertThat(totalCredits).isEqualByComparingTo(expectedVolume);
        assertThat(totalDebits).isEqualByComparingTo(totalCredits);
    }

    @Test
    @DisplayName("Balance exhaustion contention: concurrent debit attempts prevent overdraft and maintain correctness")
    void verifyConcurrentTransfersWithBalanceExhaustionNoLostUpdates() throws InterruptedException {
        // Initial: A has exactly 100.0000, B has 0.0000
        Account accountA = accountRepository.save(new Account(aliceUser, "USD", new BigDecimal("100.0000")));
        Account accountB = accountRepository.save(new Account(bobUser, "USD", new BigDecimal("0.0000")));

        int totalThreads = 20;
        BigDecimal transferAmount = new BigDecimal("20.0000"); // 5 transfers can succeed: 5 * 20 = 100

        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(totalThreads);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger insufficientBalanceCount = new AtomicInteger(0);
        List<Throwable> unexpectedFailures = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < totalThreads; i++) {
            final String key = "tx-exhaust-" + i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    transferService.executeTransfer(key, new TransferRequestDto(
                            accountA.getId(),
                            accountB.getId(),
                            transferAmount,
                            "USD"
                    ));
                    successCount.incrementAndGet();
                } catch (InsufficientBalanceException e) {
                    insufficientBalanceCount.incrementAndGet();
                } catch (Throwable t) {
                    unexpectedFailures.add(t);
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = endLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(unexpectedFailures).isEmpty();

        // Exactly 5 transfers succeed, 15 fail due to balance exhaustion
        assertThat(successCount.get()).isEqualTo(5);
        assertThat(insufficientBalanceCount.get()).isEqualTo(15);

        // Account A balance must be exactly 0 (never negative)
        Account finalA = accountRepository.findById(accountA.getId()).orElseThrow();
        Account finalB = accountRepository.findById(accountB.getId()).orElseThrow();
        assertThat(finalA.getBalance()).isEqualByComparingTo(new BigDecimal("0.0000"));
        assertThat(finalB.getBalance()).isEqualByComparingTo(new BigDecimal("100.0000"));

        // Exactly 5 transactions recorded
        assertThat(transactionRepository.count()).isEqualTo(5);

        // Exactly 10 ledger entries recorded
        List<LedgerEntry> entries = ledgerEntryRepository.findAll();
        assertThat(entries).hasSize(10);

        BigDecimal totalDebits = entries.stream()
                .filter(e -> e.getEntryType() == LedgerEntryType.DEBIT)
                .map(LedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCredits = entries.stream()
                .filter(e -> e.getEntryType() == LedgerEntryType.CREDIT)
                .map(LedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(totalDebits).isEqualByComparingTo(new BigDecimal("100.0000"));
        assertThat(totalCredits).isEqualByComparingTo(new BigDecimal("100.0000"));
    }

    @Test
    @DisplayName("Concurrent identical idempotent requests: execute single transfer and return identical transaction")
    void verifyConcurrentIdenticalRetries() throws InterruptedException {
        Account accountA = accountRepository.save(new Account(aliceUser, "USD", new BigDecimal("500.0000")));
        Account accountB = accountRepository.save(new Account(bobUser, "USD", new BigDecimal("500.0000")));

        int concurrentRetries = 10;
        String sharedKey = "tx-concurrent-idem-001";
        BigDecimal transferAmount = new BigDecimal("100.0000");

        TransferRequestDto request = new TransferRequestDto(
                accountA.getId(),
                accountB.getId(),
                transferAmount,
                "USD"
        );

        ExecutorService executor = Executors.newFixedThreadPool(concurrentRetries);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(concurrentRetries);

        Set<UUID> returnedTransactionIds = ConcurrentHashMap.newKeySet();
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < concurrentRetries; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    TransferResponseDto response = transferService.executeTransfer(sharedKey, request);
                    returnedTransactionIds.add(response.transactionId());
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = endLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(errors).isEmpty();

        // All concurrent threads must return the exact same transaction ID
        assertThat(returnedTransactionIds).hasSize(1);

        // Balances must be debited/credited exactly ONCE
        Account finalA = accountRepository.findById(accountA.getId()).orElseThrow();
        Account finalB = accountRepository.findById(accountB.getId()).orElseThrow();
        assertThat(finalA.getBalance()).isEqualByComparingTo(new BigDecimal("400.0000"));
        assertThat(finalB.getBalance()).isEqualByComparingTo(new BigDecimal("600.0000"));

        // Exactly one transaction in database
        assertThat(transactionRepository.count()).isEqualTo(1);

        // Exactly two ledger entries in database
        assertThat(ledgerEntryRepository.count()).isEqualTo(2);
    }
}
