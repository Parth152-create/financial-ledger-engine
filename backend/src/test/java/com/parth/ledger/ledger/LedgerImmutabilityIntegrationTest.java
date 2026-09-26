package com.parth.ledger.ledger;

import com.parth.ledger.BaseIntegrationTest;
import com.parth.ledger.account.Account;
import com.parth.ledger.account.AccountRepository;
import com.parth.ledger.reconciliation.ReconciliationStatus;
import com.parth.ledger.reconciliation.dto.OverallReconciliationDto;
import com.parth.ledger.reconciliation.dto.ReconciliationResultDto;
import com.parth.ledger.reconciliation.service.ReconciliationService;
import com.parth.ledger.transaction.Transaction;
import com.parth.ledger.transaction.TransactionRepository;
import com.parth.ledger.transaction.TransactionStatus;
import com.parth.ledger.transaction.TransactionType;
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
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("V4 Database Integrity & Ledger Immutability Integration Tests")
class LedgerImmutabilityIntegrationTest extends BaseIntegrationTest {

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

    @Autowired
    private ReconciliationService reconciliationService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private User aliceUser;
    private User bobUser;
    private Account aliceAccount;
    private Account bobAccount;

    @BeforeEach
    void setUp() {
        clearRedis();
        if (jdbcTemplate != null) {
            jdbcTemplate.execute("TRUNCATE TABLE ledger_entries CASCADE");
        }
        ledgerEntryRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();

        aliceUser = userRepository.save(new User("alice.v4@ledger.com", "Alice V4"));
        bobUser = userRepository.save(new User("bob.v4@ledger.com", "Bob V4"));

        aliceAccount = accountRepository.save(new Account(aliceUser, "INR", new BigDecimal("1000.0000")));
        bobAccount = accountRepository.save(new Account(bobUser, "INR", new BigDecimal("500.0000")));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(aliceUser.getEmail(), null, Collections.emptyList())
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        if (jdbcTemplate != null) {
            jdbcTemplate.execute("TRUNCATE TABLE ledger_entries CASCADE");
        }
        ledgerEntryRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("1. A valid ledger entry can be inserted successfully")
    void validLedgerEntryCanBeInserted() {
        Transaction tx = transactionRepository.save(new Transaction(
                "tx-immutability-insert-01",
                new BigDecimal("150.0000"),
                "INR",
                TransactionStatus.PENDING,
                aliceAccount,
                bobAccount,
                TransactionType.TRANSFER,
                aliceUser,
                "Insert test"
        ));

        LedgerEntry entry = new LedgerEntry(
                tx,
                aliceAccount,
                LedgerEntryType.DEBIT,
                new BigDecimal("150.0000"),
                "INR"
        );
        LedgerEntry savedEntry = ledgerEntryRepository.save(entry);

        assertThat(savedEntry.getId()).isNotNull();
        assertThat(savedEntry.getAmount()).isEqualByComparingTo(new BigDecimal("150.0000"));
        assertThat(savedEntry.getCurrency()).isEqualTo("INR");
        assertThat(savedEntry.getEntryType()).isEqualTo(LedgerEntryType.DEBIT);
        assertThat(savedEntry.getAccount().getId()).isEqualTo(aliceAccount.getId());
        assertThat(savedEntry.getTransaction().getId()).isEqualTo(tx.getId());
        assertThat(savedEntry.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("2. Updating an existing ledger entry fails at the PostgreSQL database level")
    void updatingExistingLedgerEntryFailsAtDatabaseLevel() {
        Transaction tx = transactionRepository.save(new Transaction(
                "tx-immutability-update-01",
                new BigDecimal("200.0000"),
                "INR",
                TransactionStatus.PENDING,
                aliceAccount,
                bobAccount
        ));
        LedgerEntry entry = ledgerEntryRepository.save(new LedgerEntry(
                tx,
                aliceAccount,
                LedgerEntryType.DEBIT,
                new BigDecimal("200.0000"),
                "INR"
        ));

        // Attempt direct SQL update to verify database trigger rejects it
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE ledger_entries SET amount = ? WHERE id = ?",
                new BigDecimal("999.0000"),
                entry.getId()
        ))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("Ledger entries are immutable")
                .hasMessageContaining("UPDATE operations are not allowed on ledger_entries");

        // Verify the database row remained completely untouched
        LedgerEntry fresh = ledgerEntryRepository.findById(entry.getId()).orElseThrow();
        assertThat(fresh.getAmount()).isEqualByComparingTo(new BigDecimal("200.0000"));
    }

    @Test
    @DisplayName("3. Deleting an existing ledger entry fails at the PostgreSQL database level")
    void deletingExistingLedgerEntryFailsAtDatabaseLevel() {
        Transaction tx = transactionRepository.save(new Transaction(
                "tx-immutability-delete-01",
                new BigDecimal("75.0000"),
                "INR",
                TransactionStatus.PENDING,
                aliceAccount,
                bobAccount
        ));
        LedgerEntry entry = ledgerEntryRepository.save(new LedgerEntry(
                tx,
                aliceAccount,
                LedgerEntryType.DEBIT,
                new BigDecimal("75.0000"),
                "INR"
        ));

        // Attempt direct SQL DELETE to verify database trigger rejects it
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM ledger_entries WHERE id = ?",
                entry.getId()
        ))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("Ledger entries are immutable")
                .hasMessageContaining("DELETE operations are not allowed on ledger_entries");

