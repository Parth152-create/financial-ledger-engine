package com.parth.ledger;

import com.parth.ledger.account.Account;
import com.parth.ledger.account.AccountRepository;
import com.parth.ledger.ledger.LedgerEntry;
import com.parth.ledger.ledger.LedgerEntryRepository;
import com.parth.ledger.ledger.LedgerEntryType;
import com.parth.ledger.transaction.Transaction;
import com.parth.ledger.transaction.TransactionRepository;
import com.parth.ledger.transaction.TransactionStatus;
import com.parth.ledger.user.User;
import com.parth.ledger.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class CoreLedgerPersistenceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("ledger_test")
            .withUsername("ledger_user")
            .withPassword("ledger_pass");

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Test
    @DisplayName("Verify full persistence lifecycle: User, Accounts, Transaction, and Balanced Ledger Entries")
    void verifyCoreLedgerPersistenceLifecycle() {
        // 1. Create and persist Users
        User alice = userRepository.save(new User("alice@example.com", "Alice Smith"));
        User bob = userRepository.save(new User("bob@example.com", "Bob Jones"));

        assertThat(alice.getId()).isNotNull();
        assertThat(alice.getCreatedAt()).isNotNull();
        assertThat(alice.getUpdatedAt()).isNotNull();
        assertThat(bob.getId()).isNotNull();

        // 2. Create and persist Accounts
        BigDecimal initialAliceBalance = new BigDecimal("1000.5000");
        BigDecimal initialBobBalance = new BigDecimal("250.0000");

        Account sourceAccount = accountRepository.save(new Account(alice, "INR", initialAliceBalance));
        Account destinationAccount = accountRepository.save(new Account(bob, "INR", initialBobBalance));

        assertThat(sourceAccount.getId()).isNotNull();
        assertThat(sourceAccount.getVersion()).isNotNull();
        assertThat(destinationAccount.getId()).isNotNull();

        // 3. Create and persist Transaction
        String idempotencyKey = "tx-transfer-001";
        BigDecimal transferAmount = new BigDecimal("150.2500");

        Transaction transaction = new Transaction(
                idempotencyKey,
                transferAmount,
                "INR",
                TransactionStatus.PENDING,
                sourceAccount,
                destinationAccount
        );
        Transaction savedTx = transactionRepository.save(transaction);

        assertThat(savedTx.getId()).isNotNull();
        assertThat(savedTx.getStatus()).isEqualTo(TransactionStatus.PENDING);
        assertThat(savedTx.getAmount()).isEqualByComparingTo(transferAmount);

        // 4. Create and persist double-entry Ledger Entries (DEBIT source, CREDIT destination)
        LedgerEntry debitEntry = new LedgerEntry(
                savedTx,
                sourceAccount,
                LedgerEntryType.DEBIT,
                transferAmount
        );
        LedgerEntry creditEntry = new LedgerEntry(
                savedTx,
                destinationAccount,
                LedgerEntryType.CREDIT,
                transferAmount
        );

        LedgerEntry savedDebit = ledgerEntryRepository.save(debitEntry);
        LedgerEntry savedCredit = ledgerEntryRepository.save(creditEntry);

        assertThat(savedDebit.getId()).isNotNull();
        assertThat(savedDebit.getEntryType()).isEqualTo(LedgerEntryType.DEBIT);
        assertThat(savedDebit.getCreatedAt()).isNotNull();

        assertThat(savedCredit.getId()).isNotNull();
        assertThat(savedCredit.getEntryType()).isEqualTo(LedgerEntryType.CREDIT);
        assertThat(savedCredit.getCreatedAt()).isNotNull();

        // 5. Verify round-trip retrieval and assertions
        Optional<Transaction> retrievedTxOpt = transactionRepository.findById(savedTx.getId());
        assertThat(retrievedTxOpt).isPresent();
        Transaction retrievedTx = retrievedTxOpt.get();
        assertThat(retrievedTx.getIdempotencyKey()).isEqualTo(idempotencyKey);
        assertThat(retrievedTx.getAmount()).isEqualByComparingTo(new BigDecimal("150.2500"));
        assertThat(retrievedTx.getCurrency()).isEqualTo("INR");
        assertThat(retrievedTx.getStatus()).isEqualTo(TransactionStatus.PENDING);
        assertThat(retrievedTx.getSourceAccount().getId()).isEqualTo(sourceAccount.getId());
        assertThat(retrievedTx.getDestinationAccount().getId()).isEqualTo(destinationAccount.getId());

        List<LedgerEntry> entries = ledgerEntryRepository.findByTransactionId(savedTx.getId());
        assertThat(entries).hasSize(2);

        BigDecimal totalDebits = entries.stream()
                .filter(e -> e.getEntryType() == LedgerEntryType.DEBIT)
                .map(LedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalCredits = entries.stream()
                .filter(e -> e.getEntryType() == LedgerEntryType.CREDIT)
                .map(LedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Verify balanced double-entry
        assertThat(totalDebits).isEqualByComparingTo(transferAmount);
        assertThat(totalCredits).isEqualByComparingTo(transferAmount);
        assertThat(totalDebits).isEqualByComparingTo(totalCredits);

        // Update transaction status to COMPLETED
        retrievedTx.setStatus(TransactionStatus.COMPLETED);
        retrievedTx.setCompletedAt(java.time.Instant.now());
        Transaction updatedTx = transactionRepository.save(retrievedTx);
        assertThat(updatedTx.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(updatedTx.getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("Verify unique idempotency key constraint rejects duplicates")
    void verifyUniqueIdempotencyKeyConstraint() {
        User user = userRepository.save(new User("idemp-test@example.com", "Idemp User"));
        Account acc1 = accountRepository.save(new Account(user, "INR", new BigDecimal("500.0000")));
        Account acc2 = accountRepository.save(new Account(user, "INR", new BigDecimal("500.0000")));

        String duplicateKey = "tx-dup-key-999";
        Transaction tx1 = new Transaction(
                duplicateKey,
                new BigDecimal("50.0000"),
                "INR",
                TransactionStatus.PENDING,
                acc1,
                acc2
        );
        transactionRepository.saveAndFlush(tx1);

        Transaction tx2 = new Transaction(
                duplicateKey,
                new BigDecimal("50.0000"),
                "INR",
                TransactionStatus.PENDING,
                acc1,
                acc2
        );

        assertThatThrownBy(() -> transactionRepository.saveAndFlush(tx2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Verify unique email constraint rejects duplicate users")
    void verifyUniqueEmailConstraint() {
        userRepository.saveAndFlush(new User("unique@example.com", "First User"));

        assertThatThrownBy(() -> userRepository.saveAndFlush(new User("unique@example.com", "Second User")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Verify negative account balance check constraint triggers violation")
    void verifyNegativeAccountBalanceConstraint() {
        User user = userRepository.save(new User("negative-test@example.com", "Balance Test"));
        Account negativeAccount = new Account(user, "INR", new BigDecimal("-10.0000"));

        assertThatThrownBy(() -> accountRepository.saveAndFlush(negativeAccount))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Verify monetary precision and scale round-trip correctly")
    void verifyMonetaryPrecisionRoundTrip() {
        User user = userRepository.save(new User("precision@example.com", "Precision User"));
        BigDecimal preciseBalance = new BigDecimal("123456789012345.6789");
        Account account = accountRepository.save(new Account(user, "INR", preciseBalance));

        Account retrieved = accountRepository.findById(account.getId()).orElseThrow();
        assertThat(retrieved.getBalance()).isEqualByComparingTo(preciseBalance);
        assertThat(retrieved.getBalance().scale()).isEqualTo(4);
    }
}
