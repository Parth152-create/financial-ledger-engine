package com.parth.ledger.reconciliation;

import com.parth.ledger.BaseIntegrationTest;
import com.parth.ledger.account.Account;
import com.parth.ledger.account.AccountRepository;
import com.parth.ledger.ledger.LedgerEntryRepository;
import com.parth.ledger.transaction.TransactionRepository;
import com.parth.ledger.transaction.dto.TransferRequestDto;
import com.parth.ledger.transaction.service.TransferService;
import com.parth.ledger.user.User;
import com.parth.ledger.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class ReconciliationIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private TransferService transferService;

    private User aliceUser;
    private User bobUser;
    private User charlieUser;

    private Account aliceAccount;
    private Account bobAccount;
    private Account charlieAccount;

    @BeforeEach
    void setUp() {
        clearRedis();
        ledgerEntryRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();

        aliceUser = userRepository.save(new User("alice.recon@ledger.com", "Alice Recon"));
        bobUser = userRepository.save(new User("bob.recon@ledger.com", "Bob Recon"));
        charlieUser = userRepository.save(new User("charlie.recon@ledger.com", "Charlie Recon"));

        // Alice serves as funding source for transfers
        aliceAccount = accountRepository.save(new Account(aliceUser, "USD", new BigDecimal("10000.0000")));
        // Bob and Charlie start at 0.0000
        bobAccount = accountRepository.save(new Account(bobUser, "USD"));
        charlieAccount = accountRepository.save(new Account(charlieUser, "USD"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        ledgerEntryRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("1. Consistent account: newly created zero-balance account matches ledger history")
    void verifyConsistentZeroBalanceAccount() throws Exception {
        mockMvc.perform(get("/api/v1/reconciliation/accounts/" + bobAccount.getId())
                        .with(user("bob.recon@ledger.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId", is(bobAccount.getId().toString())))
                .andExpect(jsonPath("$.snapshotBalance", is(0.0)))
                .andExpect(jsonPath("$.ledgerBalance", is(0.0)))
                .andExpect(jsonPath("$.difference", is(0.0)))
                .andExpect(jsonPath("$.status", is("CONSISTENT")))
                .andExpect(jsonPath("$.totalCredits", is(0.0)))
                .andExpect(jsonPath("$.totalDebits", is(0.0)));
    }

    @Test
    @DisplayName("2. Account after successful transfer: balance snapshot matches ledger credit entries")
    void verifyAccountAfterSuccessfulTransfer() throws Exception {
        // Alice transfers 200.0000 to Bob
        executeTransferAsAlice("recon-tx-001", aliceAccount.getId(), bobAccount.getId(), new BigDecimal("200.0000"));

        mockMvc.perform(get("/api/v1/reconciliation/accounts/" + bobAccount.getId())
                        .with(user("bob.recon@ledger.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId", is(bobAccount.getId().toString())))
                .andExpect(jsonPath("$.snapshotBalance", is(200.0)))
                .andExpect(jsonPath("$.ledgerBalance", is(200.0)))
                .andExpect(jsonPath("$.difference", is(0.0)))
                .andExpect(jsonPath("$.status", is("CONSISTENT")))
                .andExpect(jsonPath("$.totalCredits", is(200.0)))
                .andExpect(jsonPath("$.totalDebits", is(0.0)));
    }

    @Test
    @DisplayName("3. Multiple transfers: credits and debits aggregate correctly and maintain consistency")
    void verifyMultipleTransfersReconciliation() throws Exception {
        // Alice -> Bob: 500.0000
        executeTransferAsAlice("recon-tx-m1", aliceAccount.getId(), bobAccount.getId(), new BigDecimal("500.0000"));
        // Bob -> Charlie: 100.0000
        executeTransferAsBob("recon-tx-m2", bobAccount.getId(), charlieAccount.getId(), new BigDecimal("100.0000"));
        // Bob -> Charlie: 50.0000
        executeTransferAsBob("recon-tx-m3", bobAccount.getId(), charlieAccount.getId(), new BigDecimal("50.0000"));
        // Alice -> Bob: 25.0000
        executeTransferAsAlice("recon-tx-m4", aliceAccount.getId(), bobAccount.getId(), new BigDecimal("25.0000"));

        // Bob: 500 - 100 - 50 + 25 = 375.0000 (Credits: 525.0000, Debits: 150.0000)
        mockMvc.perform(get("/api/v1/reconciliation/accounts/" + bobAccount.getId())
                        .with(user("bob.recon@ledger.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshotBalance", is(375.0)))
                .andExpect(jsonPath("$.ledgerBalance", is(375.0)))
                .andExpect(jsonPath("$.totalCredits", is(525.0)))
                .andExpect(jsonPath("$.totalDebits", is(150.0)))
                .andExpect(jsonPath("$.difference", is(0.0)))
                .andExpect(jsonPath("$.status", is("CONSISTENT")));

        // Charlie: 100 + 50 = 150.0000
        mockMvc.perform(get("/api/v1/reconciliation/accounts/" + charlieAccount.getId())
                        .with(user("charlie.recon@ledger.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshotBalance", is(150.0)))
                .andExpect(jsonPath("$.ledgerBalance", is(150.0)))
                .andExpect(jsonPath("$.totalCredits", is(150.0)))
                .andExpect(jsonPath("$.totalDebits", is(0.0)))
                .andExpect(jsonPath("$.difference", is(0.0)))
                .andExpect(jsonPath("$.status", is("CONSISTENT")));
    }

    @Test
    @DisplayName("4. Decimal amounts: 4-decimal precision arithmetic produces exact reconciliation without rounding error")
    void verifyDecimalAmountsReconciliation() throws Exception {
        executeTransferAsAlice("recon-tx-dec1", aliceAccount.getId(), bobAccount.getId(), new BigDecimal("123.4567"));
        executeTransferAsBob("recon-tx-dec2", bobAccount.getId(), charlieAccount.getId(), new BigDecimal("23.4567"));

        // Bob: 123.4567 - 23.4567 = 100.0000
        mockMvc.perform(get("/api/v1/reconciliation/accounts/" + bobAccount.getId())
                        .with(user("bob.recon@ledger.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshotBalance", is(100.0)))
                .andExpect(jsonPath("$.ledgerBalance", is(100.0)))
                .andExpect(jsonPath("$.difference", is(0.0)))
                .andExpect(jsonPath("$.status", is("CONSISTENT")));

        // Charlie: 23.4567
        mockMvc.perform(get("/api/v1/reconciliation/accounts/" + charlieAccount.getId())
                        .with(user("charlie.recon@ledger.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshotBalance", is(23.4567)))
                .andExpect(jsonPath("$.ledgerBalance", is(23.4567)))
                .andExpect(jsonPath("$.difference", is(0.0)))
                .andExpect(jsonPath("$.status", is("CONSISTENT")));
    }

    @Test
    @DisplayName("5. Discrepancy detection: controlled snapshot mismatch reports DISCREPANCY and does not repair balance")
    void verifyDiscrepancyDetectionControlledSnapshotMismatch() throws Exception {
        // Alice -> Bob: 200.0000 (ledger has credit of 200.0000)
        executeTransferAsAlice("recon-tx-disc1", aliceAccount.getId(), bobAccount.getId(), new BigDecimal("200.0000"));

        // Deliberately corrupt snapshot balance directly in DB to 250.0000 (tampering by 50.0000)
        Account accountToCorrupt = accountRepository.findById(bobAccount.getId()).orElseThrow();
        accountToCorrupt.setBalance(new BigDecimal("250.0000"));
        accountRepository.save(accountToCorrupt);

        // Reconcile Bob
        mockMvc.perform(get("/api/v1/reconciliation/accounts/" + bobAccount.getId())
                        .with(user("bob.recon@ledger.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshotBalance", is(250.0)))
                .andExpect(jsonPath("$.ledgerBalance", is(200.0)))
                .andExpect(jsonPath("$.difference", is(50.0)))
                .andExpect(jsonPath("$.status", is("DISCREPANCY")));

        // Verify failure behavior: reconciliation detects rather than modifies or repairs the balance
        Account verified = accountRepository.findById(bobAccount.getId()).orElseThrow();
        assertThat(verified.getBalance()).isEqualByComparingTo(new BigDecimal("250.0000"));
    }

    @Test
    @DisplayName("6. Nonexistent account returns HTTP 404 Not Found")
    void verifyNonexistentAccountReturns404() throws Exception {
        UUID unknownId = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/reconciliation/accounts/" + unknownId)
                        .with(user("bob.recon@ledger.com")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status", is(404)))
                .andExpect(jsonPath("$.message", containsString("Account not found")));
    }

    @Test
    @DisplayName("7. Unauthorized access returns HTTP 401 Unauthorized")
    void verifyUnauthorizedAccessReturns401() throws Exception {
        // Unauthenticated single-account reconciliation
        mockMvc.perform(get("/api/v1/reconciliation/accounts/" + bobAccount.getId()))
                .andExpect(status().isUnauthorized());

        // Unauthenticated multi-account reconciliation
        mockMvc.perform(get("/api/v1/reconciliation/accounts"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("8. Authenticated non-owner access returns HTTP 403 Forbidden")
    void verifyAuthenticatedNonOwnerAccessReturns403() throws Exception {
        // Charlie attempts to reconcile Bob's account
        mockMvc.perform(get("/api/v1/reconciliation/accounts/" + bobAccount.getId())
                        .with(user("charlie.recon@ledger.com")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status", is(403)))
                .andExpect(jsonPath("$.message", containsString("not authorized to operate on this account")));
    }

    @Test
    @DisplayName("9. Concurrent transfer scenario followed by reconciliation confirms integrity")
    void verifyConcurrentTransfersFollowedByReconciliation() throws Exception {
        int threadCount = 10;
        BigDecimal transferAmount = new BigDecimal("25.0000"); // 10 * 25 = 250.0000 total

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            final String key = "recon-concurrent-tx-" + i;
            executor.submit(() -> {
                try {
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken("alice.recon@ledger.com", null, Collections.emptyList())
                    );
                    startLatch.await();
                    transferService.executeTransfer(key, new TransferRequestDto(
                            aliceAccount.getId(),
                            bobAccount.getId(),
                            transferAmount,
                            "USD"
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
        boolean completed = endLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(failures).isEmpty();
        assertThat(successCount.get()).isEqualTo(threadCount);

        // Reconcile Bob after all concurrent transfers finish
        mockMvc.perform(get("/api/v1/reconciliation/accounts/" + bobAccount.getId())
                        .with(user("bob.recon@ledger.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshotBalance", is(250.0)))
                .andExpect(jsonPath("$.ledgerBalance", is(250.0)))
                .andExpect(jsonPath("$.difference", is(0.0)))
                .andExpect(jsonPath("$.status", is("CONSISTENT")))
                .andExpect(jsonPath("$.totalCredits", is(250.0)))
                .andExpect(jsonPath("$.totalDebits", is(0.0)));
    }

    @Test
    @DisplayName("10. Multi-account reconciliation: reconciles all accounts owned by authenticated user")
    void verifyMultiAccountReconciliation() throws Exception {
        // Create second account for Bob
        Account bobAccount2 = accountRepository.save(new Account(bobUser, "USD"));

        // Alice funds both accounts
        executeTransferAsAlice("recon-multi-tx-1", aliceAccount.getId(), bobAccount.getId(), new BigDecimal("300.0000"));
        executeTransferAsAlice("recon-multi-tx-2", aliceAccount.getId(), bobAccount2.getId(), new BigDecimal("200.0000"));

        // Bob transfers between his own accounts: bobAccount -> bobAccount2
        executeTransferAsBob("recon-multi-tx-3", bobAccount.getId(), bobAccount2.getId(), new BigDecimal("50.0000"));

        // Call multi-account reconciliation endpoint
        mockMvc.perform(get("/api/v1/reconciliation/accounts")
                        .with(user("bob.recon@ledger.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAccountsChecked", is(2)))
                .andExpect(jsonPath("$.consistentAccounts", is(2)))
                .andExpect(jsonPath("$.discrepancyCount", is(0)))
                .andExpect(jsonPath("$.reconciliationResults", hasSize(2)));

        // Introduce discrepancy in one account
        Account toCorrupt = accountRepository.findById(bobAccount.getId()).orElseThrow();
        toCorrupt.setBalance(new BigDecimal("999.0000"));
        accountRepository.save(toCorrupt);

        // Re-run multi-account reconciliation
        mockMvc.perform(get("/api/v1/reconciliation/accounts")
                        .with(user("bob.recon@ledger.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAccountsChecked", is(2)))
                .andExpect(jsonPath("$.consistentAccounts", is(1)))
                .andExpect(jsonPath("$.discrepancyCount", is(1)))
                .andExpect(jsonPath("$.reconciliationResults", hasSize(2)));
    }

    private void executeTransferAsAlice(String idempotencyKey, UUID sourceId, UUID destId, BigDecimal amount) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice.recon@ledger.com", null, Collections.emptyList())
        );
        try {
            transferService.executeTransfer(idempotencyKey, new TransferRequestDto(sourceId, destId, amount, "USD"));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void executeTransferAsBob(String idempotencyKey, UUID sourceId, UUID destId, BigDecimal amount) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("bob.recon@ledger.com", null, Collections.emptyList())
        );
        try {
            transferService.executeTransfer(idempotencyKey, new TransferRequestDto(sourceId, destId, amount, "USD"));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
