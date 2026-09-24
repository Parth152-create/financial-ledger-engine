package com.parth.ledger.statement;

import com.parth.ledger.BaseIntegrationTest;
import com.parth.ledger.account.Account;
import com.parth.ledger.account.AccountRepository;
import com.parth.ledger.account.AccountStatus;
import com.parth.ledger.account.AccountType;
import com.parth.ledger.deposit.dto.DepositRequestDto;
import com.parth.ledger.deposit.service.DepositService;
import com.parth.ledger.ledger.LedgerEntryRepository;
import com.parth.ledger.reconciliation.ReconciliationStatus;
import com.parth.ledger.reconciliation.dto.ReconciliationResultDto;
import com.parth.ledger.reconciliation.service.ReconciliationService;
import com.parth.ledger.statement.dto.AccountStatementResponseDto;
import com.parth.ledger.transaction.TransactionRepository;
import com.parth.ledger.transaction.TransactionStatus;
import com.parth.ledger.transaction.TransactionType;
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
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@DisplayName("V8 Account Statement & Running Balance Integration Tests")
class AccountStatementIntegrationTest extends BaseIntegrationTest {

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
    private DepositService depositService;

    @Autowired
    private TransferService transferService;

    @Autowired
    private ReconciliationService reconciliationService;

    private User aliceUser;
    private User bobUser;
    private Account aliceAccount;
    private Account bobAccount;
    private Account clearingAccount;

    private static final BigDecimal INITIAL_CLEARING_BALANCE = new BigDecimal("100000.0000");

    @BeforeEach
    void setUp() {
        clearRedis();
        if (jdbcTemplate != null) {
            jdbcTemplate.execute("TRUNCATE TABLE ledger_entries CASCADE");
        }
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();

        aliceUser = userRepository.save(new User("alice.v8@ledger.com", "Alice V8"));
        bobUser = userRepository.save(new User("bob.v8@ledger.com", "Bob V8"));

        aliceAccount = accountRepository.save(new Account(
                aliceUser,
                "USD",
                BigDecimal.ZERO.setScale(4),
                AccountType.USER_CHECKING,
                AccountStatus.ACTIVE,
                "ACCT-ALICE-V8"
        ));

        bobAccount = accountRepository.save(new Account(
                bobUser,
                "USD",
                BigDecimal.ZERO.setScale(4),
                AccountType.USER_CHECKING,
                AccountStatus.ACTIVE,
                "ACCT-BOB-V8"
        ));

        clearingAccount = ensureSystemClearingAccount(INITIAL_CLEARING_BALANCE);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        if (jdbcTemplate != null) {
            jdbcTemplate.execute("TRUNCATE TABLE ledger_entries CASCADE");
        }
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();
    }

    private Account ensureSystemClearingAccount(BigDecimal initialBalance) {
        jdbcTemplate.update(
                "INSERT INTO accounts (id, user_id, currency, balance, version, created_at, updated_at, account_type, status, account_number) " +
                        "VALUES ('00000000-0000-0000-0000-000000000001', NULL, 'USD', ?, 0, NOW(), NOW(), 'SYSTEM_CLEARING', 'ACTIVE', 'ACCT-SYSTEM-CLEARING-01') " +
                        "ON CONFLICT (id) DO UPDATE SET balance = EXCLUDED.balance, status = 'ACTIVE'",
                initialBalance
        );
        return accountRepository.findById(DepositService.SYSTEM_CLEARING_ACCOUNT_ID).orElseThrow();
    }

    private void insertTransactionAndLedgerEntries(
            UUID txId,
            UUID sourceLedgerId,
            UUID destLedgerId,
            String idempotencyKey,
            Account source,
            Account dest,
            BigDecimal amount,
            String currency,
            TransactionType type,
            TransactionStatus status,
            User initiatedBy,
            String description,
            Instant createdAt,
            Instant completedAt
    ) {
        jdbcTemplate.update(
                "INSERT INTO transactions (id, idempotency_key, amount, currency, status, transaction_type, initiated_by_user_id, description, source_account_id, destination_account_id, created_at, completed_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                txId, idempotencyKey, amount, currency, status.name(), type.name(),
                initiatedBy != null ? initiatedBy.getId() : null,
                description, source.getId(), dest.getId(),
                Timestamp.from(createdAt),
                completedAt != null ? Timestamp.from(completedAt) : null
        );

        jdbcTemplate.update(
                "INSERT INTO ledger_entries (id, transaction_id, account_id, entry_type, amount, currency, created_at) " +
                        "VALUES (?, ?, ?, 'DEBIT', ?, ?, ?)",
                sourceLedgerId != null ? sourceLedgerId : UUID.randomUUID(),
                txId, source.getId(), amount, currency, Timestamp.from(createdAt)
        );

        jdbcTemplate.update(
                "INSERT INTO ledger_entries (id, transaction_id, account_id, entry_type, amount, currency, created_at) " +
                        "VALUES (?, ?, ?, 'CREDIT', ?, ?, ?)",
                destLedgerId != null ? destLedgerId : UUID.randomUUID(),
                txId, dest.getId(), amount, currency, Timestamp.from(createdAt)
        );
    }

    @Test
    @DisplayName("1. Authenticated user can retrieve statement")
    void test01_authenticatedUserCanRetrieveStatement() throws Exception {
        Instant t = Instant.parse("2026-09-24T10:00:00Z");
        insertTransactionAndLedgerEntries(
                UUID.randomUUID(), null, null, "s-tx-1",
                clearingAccount, aliceAccount, new BigDecimal("500.0000"), "USD",
                TransactionType.DEPOSIT, TransactionStatus.COMPLETED, null, "Funding", t, t
        );

        mockMvc.perform(get("/api/v1/accounts/{accountId}/statement", aliceAccount.getId())
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId", is(aliceAccount.getId().toString())))
                .andExpect(jsonPath("$.accountNumber", is(aliceAccount.getAccountNumber())))
                .andExpect(jsonPath("$.currency", is("USD")))
                .andExpect(jsonPath("$.openingBalance", is(0.0)))
                .andExpect(jsonPath("$.closingBalance", is(500.0)))
                .andExpect(jsonPath("$.totalCredits", is(500.0)))
                .andExpect(jsonPath("$.totalDebits", is(0.0)))
                .andExpect(jsonPath("$.entries", hasSize(1)))
                .andExpect(jsonPath("$.entries[0].balanceAfter", is(500.0)));
    }

    @Test
    @DisplayName("2. Empty account returns zero opening and closing balance")
    void test02_emptyAccountReturnsZeroBalances() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/{accountId}/statement", aliceAccount.getId())
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openingBalance", is(0.0)))
                .andExpect(jsonPath("$.closingBalance", is(0.0)))
                .andExpect(jsonPath("$.totalCredits", is(0.0)))
                .andExpect(jsonPath("$.totalDebits", is(0.0)))
                .andExpect(jsonPath("$.entries", hasSize(0)))
                .andExpect(jsonPath("$.totalElements", is(0)))
                .andExpect(jsonPath("$.totalPages", is(0)));
    }

