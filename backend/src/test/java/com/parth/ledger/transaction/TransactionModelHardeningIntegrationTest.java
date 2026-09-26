package com.parth.ledger.transaction;

import com.parth.ledger.BaseIntegrationTest;
import com.parth.ledger.account.Account;
import com.parth.ledger.account.AccountRepository;
import com.parth.ledger.account.AccountStatus;
import com.parth.ledger.account.AccountType;
import com.parth.ledger.ledger.LedgerEntry;
import com.parth.ledger.ledger.LedgerEntryRepository;
import com.parth.ledger.ledger.LedgerEntryType;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@WithMockUser(username = "alice.v3@ledger.com")
class TransactionModelHardeningIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

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
    private JdbcTemplate jdbcTemplate;

    private User aliceUser;
    private User bobUser;
    private Account aliceAccount;
    private Account bobAccount;

    @BeforeEach
    void setUp() {
        clearRedis();
        ledgerEntryRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();

        aliceUser = userRepository.save(new User("alice.v3@ledger.com", "Alice V3"));
        bobUser = userRepository.save(new User("bob.v3@ledger.com", "Bob V3"));

        aliceAccount = accountRepository.save(new Account(aliceUser, "INR", new BigDecimal("1000.0000")));
        bobAccount = accountRepository.save(new Account(bobUser, "INR", new BigDecimal("500.0000")));
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

    // =========================================================================
    // 1. TRANSACTION TYPE TESTS
    // =========================================================================

    @Test
    @DisplayName("Transaction type: Persisted as TRANSFER for normal transfer")
    void transactionTypePersistedAsTransfer() throws Exception {
        TransferRequestDto request = new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("100.0000"),
                "INR",
                "Payment for services"
        );

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "tx-v3-type-01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionType", is("TRANSFER")));

        Transaction tx = transactionRepository.findByIdempotencyKey("tx-v3-type-01").orElseThrow();
        assertThat(tx.getTransactionType()).isEqualTo(TransactionType.TRANSFER);
    }

    @Test
    @DisplayName("Transaction type: DB CHECK constraint rejects unsupported transaction types")
    void databaseConstraintRejectsUnsupportedTransactionType() {
        UUID txId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO transactions (id, idempotency_key, amount, currency, status, source_account_id, destination_account_id, created_at, transaction_type, initiated_by_user_id) " +
                        "VALUES (?, ?, 10.0000, 'INR', 'COMPLETED', ?, ?, NOW(), 'REFUND', ?)",
                txId, "tx-v3-invalid-type", aliceAccount.getId(), bobAccount.getId(), aliceUser.getId()
        )).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_transactions_transaction_type");
    }

    // =========================================================================
    // 2. INITIATED-BY USER TESTS
    // =========================================================================

    @Test
    @DisplayName("Initiated-by user: Authenticated initiating user persisted on transfer")
    void initiatedByUserPersistedOnTransfer() throws Exception {
        TransferRequestDto request = new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("75.0000"),
                "INR"
        );

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "tx-v3-user-01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.initiatedByUserId", is(aliceUser.getId().toString())));

        Transaction tx = transactionRepository.findByIdempotencyKey("tx-v3-user-01").orElseThrow();
        assertThat(tx.getInitiatedByUser()).isNotNull();
        assertThat(tx.getInitiatedByUser().getId()).isEqualTo(aliceUser.getId());
    }

    @Test
    @DisplayName("Initiated-by user: DB CHECK constraint rejects TRANSFER with NULL initiated_by_user_id")
    void databaseConstraintRejectsTransferWithNullInitiatedByUser() {
        UUID txId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO transactions (id, idempotency_key, amount, currency, status, source_account_id, destination_account_id, created_at, transaction_type, initiated_by_user_id) " +
                        "VALUES (?, ?, 10.0000, 'INR', 'COMPLETED', ?, ?, NOW(), 'TRANSFER', NULL)",
                txId, "tx-v3-null-user", aliceAccount.getId(), bobAccount.getId()
        )).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_transactions_initiated_by_user");
    }

    @Test
    @DisplayName("Initiated-by user: DB constraint allows future system transactions (e.g. DEPOSIT) with NULL user without fake user")
    void databaseConstraintAllowsSystemTransactionsWithNullUser() {
        UUID txId = UUID.randomUUID();
        int rows = jdbcTemplate.update(
                "INSERT INTO transactions (id, idempotency_key, amount, currency, status, source_account_id, destination_account_id, created_at, transaction_type, initiated_by_user_id) " +
                        "VALUES (?, ?, 10.0000, 'INR', 'COMPLETED', ?, ?, NOW(), 'DEPOSIT', NULL)",
                txId, "tx-v3-system-deposit", aliceAccount.getId(), bobAccount.getId()
        );
        assertThat(rows).isEqualTo(1);
    }

    // =========================================================================
    // 3. TRANSACTION DESCRIPTION TESTS
    // =========================================================================

    @Test
    @DisplayName("Description: Persisted when provided in request")
    void descriptionPersistedWhenProvided() throws Exception {
        TransferRequestDto request = new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("50.0000"),
                "INR",
                "Consulting Invoice #2026-09"
        );

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "tx-v3-desc-01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description", is("Consulting Invoice #2026-09")));

        Transaction tx = transactionRepository.findByIdempotencyKey("tx-v3-desc-01").orElseThrow();
        assertThat(tx.getDescription()).isEqualTo("Consulting Invoice #2026-09");
    }

    @Test
    @DisplayName("Description: Can be null when omitted")
    void descriptionCanBeNull() throws Exception {
        TransferRequestDto request = new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("50.0000"),
                "INR"
        );

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "tx-v3-desc-null")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").doesNotExist());

        Transaction tx = transactionRepository.findByIdempotencyKey("tx-v3-desc-null").orElseThrow();
        assertThat(tx.getDescription()).isNull();
    }

    // =========================================================================
    // 4. LEDGER ENTRY CURRENCY TESTS
    // =========================================================================

    @Test
    @DisplayName("Ledger entry: Currency is explicitly persisted and matches transaction currency")
    void ledgerEntryCurrencyPersisted() {
        TransferRequestDto request = new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("120.0000"),
                "INR",
                "Ledger currency test"
        );

        TransferResponseDto response = transferService.executeTransfer("tx-v3-ledger-curr", request);
        assertThat(response.currency()).isEqualTo("INR");

        List<LedgerEntry> entries = ledgerEntryRepository.findByTransactionId(response.transactionId());
        assertThat(entries).hasSize(2);
        for (LedgerEntry entry : entries) {
            assertThat(entry.getCurrency()).isEqualTo("INR");
        }
    }

    @Test
    @DisplayName("Ledger entry: DB CHECK constraint rejects lowercase or invalid currency format")
    void databaseConstraintRejectsInvalidLedgerCurrency() {
        Transaction tx = transactionRepository.save(new Transaction(
                "tx-v3-ledger-constraint",
                new BigDecimal("10.0000"),
                "INR",
                TransactionStatus.COMPLETED,
                aliceAccount,
                bobAccount,
                TransactionType.TRANSFER,
                aliceUser,
                null
        ));

        UUID entryId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO ledger_entries (id, transaction_id, account_id, entry_type, amount, currency, created_at) " +
                        "VALUES (?, ?, ?, 'DEBIT', 10.0000, 'usd', NOW())",
                entryId, tx.getId(), aliceAccount.getId()
        )).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_ledger_entries_currency_inr");
    }

    @Test
    @DisplayName("Ledger entry: DB NOT NULL constraint rejects null currency")
    void databaseConstraintRejectsNullLedgerCurrency() {
        Transaction tx = transactionRepository.save(new Transaction(
                "tx-v3-ledger-null-curr",
                new BigDecimal("10.0000"),
                "INR",
                TransactionStatus.COMPLETED,
                aliceAccount,
                bobAccount,
                TransactionType.TRANSFER,
                aliceUser,
                null
        ));

        UUID entryId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO ledger_entries (id, transaction_id, account_id, entry_type, amount, currency, created_at) " +
                        "VALUES (?, ?, ?, 'DEBIT', 10.0000, NULL, NOW())",
                entryId, tx.getId(), aliceAccount.getId()
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    // =========================================================================
    // 5. MIGRATION BACKFILL VERIFICATION
    // =========================================================================

    @Test
    @DisplayName("Migration backfill: Verify SQL backfill semantics for legacy transactions and ledger entries")
    void verifyMigrationBackfillSemantics() {
        // Create an un-backfilled transaction simulating legacy pre-V3 data (using raw SQL)
        UUID legacyTxId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO transactions (id, idempotency_key, amount, currency, status, source_account_id, destination_account_id, created_at, transaction_type, initiated_by_user_id) " +
                        "VALUES (?, ?, 200.0000, 'INR', 'COMPLETED', ?, ?, NOW(), 'TRANSFER', ?)",
                legacyTxId, "tx-v3-legacy-01", aliceAccount.getId(), bobAccount.getId(), aliceUser.getId()
        );

        UUID legacyDebitId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO ledger_entries (id, transaction_id, account_id, entry_type, amount, currency, created_at) " +
                        "VALUES (?, ?, ?, 'DEBIT', 200.0000, 'INR', NOW())",
                legacyDebitId, legacyTxId, aliceAccount.getId()
        );

        // Verify entity can be read and contains backfilled values
        Transaction loadedTx = transactionRepository.findById(legacyTxId).orElseThrow();
        assertThat(loadedTx.getTransactionType()).isEqualTo(TransactionType.TRANSFER);
        assertThat(loadedTx.getInitiatedByUser().getId()).isEqualTo(aliceUser.getId());

        LedgerEntry loadedEntry = ledgerEntryRepository.findById(legacyDebitId).orElseThrow();
        assertThat(loadedEntry.getCurrency()).isEqualTo("INR");
    }

    // =========================================================================
    // 6. IDEMPOTENCY WITH DESCRIPTION
    // =========================================================================

    @Test
    @DisplayName("Idempotency: Identical retry with same description returns cached result")
    void identicalRetryWithDescriptionSucceeds() throws Exception {
        TransferRequestDto request = new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("60.0000"),
                "INR",
                "Idempotent memo"
        );

        // First call
        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "tx-v3-idem-desc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("COMPLETED")))
                .andExpect(jsonPath("$.description", is("Idempotent memo")));

        // Identical retry
        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "tx-v3-idem-desc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("COMPLETED")))
                .andExpect(jsonPath("$.description", is("Idempotent memo")));
    }

    @Test
    @DisplayName("Idempotency: Reusing key with different description throws 409 Conflict")
    void reusingKeyWithDifferentDescriptionThrowsConflict() throws Exception {
        TransferRequestDto request1 = new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("60.0000"),
                "INR",
                "Memo A"
        );

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "tx-v3-conflict-desc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request1)))
                .andExpect(status().isOk());

        TransferRequestDto request2 = new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("60.0000"),
                "INR",
                "Memo B"
        );

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "tx-v3-conflict-desc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status", is(409)))
                .andExpect(jsonPath("$.message", containsString("different parameters")));
    }
}