        // Verify the database row still exists
        assertThat(ledgerEntryRepository.existsById(entry.getId())).isTrue();
    }

    @Test
    @DisplayName("3b. Deleting an existing ledger entry via Spring Data JPA repository fails at the PostgreSQL database level")
    void deletingExistingLedgerEntryViaRepositoryFailsAtDatabaseLevel() {
        Transaction tx = transactionRepository.save(new Transaction(
                "tx-immutability-delete-repo-01",
                new BigDecimal("85.0000"),
                "INR",
                TransactionStatus.PENDING,
                aliceAccount,
                bobAccount
        ));
        LedgerEntry entry = ledgerEntryRepository.save(new LedgerEntry(
                tx,
                aliceAccount,
                LedgerEntryType.DEBIT,
                new BigDecimal("85.0000"),
                "INR"
        ));

        // Attempt deletion via Spring Data JPA repository - must trigger database rejection
        assertThatThrownBy(() -> {
            ledgerEntryRepository.delete(entry);
            ledgerEntryRepository.flush();
        })
                .isInstanceOf(Exception.class)
                .rootCause()
                .hasMessageContaining("Ledger entries are immutable")
                .hasMessageContaining("DELETE operations are not allowed on ledger_entries");

        // Clear persistence context and verify entity is still intact in database
        assertThat(ledgerEntryRepository.existsById(entry.getId())).isTrue();
    }

    @Test
    @DisplayName("4. A normal transfer still succeeds under PostgreSQL immutability enforcement")
    void normalTransferStillSucceeds() {
        TransferRequestDto request = new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("100.0000"),
                "INR",
                "Normal transfer with immutability active"
        );

        TransferResponseDto response = transferService.executeTransfer("tx-v4-normal-01", request);

        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(response.amount()).isEqualByComparingTo(new BigDecimal("100.0000"));

        Account freshAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        Account freshBob = accountRepository.findById(bobAccount.getId()).orElseThrow();

        assertThat(freshAlice.getBalance()).isEqualByComparingTo(new BigDecimal("900.0000"));
        assertThat(freshBob.getBalance()).isEqualByComparingTo(new BigDecimal("600.0000"));
    }

    @Test
    @DisplayName("5. A transfer creates balanced debit and credit ledger entries successfully")
    void transferCreatesDebitAndCreditLedgerEntriesSuccessfully() {
        TransferRequestDto request = new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("250.0000"),
                "INR",
                "Balanced transfer entries test"
        );

        TransferResponseDto response = transferService.executeTransfer("tx-v4-entries-01", request);

        List<LedgerEntry> entries = ledgerEntryRepository.findByTransactionId(response.transactionId());
        assertThat(entries).hasSize(2);

        LedgerEntry debitEntry = entries.stream()
                .filter(e -> e.getEntryType() == LedgerEntryType.DEBIT)
                .findFirst()
                .orElseThrow();
        assertThat(debitEntry.getAccount().getId()).isEqualTo(aliceAccount.getId());
        assertThat(debitEntry.getAmount()).isEqualByComparingTo(new BigDecimal("250.0000"));
        assertThat(debitEntry.getCurrency()).isEqualTo("INR");

        LedgerEntry creditEntry = entries.stream()
                .filter(e -> e.getEntryType() == LedgerEntryType.CREDIT)
                .findFirst()
                .orElseThrow();
        assertThat(creditEntry.getAccount().getId()).isEqualTo(bobAccount.getId());
        assertThat(creditEntry.getAmount()).isEqualByComparingTo(new BigDecimal("250.0000"));
        assertThat(creditEntry.getCurrency()).isEqualTo("INR");

        assertThat(debitEntry.getAmount()).isEqualByComparingTo(creditEntry.getAmount());
    }

    @Test
    @DisplayName("6. Transaction rollback removes newly-created ledger entries because they were never committed")
    void transactionRollbackRemovesUncommittedLedgerEntries() {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        UUID uncommittedEntryId = UUID.randomUUID();

        assertThatThrownBy(() -> txTemplate.execute(status -> {
            Transaction tx = transactionRepository.save(new Transaction(
                    "tx-v4-rollback-01",
                    new BigDecimal("50.0000"),
                    "INR",
                    TransactionStatus.PENDING,
                    aliceAccount,
                    bobAccount
            ));

            LedgerEntry entry = new LedgerEntry(
                    tx,
                    aliceAccount,
                    LedgerEntryType.DEBIT,
                    new BigDecimal("50.0000"),
                    "INR"
            );
            LedgerEntry saved = ledgerEntryRepository.save(entry);

            // Verify entry exists within this uncommitted transaction
            assertThat(saved.getId()).isNotNull();

            // Simulate business failure / rollback condition
            throw new RuntimeException("Simulated business exception to trigger transaction abort");
        })).isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Simulated business exception");

        // Verify outside transaction: uncommitted ledger entries were rolled back by PostgreSQL MVCC
        assertThat(ledgerEntryRepository.count()).isEqualTo(0);
        assertThat(transactionRepository.findByIdempotencyKey("tx-v4-rollback-01")).isEmpty();
    }

    @Test
    @DisplayName("7. Reconciliation still works accurately against immutable ledger entries")
    void reconciliationStillWorks() {
        // Bob starts with 0.0000 balance
        bobAccount.setBalance(BigDecimal.ZERO.setScale(4));
        accountRepository.save(bobAccount);

        // Execute two transfers: 100 USD and 50 USD from Alice to Bob
        transferService.executeTransfer("tx-v4-recon-01", new TransferRequestDto(
                aliceAccount.getId(), bobAccount.getId(), new BigDecimal("100.0000"), "INR", "Transfer 1"
        ));
        transferService.executeTransfer("tx-v4-recon-02", new TransferRequestDto(
                aliceAccount.getId(), bobAccount.getId(), new BigDecimal("50.0000"), "INR", "Transfer 2"
        ));

        // Reconcile Bob's account as Bob (initial 0.0000 balance + 150.0000 credits = 150.0000)
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(bobUser.getEmail(), null, Collections.emptyList())
        );

        ReconciliationResultDto bobResult = reconciliationService.reconcileAccount(bobAccount.getId());
        assertThat(bobResult.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
        assertThat(bobResult.difference()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(bobResult.snapshotBalance()).isEqualByComparingTo(new BigDecimal("150.0000"));
        assertThat(bobResult.ledgerBalance()).isEqualByComparingTo(new BigDecimal("150.0000"));
        assertThat(bobResult.totalCredits()).isEqualByComparingTo(new BigDecimal("150.0000"));
        assertThat(bobResult.totalDebits()).isEqualByComparingTo(BigDecimal.ZERO);

        // Reconcile Bob's user accounts
        OverallReconciliationDto overall = reconciliationService.reconcileUserAccounts();
        assertThat(overall.totalAccountsChecked()).isEqualTo(1);
        assertThat(overall.consistentAccounts()).isEqualTo(1);
        assertThat(overall.discrepancyCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("8. Optimized composite indexes exist and redundant single-column indexes are removed")
    void verifyIndexOptimizationAndRedundancyRemoval() {
        List<String> ledgerIndexes = jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'ledger_entries'",
                String.class
        );
        assertThat(ledgerIndexes).contains("idx_ledger_entries_account_id_created_at");
        assertThat(ledgerIndexes).doesNotContain("idx_ledger_entries_account_id");
        assertThat(ledgerIndexes).contains("idx_ledger_entries_transaction_id");
        assertThat(ledgerIndexes).contains("idx_ledger_entries_created_at");

        List<String> txIndexes = jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'transactions'",
                String.class
        );
        assertThat(txIndexes).contains("idx_transactions_source_account_id_created_at");
        assertThat(txIndexes).doesNotContain("idx_transactions_source_account_id");
        assertThat(txIndexes).contains("idx_transactions_destination_account_id_created_at");
        assertThat(txIndexes).doesNotContain("idx_transactions_destination_account_id");
        assertThat(txIndexes).contains("idx_transactions_status");
        assertThat(txIndexes).contains("idx_transactions_created_at");
        assertThat(txIndexes).contains("idx_transactions_transaction_type");
        assertThat(txIndexes).contains("idx_transactions_initiated_by_user_id");
    }
}
