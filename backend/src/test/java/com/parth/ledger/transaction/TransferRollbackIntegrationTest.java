package com.parth.ledger.transaction;

import com.parth.ledger.BaseIntegrationTest;
import com.parth.ledger.account.Account;
import com.parth.ledger.account.AccountRepository;
import com.parth.ledger.ledger.LedgerEntry;
import com.parth.ledger.ledger.LedgerEntryRepository;
import com.parth.ledger.ledger.LedgerEntryType;
import com.parth.ledger.reconciliation.ReconciliationStatus;
import com.parth.ledger.reconciliation.dto.ReconciliationResultDto;
import com.parth.ledger.reconciliation.service.ReconciliationService;
import com.parth.ledger.transaction.dto.TransferRequestDto;
import com.parth.ledger.transaction.dto.TransferResponseDto;
import com.parth.ledger.transaction.service.TransferService;
import com.parth.ledger.user.User;
import com.parth.ledger.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

class TransferRollbackIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TransferService transferService;

    @Autowired
    private ReconciliationService reconciliationService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @MockitoSpyBean
    private TransactionRepository spyTransactionRepository;

    @MockitoSpyBean
    private LedgerEntryRepository spyLedgerEntryRepository;

    private User funderUser;
    private User aliceUser;
    private User bobUser;

    private Account funderAccount;
    private Account aliceAccount;
    private Account bobAccount;

    @BeforeEach
    void setUp() {
        clearRedis();
        spyLedgerEntryRepository.deleteAll();
        spyTransactionRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();

        reset(spyTransactionRepository);
        reset(spyLedgerEntryRepository);

        funderUser = userRepository.save(new User("funder.rollback@ledger.com", "Funder"));
        aliceUser = userRepository.save(new User("alice.rollback@ledger.com", "Alice Rollback"));
        bobUser = userRepository.save(new User("bob.rollback@ledger.com", "Bob Rollback"));

        // Funder account initialized with funds
        funderAccount = accountRepository.save(new Account(funderUser, "USD", new BigDecimal("100000.0000")));

        // Alice and Bob start at 0.0000 and are funded via balanced double-entry transfers
        aliceAccount = accountRepository.save(new Account(aliceUser, "USD"));
        bobAccount = accountRepository.save(new Account(bobUser, "USD"));

        // Fund Alice with 1000.0000 and Bob with 500.0000 via authoritative transfers
        executeAsUser("funder.rollback@ledger.com", () -> {
            transferService.executeTransfer("fund-alice-init",
                    new TransferRequestDto(funderAccount.getId(), aliceAccount.getId(), new BigDecimal("1000.0000"), "USD"));
            transferService.executeTransfer("fund-bob-init",
                    new TransferRequestDto(funderAccount.getId(), bobAccount.getId(), new BigDecimal("500.0000"), "USD"));
        });

        // Verify initial state: Alice has 1000.0000, Bob has 500.0000, both are CONSISTENT
        executeAsUser("alice.rollback@ledger.com", () -> {
            ReconciliationResultDto recon = reconciliationService.reconcileAccount(aliceAccount.getId());
            assertThat(recon.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
            assertThat(recon.snapshotBalance()).isEqualByComparingTo(new BigDecimal("1000.0000"));
        });

        executeAsUser("bob.rollback@ledger.com", () -> {
            ReconciliationResultDto recon = reconciliationService.reconcileAccount(bobAccount.getId());
            assertThat(recon.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
            assertThat(recon.snapshotBalance()).isEqualByComparingTo(new BigDecimal("500.0000"));
        });
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        reset(spyTransactionRepository);
        reset(spyLedgerEntryRepository);
    }

    @Test
    @DisplayName("Scenario C: Controlled database failure during transaction record save triggers full rollback")
    void verifyRollbackOnDatabaseFailureDuringTransactionSave() {
        long initialTxCount = spyTransactionRepository.count();
        long initialLedgerCount = spyLedgerEntryRepository.count();

        // Introduce simulated failure when saving the Transaction entity
        doThrow(new DataAccessResourceFailureException("Simulated PostgreSQL connection failure on transaction save"))
                .when(spyTransactionRepository).save(any(Transaction.class));

        TransferRequestDto request = new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("150.0000"),
                "USD"
        );

        // Execute transfer as Alice: must throw and rollback
        executeAsUser("alice.rollback@ledger.com", () -> {
            assertThatThrownBy(() -> transferService.executeTransfer("tx-rollback-c-001", request))
                    .isInstanceOf(DataAccessResourceFailureException.class)
                    .hasMessageContaining("Simulated PostgreSQL connection failure");
        });

        // Reset spy to allow verification queries
        reset(spyTransactionRepository);

        // 1. Verify balances returned to pre-transaction values
        Account currentAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        Account currentBob = accountRepository.findById(bobAccount.getId()).orElseThrow();
        assertThat(currentAlice.getBalance()).isEqualByComparingTo(new BigDecimal("1000.0000"));
        assertThat(currentBob.getBalance()).isEqualByComparingTo(new BigDecimal("500.0000"));

        // 2. Verify no partial transaction persisted
        assertThat(spyTransactionRepository.count()).isEqualTo(initialTxCount);
        assertThat(spyTransactionRepository.findByIdempotencyKey("tx-rollback-c-001")).isEmpty();

        // 3. Verify no partial ledger entries persisted
        assertThat(spyLedgerEntryRepository.count()).isEqualTo(initialLedgerCount);

        // 4. Verify system-wide debit and credit totals remain balanced
        assertSystemLedgerEntriesBalanced();

        // 5. Verify reconciliation confirms zero discrepancy on both accounts
        executeAsUser("alice.rollback@ledger.com", () -> {
            ReconciliationResultDto reconAlice = reconciliationService.reconcileAccount(aliceAccount.getId());
            assertThat(reconAlice.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
            assertThat(reconAlice.difference()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(reconAlice.snapshotBalance()).isEqualByComparingTo(new BigDecimal("1000.0000"));
        });

        executeAsUser("bob.rollback@ledger.com", () -> {
            ReconciliationResultDto reconBob = reconciliationService.reconcileAccount(bobAccount.getId());
            assertThat(reconBob.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
            assertThat(reconBob.difference()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(reconBob.snapshotBalance()).isEqualByComparingTo(new BigDecimal("500.0000"));
        });
    }

    @Test
    @DisplayName("Scenario D: Failure before ledger entry persistence rolls back mutated balances and pending tx")
    void verifyRollbackOnFailureBeforeLedgerEntryPersistence() {
        long initialTxCount = spyTransactionRepository.count();
        long initialLedgerCount = spyLedgerEntryRepository.count();

        // Introduce simulated failure when saving ledger entries (after account balances mutated and tx created)
        doThrow(new DataAccessResourceFailureException("Simulated PostgreSQL disk failure during ledger entry write"))
                .when(spyLedgerEntryRepository).save(any(LedgerEntry.class));

        TransferRequestDto request = new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("200.0000"),
                "USD"
        );

        // Execute transfer as Alice
        executeAsUser("alice.rollback@ledger.com", () -> {
            assertThatThrownBy(() -> transferService.executeTransfer("tx-rollback-d-001", request))
                    .isInstanceOf(DataAccessResourceFailureException.class)
                    .hasMessageContaining("Simulated PostgreSQL disk failure");
        });

        // Reset spy
        reset(spyLedgerEntryRepository);

        // 1. Verify accounts have NO partial balance updates (rollback reverted mutations)
        Account currentAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        Account currentBob = accountRepository.findById(bobAccount.getId()).orElseThrow();
        assertThat(currentAlice.getBalance()).isEqualByComparingTo(new BigDecimal("1000.0000"));
        assertThat(currentBob.getBalance()).isEqualByComparingTo(new BigDecimal("500.0000"));

        // 2. Verify NO orphan transaction record survived
        assertThat(spyTransactionRepository.count()).isEqualTo(initialTxCount);
        assertThat(spyTransactionRepository.findByIdempotencyKey("tx-rollback-d-001")).isEmpty();

        // 3. Verify NO orphan ledger entries survived
        assertThat(spyLedgerEntryRepository.count()).isEqualTo(initialLedgerCount);

        // 4. Verify system balance invariant
        assertSystemLedgerEntriesBalanced();

        // 5. Verify reconciliation remains CONSISTENT
        executeAsUser("alice.rollback@ledger.com", () -> {
            ReconciliationResultDto recon = reconciliationService.reconcileAccount(aliceAccount.getId());
            assertThat(recon.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
            assertThat(recon.difference()).isEqualByComparingTo(BigDecimal.ZERO);
        });
        executeAsUser("bob.rollback@ledger.com", () -> {
            ReconciliationResultDto recon = reconciliationService.reconcileAccount(bobAccount.getId());
            assertThat(recon.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
            assertThat(recon.difference()).isEqualByComparingTo(BigDecimal.ZERO);
        });
    }

    @Test
    @DisplayName("Scenario E: Idempotent retry succeeds after an initial transfer failure and rollback")
    void verifyIdempotentRetryAfterFailureSucceeds() {
        String idempotencyKey = "tx-retry-after-fail-001";
        BigDecimal transferAmount = new BigDecimal("120.0000");

        TransferRequestDto request = new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                transferAmount,
                "USD"
        );

        // Step 1: Force failure on attempt 1 during ledger entry write
        doThrow(new DataAccessResourceFailureException("Transient write timeout"))
                .when(spyLedgerEntryRepository).save(any(LedgerEntry.class));

        executeAsUser("alice.rollback@ledger.com", () -> {
            assertThatThrownBy(() -> transferService.executeTransfer(idempotencyKey, request))
                    .isInstanceOf(DataAccessResourceFailureException.class);
        });

        // Verify attempt 1 left no trace in PostgreSQL
        assertThat(spyTransactionRepository.findByIdempotencyKey(idempotencyKey)).isEmpty();

        // Step 2: Remove failure condition (simulate recovery)
        reset(spyLedgerEntryRepository);

        // Step 3: Client retries with the EXACT same idempotency key
        TransferResponseDto retryResponse = executeAsUser("alice.rollback@ledger.com",
                () -> transferService.executeTransfer(idempotencyKey, request));

        // Step 4: Verify retry succeeded cleanly
        assertThat(retryResponse).isNotNull();
        assertThat(retryResponse.status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(retryResponse.amount()).isEqualByComparingTo(transferAmount);

        // Exactly one transaction exists for this idempotency key
        Transaction savedTx = spyTransactionRepository.findByIdempotencyKey(idempotencyKey).orElseThrow();
        assertThat(savedTx.getStatus()).isEqualTo(TransactionStatus.COMPLETED);

        // Exactly two ledger entries exist for this transaction
        List<LedgerEntry> entries = spyLedgerEntryRepository.findByTransactionId(savedTx.getId());
        assertThat(entries).hasSize(2);

        // Balances moved exactly once
        Account currentAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        Account currentBob = accountRepository.findById(bobAccount.getId()).orElseThrow();
        assertThat(currentAlice.getBalance()).isEqualByComparingTo(new BigDecimal("880.0000"));
        assertThat(currentBob.getBalance()).isEqualByComparingTo(new BigDecimal("620.0000"));

        // Reconciliation confirms perfect consistency
        executeAsUser("alice.rollback@ledger.com", () -> {
            ReconciliationResultDto recon = reconciliationService.reconcileAccount(aliceAccount.getId());
            assertThat(recon.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
            assertThat(recon.snapshotBalance()).isEqualByComparingTo(new BigDecimal("880.0000"));
            assertThat(recon.ledgerBalance()).isEqualByComparingTo(new BigDecimal("880.0000"));
        });
        executeAsUser("bob.rollback@ledger.com", () -> {
            ReconciliationResultDto recon = reconciliationService.reconcileAccount(bobAccount.getId());
            assertThat(recon.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
            assertThat(recon.snapshotBalance()).isEqualByComparingTo(new BigDecimal("620.0000"));
            assertThat(recon.ledgerBalance()).isEqualByComparingTo(new BigDecimal("620.0000"));
        });
    }

    private void assertSystemLedgerEntriesBalanced() {
        List<LedgerEntry> entries = spyLedgerEntryRepository.findAll();
        BigDecimal totalDebits = entries.stream()
                .filter(e -> e.getEntryType() == LedgerEntryType.DEBIT)
                .map(LedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalCredits = entries.stream()
                .filter(e -> e.getEntryType() == LedgerEntryType.CREDIT)
                .map(LedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(totalDebits).isEqualByComparingTo(totalCredits);
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