    @Test
    @DisplayName("3. Statement returns chronological order (createdAt ASC)")
    void test03_statementReturnsChronologicalOrder() throws Exception {
        Instant t1 = Instant.parse("2026-09-24T08:00:00Z");
        Instant t2 = Instant.parse("2026-09-24T09:00:00Z");
        Instant t3 = Instant.parse("2026-09-24T10:00:00Z");

        UUID tx1 = UUID.randomUUID();
        UUID tx2 = UUID.randomUUID();
        UUID tx3 = UUID.randomUUID();

        insertTransactionAndLedgerEntries(tx1, null, null, "tx-order-1", clearingAccount, aliceAccount, new BigDecimal("100.0000"), "USD", TransactionType.DEPOSIT, TransactionStatus.COMPLETED, null, "First", t1, t1);
        insertTransactionAndLedgerEntries(tx2, null, null, "tx-order-2", clearingAccount, aliceAccount, new BigDecimal("200.0000"), "USD", TransactionType.DEPOSIT, TransactionStatus.COMPLETED, null, "Second", t2, t2);
        insertTransactionAndLedgerEntries(tx3, null, null, "tx-order-3", clearingAccount, aliceAccount, new BigDecimal("300.0000"), "USD", TransactionType.DEPOSIT, TransactionStatus.COMPLETED, null, "Third", t3, t3);

        mockMvc.perform(get("/api/v1/accounts/{accountId}/statement", aliceAccount.getId())
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries", hasSize(3)))
                .andExpect(jsonPath("$.entries[0].transactionId", is(tx1.toString())))
                .andExpect(jsonPath("$.entries[1].transactionId", is(tx2.toString())))
                .andExpect(jsonPath("$.entries[2].transactionId", is(tx3.toString())));
    }

    @Test
    @DisplayName("4. Deterministic ordering works for identical timestamps (id ASC)")
    void test04_deterministicOrderingForIdenticalTimestamps() throws Exception {
        Instant sameTime = Instant.parse("2026-09-24T10:00:00Z");
        UUID tx1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID tx2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID le1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID le2 = UUID.fromString("00000000-0000-0000-0000-000000000002");

        insertTransactionAndLedgerEntries(tx1, null, le1, "same-1", clearingAccount, aliceAccount, new BigDecimal("10.0000"), "USD", TransactionType.DEPOSIT, TransactionStatus.COMPLETED, null, "First tie", sameTime, sameTime);
        insertTransactionAndLedgerEntries(tx2, null, le2, "same-2", clearingAccount, aliceAccount, new BigDecimal("20.0000"), "USD", TransactionType.DEPOSIT, TransactionStatus.COMPLETED, null, "Second tie", sameTime, sameTime);

        mockMvc.perform(get("/api/v1/accounts/{accountId}/statement", aliceAccount.getId())
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries", hasSize(2)))
                .andExpect(jsonPath("$.entries[0].transactionId", is(tx1.toString())))
                .andExpect(jsonPath("$.entries[1].transactionId", is(tx2.toString())));
    }

    @Test
    @DisplayName("5. Account metadata is correct")
    void test05_accountMetadataCorrect() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/{accountId}/statement", aliceAccount.getId())
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId", is(aliceAccount.getId().toString())))
                .andExpect(jsonPath("$.accountNumber", is(aliceAccount.getAccountNumber())))
                .andExpect(jsonPath("$.currency", is("USD")));
    }

    @Test
    @DisplayName("6. Currency is correct")
    void test06_currencyIsCorrect() throws Exception {
        Account eurAccount = accountRepository.save(new Account(
                aliceUser, "EUR", BigDecimal.ZERO.setScale(4), AccountType.USER_CHECKING, AccountStatus.ACTIVE, "ACCT-ALICE-EUR"
        ));

        mockMvc.perform(get("/api/v1/accounts/{accountId}/statement", eurAccount.getId())
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency", is("EUR")));
    }

    @Test
    @DisplayName("7. Deposit increases running balance")
    void test07_depositIncreasesRunningBalance() throws Exception {
        Instant t = Instant.parse("2026-09-24T10:00:00Z");
        insertTransactionAndLedgerEntries(UUID.randomUUID(), null, null, "dep-1", clearingAccount, aliceAccount, new BigDecimal("100.0000"), "USD", TransactionType.DEPOSIT, TransactionStatus.COMPLETED, null, "Dep", t, t);

        mockMvc.perform(get("/api/v1/accounts/{accountId}/statement", aliceAccount.getId())
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].direction", is("CREDIT")))
                .andExpect(jsonPath("$.entries[0].balanceAfter", is(100.0)));
    }

    @Test
    @DisplayName("8. Outgoing transfer decreases running balance")
    void test08_outgoingTransferDecreasesRunningBalance() throws Exception {
        Instant t1 = Instant.parse("2026-09-24T09:00:00Z");
        Instant t2 = Instant.parse("2026-09-24T10:00:00Z");

        insertTransactionAndLedgerEntries(UUID.randomUUID(), null, null, "init-dep", clearingAccount, aliceAccount, new BigDecimal("500.0000"), "USD", TransactionType.DEPOSIT, TransactionStatus.COMPLETED, null, "Dep", t1, t1);
        insertTransactionAndLedgerEntries(UUID.randomUUID(), null, null, "out-tx", aliceAccount, bobAccount, new BigDecimal("200.0000"), "USD", TransactionType.TRANSFER, TransactionStatus.COMPLETED, aliceUser, "Transfer Out", t2, t2);

        mockMvc.perform(get("/api/v1/accounts/{accountId}/statement", aliceAccount.getId())
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries", hasSize(2)))
                .andExpect(jsonPath("$.entries[0].balanceAfter", is(500.0)))
                .andExpect(jsonPath("$.entries[1].direction", is("DEBIT")))
                .andExpect(jsonPath("$.entries[1].balanceAfter", is(300.0)));
    }

    @Test
    @DisplayName("9. Incoming transfer increases running balance")
    void test09_incomingTransferIncreasesRunningBalance() throws Exception {
        Instant t = Instant.parse("2026-09-24T10:00:00Z");
        insertTransactionAndLedgerEntries(UUID.randomUUID(), null, null, "in-tx", bobAccount, aliceAccount, new BigDecimal("350.0000"), "USD", TransactionType.TRANSFER, TransactionStatus.COMPLETED, bobUser, "Incoming", t, t);

        mockMvc.perform(get("/api/v1/accounts/{accountId}/statement", aliceAccount.getId())
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].direction", is("CREDIT")))
                .andExpect(jsonPath("$.entries[0].balanceAfter", is(350.0)));
    }

    @Test
    @DisplayName("10. Multiple transactions produce correct sequential balances")
    void test10_multipleTransactionsSequentialBalances() throws Exception {
        Instant t1 = Instant.parse("2026-09-24T08:00:00Z");
        Instant t2 = Instant.parse("2026-09-24T09:00:00Z");
        Instant t3 = Instant.parse("2026-09-24T10:00:00Z");
        Instant t4 = Instant.parse("2026-09-24T11:00:00Z");

        insertTransactionAndLedgerEntries(UUID.randomUUID(), null, null, "seq-1", clearingAccount, aliceAccount, new BigDecimal("1000.0000"), "USD", TransactionType.DEPOSIT, TransactionStatus.COMPLETED, null, "Deposit", t1, t1);
        insertTransactionAndLedgerEntries(UUID.randomUUID(), null, null, "seq-2", clearingAccount, aliceAccount, new BigDecimal("500.0000"), "USD", TransactionType.DEPOSIT, TransactionStatus.COMPLETED, null, "Deposit 2", t2, t2);
        insertTransactionAndLedgerEntries(UUID.randomUUID(), null, null, "seq-3", aliceAccount, bobAccount, new BigDecimal("200.0000"), "USD", TransactionType.TRANSFER, TransactionStatus.COMPLETED, aliceUser, "Transfer Out", t3, t3);
        insertTransactionAndLedgerEntries(UUID.randomUUID(), null, null, "seq-4", bobAccount, aliceAccount, new BigDecimal("300.0000"), "USD", TransactionType.TRANSFER, TransactionStatus.COMPLETED, bobUser, "Transfer In", t4, t4);

        mockMvc.perform(get("/api/v1/accounts/{accountId}/statement", aliceAccount.getId())
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries", hasSize(4)))
                .andExpect(jsonPath("$.entries[0].balanceAfter", is(1000.0)))
                .andExpect(jsonPath("$.entries[1].balanceAfter", is(1500.0)))
                .andExpect(jsonPath("$.entries[2].balanceAfter", is(1300.0)))
                .andExpect(jsonPath("$.entries[3].balanceAfter", is(1600.0)))
                .andExpect(jsonPath("$.closingBalance", is(1600.0)));
    }

    @Test
    @DisplayName("11. Closing balance equals opening + credits - debits when unfiltered")
    void test11_closingBalanceEqualsFormula() throws Exception {
        Instant t1 = Instant.parse("2026-09-24T08:00:00Z");
        Instant t2 = Instant.parse("2026-09-24T09:00:00Z");

        insertTransactionAndLedgerEntries(UUID.randomUUID(), null, null, "cb-1", clearingAccount, aliceAccount, new BigDecimal("500.0000"), "USD", TransactionType.DEPOSIT, TransactionStatus.COMPLETED, null, "Dep", t1, t1);
        insertTransactionAndLedgerEntries(UUID.randomUUID(), null, null, "cb-2", aliceAccount, bobAccount, new BigDecimal("150.0000"), "USD", TransactionType.TRANSFER, TransactionStatus.COMPLETED, aliceUser, "Tx", t2, t2);

        mockMvc.perform(get("/api/v1/accounts/{accountId}/statement", aliceAccount.getId())
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openingBalance", is(0.0)))
                .andExpect(jsonPath("$.totalCredits", is(500.0)))
                .andExpect(jsonPath("$.totalDebits", is(150.0)))
                .andExpect(jsonPath("$.closingBalance", is(350.0)));
    }

    @Test
    @DisplayName("12. Opening balance is zero for full-history statement")
    void test12_openingBalanceZeroForFullHistory() throws Exception {
        Instant t = Instant.parse("2026-09-24T10:00:00Z");
        insertTransactionAndLedgerEntries(UUID.randomUUID(), null, null, "full-1", clearingAccount, aliceAccount, new BigDecimal("500.0000"), "USD", TransactionType.DEPOSIT, TransactionStatus.COMPLETED, null, "Dep", t, t);

        mockMvc.perform(get("/api/v1/accounts/{accountId}/statement", aliceAccount.getId())
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openingBalance", is(0.0)));
    }

    @Test
    @DisplayName("13. Opening balance correctly represents balance before FROM")
    void test13_openingBalanceRepresentsBalanceBeforeFrom() throws Exception {
        Instant t1 = Instant.parse("2026-09-24T08:00:00Z");
        Instant t2 = Instant.parse("2026-09-24T09:00:00Z");
        Instant t3 = Instant.parse("2026-09-24T10:00:00Z");
        Instant from = Instant.parse("2026-09-24T09:30:00Z");

        insertTransactionAndLedgerEntries(UUID.randomUUID(), null, null, "ob-1", clearingAccount, aliceAccount, new BigDecimal("1000.0000"), "USD", TransactionType.DEPOSIT, TransactionStatus.COMPLETED, null, "Dep", t1, t1);
        insertTransactionAndLedgerEntries(UUID.randomUUID(), null, null, "ob-2", aliceAccount, bobAccount, new BigDecimal("300.0000"), "USD", TransactionType.TRANSFER, TransactionStatus.COMPLETED, aliceUser, "Tx", t2, t2);
        insertTransactionAndLedgerEntries(UUID.randomUUID(), null, null, "ob-3", clearingAccount, aliceAccount, new BigDecimal("200.0000"), "USD", TransactionType.DEPOSIT, TransactionStatus.COMPLETED, null, "Dep 2", t3, t3);

        mockMvc.perform(get("/api/v1/accounts/{accountId}/statement", aliceAccount.getId())
                        .param("from", from.toString())
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openingBalance", is(700.0)))
                .andExpect(jsonPath("$.entries", hasSize(1)))
                .andExpect(jsonPath("$.entries[0].balanceAfter", is(900.0)))
                .andExpect(jsonPath("$.totalCredits", is(200.0)))
                .andExpect(jsonPath("$.totalDebits", is(0.0)))
                .andExpect(jsonPath("$.closingBalance", is(900.0)));
    }

    @Test
    @DisplayName("14. Opening balance does not use accounts.balance")
    void test14_openingBalanceDoesNotUseAccountsBalance() throws Exception {
        Instant t1 = Instant.parse("2026-09-24T08:00:00Z");
        Instant from = Instant.parse("2026-09-24T09:00:00Z");
        insertTransactionAndLedgerEntries(UUID.randomUUID(), null, null, "nob-1", clearingAccount, aliceAccount, new BigDecimal("500.0000"), "USD", TransactionType.DEPOSIT, TransactionStatus.COMPLETED, null, "Dep", t1, t1);

        jdbcTemplate.update("UPDATE accounts SET balance = 99999.0000 WHERE id = ?", aliceAccount.getId());

        mockMvc.perform(get("/api/v1/accounts/{accountId}/statement", aliceAccount.getId())
                        .param("from", from.toString())
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openingBalance", is(500.0)))
                .andExpect(jsonPath("$.closingBalance", is(500.0)));
    }

    @Test
    @DisplayName("15. Statement range starting after multiple transactions is correct")
    void test15_rangeStartingAfterMultipleTransactions() throws Exception {
        Instant t1 = Instant.parse("2026-09-24T07:00:00Z");
        Instant t2 = Instant.parse("2026-09-24T08:00:00Z");
        Instant t3 = Instant.parse("2026-09-24T09:00:00Z");
        Instant t4 = Instant.parse("2026-09-24T10:00:00Z");
        Instant from = Instant.parse("2026-09-24T08:30:00Z");

        insertTransactionAndLedgerEntries(UUID.randomUUID(), null, null, "r-1", clearingAccount, aliceAccount, new BigDecimal("100.0000"), "USD", TransactionType.DEPOSIT, TransactionStatus.COMPLETED, null, "1", t1, t1);
        insertTransactionAndLedgerEntries(UUID.randomUUID(), null, null, "r-2", clearingAccount, aliceAccount, new BigDecimal("200.0000"), "USD", TransactionType.DEPOSIT, TransactionStatus.COMPLETED, null, "2", t2, t2);
        insertTransactionAndLedgerEntries(UUID.randomUUID(), null, null, "r-3", aliceAccount, bobAccount, new BigDecimal("50.0000"), "USD", TransactionType.TRANSFER, TransactionStatus.COMPLETED, aliceUser, "3", t3, t3);
        insertTransactionAndLedgerEntries(UUID.randomUUID(), null, null, "r-4", clearingAccount, aliceAccount, new BigDecimal("150.0000"), "USD", TransactionType.DEPOSIT, TransactionStatus.COMPLETED, null, "4", t4, t4);

        mockMvc.perform(get("/api/v1/accounts/{accountId}/statement", aliceAccount.getId())
                        .param("from", from.toString())
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openingBalance", is(300.0)))
                .andExpect(jsonPath("$.entries", hasSize(2)))
                .andExpect(jsonPath("$.entries[0].balanceAfter", is(250.0)))
                .andExpect(jsonPath("$.entries[1].balanceAfter", is(400.0)))
                .andExpect(jsonPath("$.closingBalance", is(400.0)));
    }

    @Test
    @DisplayName("16. from is inclusive")
    void test16_fromIsInclusive() throws Exception {
        Instant t1 = Instant.parse("2026-09-24T10:00:00Z");
        insertTransactionAndLedgerEntries(UUID.randomUUID(), null, null, "inc-1", clearingAccount, aliceAccount, new BigDecimal("100.0000"), "USD", TransactionType.DEPOSIT, TransactionStatus.COMPLETED, null, "At boundary", t1, t1);

        mockMvc.perform(get("/api/v1/accounts/{accountId}/statement", aliceAccount.getId())
                        .param("from", t1.toString())
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openingBalance", is(0.0)))
                .andExpect(jsonPath("$.entries", hasSize(1)))
                .andExpect(jsonPath("$.entries[0].balanceAfter", is(100.0)));
    }

    @Test
    @DisplayName("17. to is exclusive")
    void test17_toIsExclusive() throws Exception {
        Instant t1 = Instant.parse("2026-09-24T10:00:00Z");
        insertTransactionAndLedgerEntries(UUID.randomUUID(), null, null, "exc-1", clearingAccount, aliceAccount, new BigDecimal("100.0000"), "USD", TransactionType.DEPOSIT, TransactionStatus.COMPLETED, null, "At to boundary", t1, t1);

        mockMvc.perform(get("/api/v1/accounts/{accountId}/statement", aliceAccount.getId())
                        .param("to", t1.toString())
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries", hasSize(0)));
    }

    @Test
    @DisplayName("18. from + to works")
    void test18_fromAndToWorks() throws Exception {
        Instant t1 = Instant.parse("2026-09-24T08:00:00Z");
        Instant t2 = Instant.parse("2026-09-24T09:00:00Z");
        Instant t3 = Instant.parse("2026-09-24T10:00:00Z");

        insertTransactionAndLedgerEntries(UUID.randomUUID(), null, null, "ft-1", clearingAccount, aliceAccount, new BigDecimal("100.0000"), "USD", TransactionType.DEPOSIT, TransactionStatus.COMPLETED, null, "1", t1, t1);
        insertTransactionAndLedgerEntries(UUID.randomUUID(), null, null, "ft-2", clearingAccount, aliceAccount, new BigDecimal("200.0000"), "USD", TransactionType.DEPOSIT, TransactionStatus.COMPLETED, null, "2", t2, t2);
        insertTransactionAndLedgerEntries(UUID.randomUUID(), null, null, "ft-3", clearingAccount, aliceAccount, new BigDecimal("300.0000"), "USD", TransactionType.DEPOSIT, TransactionStatus.COMPLETED, null, "3", t3, t3);

        mockMvc.perform(get("/api/v1/accounts/{accountId}/statement", aliceAccount.getId())
                        .param("from", t2.toString())
                        .param("to", t3.toString())
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openingBalance", is(100.0)))
                .andExpect(jsonPath("$.entries", hasSize(1)))
                .andExpect(jsonPath("$.entries[0].balanceAfter", is(300.0)))
                .andExpect(jsonPath("$.closingBalance", is(300.0)));
    }

    @Test
    @DisplayName("19. from > to returns 400 Bad Request")
    void test19_fromGreaterThanToReturns400() throws Exception {
        Instant later = Instant.parse("2026-09-24T12:00:00Z");
        Instant earlier = Instant.parse("2026-09-24T10:00:00Z");

        mockMvc.perform(get("/api/v1/accounts/{accountId}/statement", aliceAccount.getId())
                        .param("from", later.toString())
                        .param("to", earlier.toString())
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.message", containsString("must be before or equal to")));
    }

    @Test
    @DisplayName("20. from == to returns empty statement with zero activity")
    void test20_fromEqualToToReturnsEmptyStatement() throws Exception {
        Instant t = Instant.parse("2026-09-24T10:00:00Z");
        insertTransactionAndLedgerEntries(UUID.randomUUID(), null, null, "eq-1", clearingAccount, aliceAccount, new BigDecimal("100.0000"), "USD", TransactionType.DEPOSIT, TransactionStatus.COMPLETED, null, "Earlier", t.minusSeconds(3600), t.minusSeconds(3600));

        mockMvc.perform(get("/api/v1/accounts/{accountId}/statement", aliceAccount.getId())
                        .param("from", t.toString())
                        .param("to", t.toString())
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openingBalance", is(100.0)))
                .andExpect(jsonPath("$.closingBalance", is(100.0)))
                .andExpect(jsonPath("$.totalCredits", is(0.0)))
                .andExpect(jsonPath("$.totalDebits", is(0.0)))
                .andExpect(jsonPath("$.entries", hasSize(0)))
                .andExpect(jsonPath("$.totalElements", is(0)));
    }

    @Test
    @DisplayName("21. Invalid timestamp returns 400 Bad Request")
    void test21_invalidTimestampReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/{accountId}/statement", aliceAccount.getId())
                        .param("from", "invalid-timestamp")
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)));

        mockMvc.perform(get("/api/v1/accounts/{accountId}/statement", aliceAccount.getId())
                        .param("to", "invalid-to-timestamp")
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)));
    }

    @Test
    @DisplayName("22. transactionType=TRANSFER filter: entries filtered, true balanceAfter and true closingBalance")
    void test22_transactionTypeTransferFilter() throws Exception {
        Instant t1 = Instant.parse("2026-09-24T08:00:00Z");
        Instant t2 = Instant.parse("2026-09-24T09:00:00Z");

        insertTransactionAndLedgerEntries(UUID.randomUUID(), null, null, "flt-dep", clearingAccount, aliceAccount, new BigDecimal("500.0000"), "USD", TransactionType.DEPOSIT, TransactionStatus.COMPLETED, null, "Dep", t1, t1);
        insertTransactionAndLedgerEntries(UUID.randomUUID(), null, null, "flt-tx", aliceAccount, bobAccount, new BigDecimal("100.0000"), "USD", TransactionType.TRANSFER, TransactionStatus.COMPLETED, aliceUser, "Tx", t2, t2);

        mockMvc.perform(get("/api/v1/accounts/{accountId}/statement", aliceAccount.getId())
                        .param("transactionType", "TRANSFER")
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries", hasSize(1)))
                .andExpect(jsonPath("$.entries[0].transactionType", is("TRANSFER")))
                .andExpect(jsonPath("$.entries[0].balanceAfter", is(400.0)))
                .andExpect(jsonPath("$.totalCredits", is(0.0)))
                .andExpect(jsonPath("$.totalDebits", is(100.0)))
                .andExpect(jsonPath("$.closingBalance", is(400.0)));
    }

    @Test
    @DisplayName("23. transactionType=DEPOSIT filter: entries filtered, true balanceAfter and true closingBalance")
    void test23_transactionTypeDepositFilter() throws Exception {
        Instant t1 = Instant.parse("2026-09-24T08:00:00Z");
        Instant t2 = Instant.parse("2026-09-24T09:00:00Z");

        insertTransactionAndLedgerEntries(UUID.randomUUID(), null, null, "flt-dep-2", clearingAccount, aliceAccount, new BigDecimal("500.0000"), "USD", TransactionType.DEPOSIT, TransactionStatus.COMPLETED, null, "Dep", t1, t1);
        insertTransactionAndLedgerEntries(UUID.randomUUID(), null, null, "flt-tx-2", aliceAccount, bobAccount, new BigDecimal("100.0000"), "USD", TransactionType.TRANSFER, TransactionStatus.COMPLETED, aliceUser, "Tx", t2, t2);

        mockMvc.perform(get("/api/v1/accounts/{accountId}/statement", aliceAccount.getId())
                        .param("transactionType", "DEPOSIT")
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries", hasSize(1)))
                .andExpect(jsonPath("$.entries[0].transactionType", is("DEPOSIT")))
                .andExpect(jsonPath("$.entries[0].balanceAfter", is(500.0)))
                .andExpect(jsonPath("$.totalCredits", is(500.0)))
                .andExpect(jsonPath("$.totalDebits", is(0.0)))
                .andExpect(jsonPath("$.closingBalance", is(400.0)));
    }

    @Test
    @DisplayName("24. status=COMPLETED filter works")
    void test24_statusCompletedFilter() throws Exception {
        Instant t1 = Instant.parse("2026-09-24T08:00:00Z");
        Instant t2 = Instant.parse("2026-09-24T09:00:00Z");

        insertTransactionAndLedgerEntries(UUID.randomUUID(), null, null, "st-comp-1", clearingAccount, aliceAccount, new BigDecimal("500.0000"), "USD", TransactionType.DEPOSIT, TransactionStatus.COMPLETED, null, "Dep", t1, t1);
        insertTransactionAndLedgerEntries(UUID.randomUUID(), null, null, "st-fail-1", aliceAccount, bobAccount, new BigDecimal("100.0000"), "USD", TransactionType.TRANSFER, TransactionStatus.FAILED, aliceUser, "Tx", t2, t2);

        mockMvc.perform(get("/api/v1/accounts/{accountId}/statement", aliceAccount.getId())
                        .param("status", "COMPLETED")
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries", hasSize(1)))
                .andExpect(jsonPath("$.entries[0].status", is("COMPLETED")));
    }

    @Test
    @DisplayName("25. Invalid transactionType returns 400 Bad Request")
    void test25_invalidTransactionTypeReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/{accountId}/statement", aliceAccount.getId())
                        .param("transactionType", "INVALID_TYPE")
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)));
    }

    @Test
    @DisplayName("26. Invalid status returns 400 Bad Request")
    void test26_invalidStatusReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/{accountId}/statement", aliceAccount.getId())
                        .param("status", "INVALID_STATUS")
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)));
    }

    @Test
    @DisplayName("27. Pagination works")
    void test27_paginationWorks() throws Exception {
        Instant base = Instant.parse("2026-09-24T10:00:00Z");
        for (int i = 0; i < 5; i++) {
            insertTransactionAndLedgerEntries(
                    UUID.randomUUID(), null, null, "p-tx-" + i,
                    clearingAccount, aliceAccount, new BigDecimal("10.0000"), "USD",
                    TransactionType.DEPOSIT, TransactionStatus.COMPLETED, null, "P" + i,
                    base.plusSeconds(i * 60), base.plusSeconds(i * 60)
            );
        }

        mockMvc.perform(get("/api/v1/accounts/{accountId}/statement", aliceAccount.getId())
                        .param("page", "0")
                        .param("size", "2")
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries", hasSize(2)))
                .andExpect(jsonPath("$.page", is(0)))
                .andExpect(jsonPath("$.size", is(2)))
                .andExpect(jsonPath("$.totalElements", is(5)))
                .andExpect(jsonPath("$.totalPages", is(3)));

        mockMvc.perform(get("/api/v1/accounts/{accountId}/statement", aliceAccount.getId())
                        .param("page", "1")
                        .param("size", "2")
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries", hasSize(2)))
                .andExpect(jsonPath("$.page", is(1)));
    }

    @Test
    @DisplayName("28. Page metadata is correct")
    void test28_pageMetadataCorrect() throws Exception {
        Instant base = Instant.parse("2026-09-24T10:00:00Z");
        for (int i = 0; i < 5; i++) {
            insertTransactionAndLedgerEntries(
                    UUID.randomUUID(), null, null, "pm-tx-" + i,
                    clearingAccount, aliceAccount, new BigDecimal("10.0000"), "USD",
                    TransactionType.DEPOSIT, TransactionStatus.COMPLETED, null, "P" + i,
                    base.plusSeconds(i * 60), base.plusSeconds(i * 60)
            );
        }

        mockMvc.perform(get("/api/v1/accounts/{accountId}/statement", aliceAccount.getId())
                        .param("page", "2")
                        .param("size", "2")
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries", hasSize(1)))
                .andExpect(jsonPath("$.first", is(false)))
                .andExpect(jsonPath("$.last", is(true)));
    }

    @Test
    @DisplayName("29. Size defaults to 20")
    void test29_sizeDefaultsTo20() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/{accountId}/statement", aliceAccount.getId())
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size", is(20)));
    }

    @Test
    @DisplayName("30. Size > 100 rejected")
    void test30_sizeOver100Rejected() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/{accountId}/statement", aliceAccount.getId())
                        .param("size", "101")
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)));
    }

    @Test
    @DisplayName("31. Page < 0 rejected")
    void test31_negativePageRejected() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/{accountId}/statement", aliceAccount.getId())
                        .param("page", "-1")
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)));
    }

    @Test
    @DisplayName("32. Running balance remains correct across page boundaries")
    void test32_runningBalanceCorrectAcrossPageBoundaries() throws Exception {
        Instant base = Instant.parse("2026-09-24T10:00:00Z");
        insertTransactionAndLedgerEntries(UUID.randomUUID(), null, null, "bnd-1", clearingAccount, aliceAccount, new BigDecimal("100.0000"), "USD", TransactionType.DEPOSIT, TransactionStatus.COMPLETED, null, "1", base, base);
        insertTransactionAndLedgerEntries(UUID.randomUUID(), null, null, "bnd-2", clearingAccount, aliceAccount, new BigDecimal("50.0000"), "USD", TransactionType.DEPOSIT, TransactionStatus.COMPLETED, null, "2", base.plusSeconds(10), base.plusSeconds(10));
        insertTransactionAndLedgerEntries(UUID.randomUUID(), null, null, "bnd-3", aliceAccount, bobAccount, new BigDecimal("30.0000"), "USD", TransactionType.TRANSFER, TransactionStatus.COMPLETED, aliceUser, "3", base.plusSeconds(20), base.plusSeconds(20));
        insertTransactionAndLedgerEntries(UUID.randomUUID(), null, null, "bnd-4", aliceAccount, bobAccount, new BigDecimal("20.0000"), "USD", TransactionType.TRANSFER, TransactionStatus.COMPLETED, aliceUser, "4", base.plusSeconds(30), base.plusSeconds(30));

        mockMvc.perform(get("/api/v1/accounts/{accountId}/statement", aliceAccount.getId())
                        .param("page", "0")
                        .param("size", "2")
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].balanceAfter", is(100.0)))
                .andExpect(jsonPath("$.entries[1].balanceAfter", is(150.0)));

        mockMvc.perform(get("/api/v1/accounts/{accountId}/statement", aliceAccount.getId())
                        .param("page", "1")
                        .param("size", "2")
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].balanceAfter", is(120.0)))
                .andExpect(jsonPath("$.entries[1].balanceAfter", is(100.0)))
                .andExpect(jsonPath("$.closingBalance", is(100.0)));
    }

    @Test
    @DisplayName("33. totalCredits correct")
    void test33_totalCreditsCorrect() throws Exception {
        Instant t = Instant.parse("2026-09-24T10:00:00Z");
        insertTransactionAndLedgerEntries(UUID.randomUUID(), null, null, "tc-1", clearingAccount, aliceAccount, new BigDecimal("250.0000"), "USD", TransactionType.DEPOSIT, TransactionStatus.COMPLETED, null, "1", t, t);
        insertTransactionAndLedgerEntries(UUID.randomUUID(), null, null, "tc-2", clearingAccount, aliceAccount, new BigDecimal("150.0000"), "USD", TransactionType.DEPOSIT, TransactionStatus.COMPLETED, null, "2", t.plusSeconds(1), t.plusSeconds(1));

        mockMvc.perform(get("/api/v1/accounts/{accountId}/statement", aliceAccount.getId())
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCredits", is(400.0)));
    }

    @Test
    @DisplayName("34. totalDebits correct")
    void test34_totalDebitsCorrect() throws Exception {
        Instant t = Instant.parse("2026-09-24T10:00:00Z");
        insertTransactionAndLedgerEntries(UUID.randomUUID(), null, null, "td-1", aliceAccount, bobAccount, new BigDecimal("75.0000"), "USD", TransactionType.TRANSFER, TransactionStatus.COMPLETED, aliceUser, "1", t, t);
        insertTransactionAndLedgerEntries(UUID.randomUUID(), null, null, "td-2", aliceAccount, bobAccount, new BigDecimal("25.0000"), "USD", TransactionType.TRANSFER, TransactionStatus.COMPLETED, aliceUser, "2", t.plusSeconds(1), t.plusSeconds(1));

        mockMvc.perform(get("/api/v1/accounts/{accountId}/statement", aliceAccount.getId())
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalDebits", is(100.0)));
    }

    @Test
    @DisplayName("35. Closing balance matches actual ledger balance before 'to'")
    void test35_closingBalanceMatchesActualLedgerBalance() throws Exception {
        Instant t1 = Instant.parse("2026-09-24T08:00:00Z");
        Instant t2 = Instant.parse("2026-09-24T09:00:00Z");
        Instant t3 = Instant.parse("2026-09-24T10:00:00Z");
        Instant from = Instant.parse("2026-09-24T08:30:00Z");

        insertTransactionAndLedgerEntries(UUID.randomUUID(), null, null, "inv-1", clearingAccount, aliceAccount, new BigDecimal("1000.0000"), "USD", TransactionType.DEPOSIT, TransactionStatus.COMPLETED, null, "1", t1, t1);
        insertTransactionAndLedgerEntries(UUID.randomUUID(), null, null, "inv-2", clearingAccount, aliceAccount, new BigDecimal("400.0000"), "USD", TransactionType.DEPOSIT, TransactionStatus.COMPLETED, null, "2", t2, t2);
        insertTransactionAndLedgerEntries(UUID.randomUUID(), null, null, "inv-3", aliceAccount, bobAccount, new BigDecimal("150.0000"), "USD", TransactionType.TRANSFER, TransactionStatus.COMPLETED, aliceUser, "3", t3, t3);

        MvcResult result = mockMvc.perform(get("/api/v1/accounts/{accountId}/statement", aliceAccount.getId())
                        .param("from", from.toString())
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isOk())
                .andReturn();

        AccountStatementResponseDto dto = objectMapper.readValue(result.getResponse().getContentAsString(), AccountStatementResponseDto.class);
        assertThat(dto.closingBalance()).isEqualByComparingTo(new BigDecimal("1250.0000"));
        assertThat(dto.closingBalance()).isEqualByComparingTo(dto.openingBalance().add(dto.totalCredits()).subtract(dto.totalDebits()));
    }

    @Test
    @DisplayName("36. Another user's account returns 404 Not Found")
    void test36_anotherUsersAccountReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/{accountId}/statement", bobAccount.getId())
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status", is(404)));
    }

    @Test
    @DisplayName("37. SYSTEM_CLEARING returns 404 Not Found")
    void test37_systemClearingReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/{accountId}/statement", DepositService.SYSTEM_CLEARING_ACCOUNT_ID)
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status", is(404)));
    }

    @Test
    @DisplayName("38. Nonexistent account returns 404 Not Found")
    void test38_nonexistentAccountReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/{accountId}/statement", UUID.randomUUID())
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status", is(404)));
    }

    @Test
    @DisplayName("39. Unauthenticated request returns 401 Unauthorized")
    void test39_unauthenticatedReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/{accountId}/statement", aliceAccount.getId()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("40. Malformed UUID returns 400 Bad Request")
    void test40_malformedUuidReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/{accountId}/statement", "not-a-valid-uuid")
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)));
    }

    @Test
    @DisplayName("41. Statement request does not mutate account balance")
    void test41_statementRequestDoesNotMutateBalance() throws Exception {
        BigDecimal balanceBefore = accountRepository.findById(aliceAccount.getId()).orElseThrow().getBalance();

        mockMvc.perform(get("/api/v1/accounts/{accountId}/statement", aliceAccount.getId())
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isOk());

        BigDecimal balanceAfter = accountRepository.findById(aliceAccount.getId()).orElseThrow().getBalance();
        assertThat(balanceAfter).isEqualByComparingTo(balanceBefore);
    }

    @Test
    @DisplayName("42. Statement request does not create transactions")
    void test42_statementRequestDoesNotCreateTransactions() throws Exception {
        long countBefore = transactionRepository.count();

        mockMvc.perform(get("/api/v1/accounts/{accountId}/statement", aliceAccount.getId())
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isOk());

        long countAfter = transactionRepository.count();
        assertThat(countAfter).isEqualTo(countBefore);
    }

    @Test
    @DisplayName("43. Statement request does not modify ledger entries")
    void test43_statementRequestDoesNotModifyLedgerEntries() throws Exception {
        long countBefore = ledgerEntryRepository.count();

        mockMvc.perform(get("/api/v1/accounts/{accountId}/statement", aliceAccount.getId())
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isOk());

        long countAfter = ledgerEntryRepository.count();
        assertThat(countAfter).isEqualTo(countBefore);
    }

    @Test
    @DisplayName("44. Statement still works with PostgreSQL ledger immutability trigger enabled")
    void test44_statementWorksWithTriggerEnabled() throws Exception {
        Integer triggerCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_trigger WHERE tgname = 'trg_ledger_entries_immutable'",
                Integer.class
        );
        assertThat(triggerCount).isNotNull().isGreaterThan(0);

        mockMvc.perform(get("/api/v1/accounts/{accountId}/statement", aliceAccount.getId())
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("45. Calculated full-history closing balance matches reconciled account snapshot for consistent accounts")
    void test45_closingBalanceMatchesReconciliation() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(aliceUser.getEmail(), null, Collections.emptyList())
        );
        depositService.executeDeposit("e2e-dep-1", new DepositRequestDto(aliceAccount.getId(), new BigDecimal("1000.00"), "USD", "Dep"));
        transferService.executeTransfer("e2e-tx-1", new TransferRequestDto(aliceAccount.getId(), bobAccount.getId(), new BigDecimal("350.00"), "USD"));

        ReconciliationResultDto recon = reconciliationService.reconcileAccount(aliceAccount.getId());
        assertThat(recon.status()).isEqualTo(ReconciliationStatus.CONSISTENT);

        MvcResult result = mockMvc.perform(get("/api/v1/accounts/{accountId}/statement", aliceAccount.getId())
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isOk())
                .andReturn();

        AccountStatementResponseDto dto = objectMapper.readValue(result.getResponse().getContentAsString(), AccountStatementResponseDto.class);
        assertThat(dto.closingBalance()).isEqualByComparingTo(recon.ledgerBalance());
        assertThat(dto.closingBalance()).isEqualByComparingTo(recon.snapshotBalance());
    }

    @Test
    @DisplayName("46. Statement does not mutate or repair reconciliation discrepancies")
    void test46_statementDoesNotRepairDiscrepancies() throws Exception {
        Instant t = Instant.parse("2026-09-24T10:00:00Z");
        insertTransactionAndLedgerEntries(UUID.randomUUID(), null, null, "disc-1", clearingAccount, aliceAccount, new BigDecimal("500.0000"), "USD", TransactionType.DEPOSIT, TransactionStatus.COMPLETED, null, "Dep", t, t);

        jdbcTemplate.update("UPDATE accounts SET balance = 8888.0000 WHERE id = ?", aliceAccount.getId());

        mockMvc.perform(get("/api/v1/accounts/{accountId}/statement", aliceAccount.getId())
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closingBalance", is(500.0)));

        BigDecimal snapshotAfter = accountRepository.findById(aliceAccount.getId()).orElseThrow().getBalance();
        assertThat(snapshotAfter).isEqualByComparingTo(new BigDecimal("8888.0000"));
    }

    @Test
    @DisplayName("47. Hidden-entry running-balance: accounts for intermediate hidden deposit")
    void test47_hiddenEntryRunningBalanceAccountsForIntermediateHiddenDeposit() throws Exception {
        Instant t1 = Instant.parse("2026-09-24T08:00:00Z");
        Instant t2 = Instant.parse("2026-09-24T09:00:00Z");
        Instant from = Instant.parse("2026-09-24T09:30:00Z");
        Instant t3 = Instant.parse("2026-09-24T10:00:00Z");
        Instant t4 = Instant.parse("2026-09-24T11:00:00Z");
        Instant to = Instant.parse("2026-09-24T11:30:00Z");

        insertTransactionAndLedgerEntries(UUID.randomUUID(), null, null, "h-dep-1", clearingAccount, aliceAccount, new BigDecimal("1000.0000"), "USD", TransactionType.DEPOSIT, TransactionStatus.COMPLETED, null, "D1", t1, t1);
        insertTransactionAndLedgerEntries(UUID.randomUUID(), null, null, "h-tx-1", aliceAccount, bobAccount, new BigDecimal("200.0000"), "USD", TransactionType.TRANSFER, TransactionStatus.COMPLETED, aliceUser, "T1", t2, t2);
        insertTransactionAndLedgerEntries(UUID.randomUUID(), null, null, "h-dep-2", clearingAccount, aliceAccount, new BigDecimal("500.0000"), "USD", TransactionType.DEPOSIT, TransactionStatus.COMPLETED, null, "D2", t3, t3);
        insertTransactionAndLedgerEntries(UUID.randomUUID(), null, null, "h-tx-2", aliceAccount, bobAccount, new BigDecimal("100.0000"), "USD", TransactionType.TRANSFER, TransactionStatus.COMPLETED, aliceUser, "T2", t4, t4);

        mockMvc.perform(get("/api/v1/accounts/{accountId}/statement", aliceAccount.getId())
                        .param("from", from.toString())
                        .param("to", to.toString())
                        .param("transactionType", "TRANSFER")
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openingBalance", is(800.0)))
                .andExpect(jsonPath("$.entries", hasSize(1)))
                .andExpect(jsonPath("$.entries[0].amount", is(100.0)))
                .andExpect(jsonPath("$.entries[0].direction", is("DEBIT")))
                .andExpect(jsonPath("$.entries[0].balanceAfter", is(1200.0)))
                .andExpect(jsonPath("$.totalCredits", is(0.0)))
                .andExpect(jsonPath("$.totalDebits", is(100.0)))
                .andExpect(jsonPath("$.closingBalance", is(1200.0)));
    }

    @Test
    @DisplayName("48. Full-history transactionType=TRANSFER retains true non-negative balances")
    void test48_fullHistoryTransferRetainsTrueBalances() throws Exception {
        Instant t1 = Instant.parse("2026-09-24T08:00:00Z");
        Instant t2 = Instant.parse("2026-09-24T09:00:00Z");
        Instant t3 = Instant.parse("2026-09-24T10:00:00Z");
        Instant t4 = Instant.parse("2026-09-24T11:00:00Z");
        Instant t5 = Instant.parse("2026-09-24T12:00:00Z");

        insertTransactionAndLedgerEntries(UUID.randomUUID(), null, null, "fh-d1", clearingAccount, aliceAccount, new BigDecimal("1000.0000"), "USD", TransactionType.DEPOSIT, TransactionStatus.COMPLETED, null, "D1", t1, t1);
        insertTransactionAndLedgerEntries(UUID.randomUUID(), null, null, "fh-t1", aliceAccount, bobAccount, new BigDecimal("200.0000"), "USD", TransactionType.TRANSFER, TransactionStatus.COMPLETED, aliceUser, "T1", t2, t2);
        insertTransactionAndLedgerEntries(UUID.randomUUID(), null, null, "fh-d2", clearingAccount, aliceAccount, new BigDecimal("500.0000"), "USD", TransactionType.DEPOSIT, TransactionStatus.COMPLETED, null, "D2", t3, t3);
        insertTransactionAndLedgerEntries(UUID.randomUUID(), null, null, "fh-t2", aliceAccount, bobAccount, new BigDecimal("100.0000"), "USD", TransactionType.TRANSFER, TransactionStatus.COMPLETED, aliceUser, "T2", t4, t4);
        insertTransactionAndLedgerEntries(UUID.randomUUID(), null, null, "fh-t3", bobAccount, aliceAccount, new BigDecimal("300.0000"), "USD", TransactionType.TRANSFER, TransactionStatus.COMPLETED, bobUser, "T3", t5, t5);

        mockMvc.perform(get("/api/v1/accounts/{accountId}/statement", aliceAccount.getId())
                        .param("transactionType", "TRANSFER")
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openingBalance", is(0.0)))
                .andExpect(jsonPath("$.entries", hasSize(3)))
                .andExpect(jsonPath("$.entries[0].balanceAfter", is(800.0)))
                .andExpect(jsonPath("$.entries[1].balanceAfter", is(1200.0)))
                .andExpect(jsonPath("$.entries[2].balanceAfter", is(1500.0)))
                .andExpect(jsonPath("$.totalCredits", is(300.0)))
                .andExpect(jsonPath("$.totalDebits", is(300.0)))
                .andExpect(jsonPath("$.closingBalance", is(1500.0)));
    }

    @Test
    @DisplayName("49. Cross-page hidden activity: page 1 accounts for hidden transactions between pages")
    void test49_crossPageHiddenActivity() throws Exception {
        Instant base = Instant.parse("2026-09-24T10:00:00Z");

        insertTransactionAndLedgerEntries(UUID.randomUUID(), null, null, "cp-t1", aliceAccount, bobAccount, new BigDecimal("10.0000"), "USD", TransactionType.TRANSFER, TransactionStatus.COMPLETED, aliceUser, "T1", base, base);
        insertTransactionAndLedgerEntries(UUID.randomUUID(), null, null, "cp-t2", aliceAccount, bobAccount, new BigDecimal("20.0000"), "USD", TransactionType.TRANSFER, TransactionStatus.COMPLETED, aliceUser, "T2", base.plusSeconds(10), base.plusSeconds(10));
        insertTransactionAndLedgerEntries(UUID.randomUUID(), null, null, "cp-hidden-dep", clearingAccount, aliceAccount, new BigDecimal("1000.0000"), "USD", TransactionType.DEPOSIT, TransactionStatus.COMPLETED, null, "Hidden Deposit", base.plusSeconds(15), base.plusSeconds(15));
        insertTransactionAndLedgerEntries(UUID.randomUUID(), null, null, "cp-t3", aliceAccount, bobAccount, new BigDecimal("30.0000"), "USD", TransactionType.TRANSFER, TransactionStatus.COMPLETED, aliceUser, "T3", base.plusSeconds(20), base.plusSeconds(20));

        mockMvc.perform(get("/api/v1/accounts/{accountId}/statement", aliceAccount.getId())
                        .param("transactionType", "TRANSFER")
                        .param("page", "1")
                        .param("size", "2")
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries", hasSize(1)))
                .andExpect(jsonPath("$.entries[0].amount", is(30.0)))
                .andExpect(jsonPath("$.entries[0].balanceAfter", is(940.0)))
                .andExpect(jsonPath("$.closingBalance", is(940.0)));
    }
}
