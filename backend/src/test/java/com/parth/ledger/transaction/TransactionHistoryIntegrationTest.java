package com.parth.ledger.transaction;

import com.parth.ledger.BaseIntegrationTest;
import com.parth.ledger.account.Account;
import com.parth.ledger.account.AccountRepository;
import com.parth.ledger.account.AccountStatus;
import com.parth.ledger.account.AccountType;
import com.parth.ledger.deposit.dto.DepositRequestDto;
import com.parth.ledger.deposit.service.DepositService;
import com.parth.ledger.ledger.LedgerEntry;
import com.parth.ledger.ledger.LedgerEntryRepository;
import com.parth.ledger.transaction.dto.TransactionDirection;
import com.parth.ledger.transaction.dto.TransactionHistoryItemDto;
import com.parth.ledger.transaction.dto.TransactionHistoryPageResponseDto;
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
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@DisplayName("V7 Transaction History API Integration Tests")
class TransactionHistoryIntegrationTest extends BaseIntegrationTest {

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

    private User aliceUser;
    private User bobUser;
    private User charlieUser;
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

        aliceUser = userRepository.save(new User("alice.v7@ledger.com", "Alice V7"));
        bobUser = userRepository.save(new User("bob.v7@ledger.com", "Bob V7"));
        charlieUser = userRepository.save(new User("charlie.v7@ledger.com", "Charlie V7"));

        aliceAccount = accountRepository.save(new Account(
                aliceUser,
                "USD",
                new BigDecimal("5000.0000"),
                AccountType.USER_CHECKING,
                AccountStatus.ACTIVE,
                "ACCT-ALICE-V7"
        ));

        bobAccount = accountRepository.save(new Account(
                bobUser,
                "USD",
                new BigDecimal("2000.0000"),
                AccountType.USER_CHECKING,
                AccountStatus.ACTIVE,
                "ACCT-BOB-V7"
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

    private Transaction insertTransaction(
            UUID id,
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
                id,
                idempotencyKey,
                amount,
                currency,
                status.name(),
                type.name(),
                initiatedBy != null ? initiatedBy.getId() : null,
                description,
                source.getId(),
                dest.getId(),
                Timestamp.from(createdAt),
                completedAt != null ? Timestamp.from(completedAt) : null
        );
        return transactionRepository.findById(id).orElseThrow();
    }

    // =========================================================================
    // 1. BASIC RETRIEVAL TESTS (Tests 1–8)
    // =========================================================================

    @Test
    @DisplayName("1. Authenticated user retrieves transaction history for owned account")
    void test01_authenticatedUserRetrievesHistoryForOwnedAccount() throws Exception {
        Instant now = Instant.parse("2026-09-24T10:00:00Z");
        insertTransaction(
                UUID.randomUUID(),
                "tx-basic-001",
                clearingAccount,
                aliceAccount,
                new BigDecimal("500.0000"),
                "USD",
                TransactionType.DEPOSIT,
                TransactionStatus.COMPLETED,
                null,
                "Direct funding",
                now,
                now.plusSeconds(1)
        );

        mockMvc.perform(get("/api/v1/accounts/{accountId}/transactions", aliceAccount.getId())
                        .with(user(aliceUser.getEmail()))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].transactionType", is("DEPOSIT")))
                .andExpect(jsonPath("$.content[0].direction", is("CREDIT")))
                .andExpect(jsonPath("$.content[0].amount", is(500.0)))
                .andExpect(jsonPath("$.content[0].currency", is("USD")))
                .andExpect(jsonPath("$.content[0].description", is("Direct funding")))
                .andExpect(jsonPath("$.content[0].status", is("COMPLETED")))
                .andExpect(jsonPath("$.content[0].sourceAccountId", is(clearingAccount.getId().toString())))
                .andExpect(jsonPath("$.content[0].destinationAccountId", is(aliceAccount.getId().toString())))
                .andExpect(jsonPath("$.page", is(0)))
                .andExpect(jsonPath("$.size", is(20)))
                .andExpect(jsonPath("$.totalElements", is(1)))
                .andExpect(jsonPath("$.totalPages", is(1)))
                .andExpect(jsonPath("$.first", is(true)))
                .andExpect(jsonPath("$.last", is(true)));
    }

    @Test
    @DisplayName("2. Empty history returns empty content list with 200 OK")
    void test02_emptyHistoryReturnsEmptyContentWith200() throws Exception {
        Account freshAccount = accountRepository.save(new Account(
                aliceUser,
                "USD",
                BigDecimal.ZERO.setScale(4),
                AccountType.USER_CHECKING,
                AccountStatus.ACTIVE,
                "ACCT-ALICE-FRESH"
        ));

        mockMvc.perform(get("/api/v1/accounts/{accountId}/transactions", freshAccount.getId())
                        .with(user(aliceUser.getEmail()))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.page", is(0)))
                .andExpect(jsonPath("$.size", is(20)))
                .andExpect(jsonPath("$.totalElements", is(0)))
                .andExpect(jsonPath("$.totalPages", is(0)))
                .andExpect(jsonPath("$.first", is(true)))
                .andExpect(jsonPath("$.last", is(true)));
    }

    @Test
    @DisplayName("3. Newest transactions appear first (createdAt DESC)")
    void test03_newestTransactionsAppearFirst_createdAtDesc() throws Exception {
        Instant t1 = Instant.parse("2026-09-24T08:00:00Z");
        Instant t2 = Instant.parse("2026-09-24T09:00:00Z");
        Instant t3 = Instant.parse("2026-09-24T10:00:00Z");

        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        UUID id3 = UUID.randomUUID();

        insertTransaction(id1, "k1", aliceAccount, bobAccount, new BigDecimal("10.0000"), "USD", TransactionType.TRANSFER, TransactionStatus.COMPLETED, aliceUser, "T1", t1, t1);
        insertTransaction(id2, "k2", aliceAccount, bobAccount, new BigDecimal("20.0000"), "USD", TransactionType.TRANSFER, TransactionStatus.COMPLETED, aliceUser, "T2", t2, t2);
        insertTransaction(id3, "k3", aliceAccount, bobAccount, new BigDecimal("30.0000"), "USD", TransactionType.TRANSFER, TransactionStatus.COMPLETED, aliceUser, "T3", t3, t3);

        mockMvc.perform(get("/api/v1/accounts/{accountId}/transactions", aliceAccount.getId())
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(3)))
                .andExpect(jsonPath("$.content[0].transactionId", is(id3.toString())))
                .andExpect(jsonPath("$.content[1].transactionId", is(id2.toString())))
                .andExpect(jsonPath("$.content[2].transactionId", is(id1.toString())));
    }

    @Test
    @DisplayName("4. Deterministic tie-breaking for identical createdAt (id DESC)")
    void test04_deterministicTieBreakingForIdenticalCreatedAt_idDesc() throws Exception {
        Instant sameTimestamp = Instant.parse("2026-09-24T12:00:00Z");

        // High UUID vs Low UUID
        UUID lowId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID highId = UUID.fromString("00000000-0000-0000-0000-000000000002");

        insertTransaction(lowId, "tie-low", aliceAccount, bobAccount, new BigDecimal("10.0000"), "USD", TransactionType.TRANSFER, TransactionStatus.COMPLETED, aliceUser, "Low", sameTimestamp, sameTimestamp);
        insertTransaction(highId, "tie-high", aliceAccount, bobAccount, new BigDecimal("20.0000"), "USD", TransactionType.TRANSFER, TransactionStatus.COMPLETED, aliceUser, "High", sameTimestamp, sameTimestamp);

        mockMvc.perform(get("/api/v1/accounts/{accountId}/transactions", aliceAccount.getId())
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[0].transactionId", is(highId.toString())))
                .andExpect(jsonPath("$.content[1].transactionId", is(lowId.toString())));
    }

    @Test
    @DisplayName("5. Pagination works: page 0 vs page 1 returns distinct items")
    void test05_paginationWorks_page0VsPage1ReturnsDistinctItems() throws Exception {
        Instant baseTime = Instant.parse("2026-09-24T10:00:00Z");
        for (int i = 0; i < 5; i++) {
            insertTransaction(
                    UUID.randomUUID(),
                    "page-tx-" + i,
                    aliceAccount,
                    bobAccount,
                    new BigDecimal("10.0000"),
                    "USD",
                    TransactionType.TRANSFER,
                    TransactionStatus.COMPLETED,
                    aliceUser,
                    "Tx " + i,
                    baseTime.plusSeconds(i * 60),
                    baseTime.plusSeconds(i * 60)
            );
        }

        // Fetch page 0, size 2
        MvcResult resPage0 = mockMvc.perform(get("/api/v1/accounts/{accountId}/transactions", aliceAccount.getId())
                        .param("page", "0")
                        .param("size", "2")
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.page", is(0)))
                .andExpect(jsonPath("$.size", is(2)))
                .andExpect(jsonPath("$.totalElements", is(5)))
                .andExpect(jsonPath("$.totalPages", is(3)))
                .andExpect(jsonPath("$.first", is(true)))
                .andExpect(jsonPath("$.last", is(false)))
                .andReturn();

        // Fetch page 1, size 2
        MvcResult resPage1 = mockMvc.perform(get("/api/v1/accounts/{accountId}/transactions", aliceAccount.getId())
                        .param("page", "1")
                        .param("size", "2")
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.page", is(1)))
                .andExpect(jsonPath("$.size", is(2)))
                .andExpect(jsonPath("$.totalElements", is(5)))
                .andExpect(jsonPath("$.totalPages", is(3)))
                .andExpect(jsonPath("$.first", is(false)))
                .andExpect(jsonPath("$.last", is(false)))
                .andReturn();

        TransactionHistoryPageResponseDto p0 = objectMapper.readValue(
                resPage0.getResponse().getContentAsString(),
                TransactionHistoryPageResponseDto.class
        );
        TransactionHistoryPageResponseDto p1 = objectMapper.readValue(
                resPage1.getResponse().getContentAsString(),
                TransactionHistoryPageResponseDto.class
        );

        List<UUID> p0Ids = p0.content().stream().map(TransactionHistoryItemDto::transactionId).toList();
        List<UUID> p1Ids = p1.content().stream().map(TransactionHistoryItemDto::transactionId).toList();

        assertThat(p0Ids).doesNotContainAnyElementsOf(p1Ids);
    }

    @Test
    @DisplayName("6. Page metadata correct on last page")
    void test06_pageMetadataCorrectOnLastPage() throws Exception {
        Instant baseTime = Instant.parse("2026-09-24T10:00:00Z");
        for (int i = 0; i < 5; i++) {
            insertTransaction(
                    UUID.randomUUID(),
                    "meta-tx-" + i,
                    aliceAccount,
                    bobAccount,
                    new BigDecimal("10.0000"),
                    "USD",
                    TransactionType.TRANSFER,
                    TransactionStatus.COMPLETED,
                    aliceUser,
                    "Tx " + i,
                    baseTime.plusSeconds(i * 60),
                    baseTime.plusSeconds(i * 60)
            );
        }

        mockMvc.perform(get("/api/v1/accounts/{accountId}/transactions", aliceAccount.getId())
                        .param("page", "2")
                        .param("size", "2")
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.page", is(2)))
                .andExpect(jsonPath("$.size", is(2)))
                .andExpect(jsonPath("$.totalElements", is(5)))
                .andExpect(jsonPath("$.totalPages", is(3)))
                .andExpect(jsonPath("$.first", is(false)))
                .andExpect(jsonPath("$.last", is(true)));
    }

    @Test
    @DisplayName("7. Default size is 20")
    void test07_defaultSizeIs20() throws Exception {
        Instant baseTime = Instant.parse("2026-09-24T10:00:00Z");
        for (int i = 0; i < 25; i++) {
            insertTransaction(
                    UUID.randomUUID(),
                    "default-size-tx-" + i,
                    aliceAccount,
                    bobAccount,
                    new BigDecimal("1.0000"),
                    "USD",
                    TransactionType.TRANSFER,
                    TransactionStatus.COMPLETED,
                    aliceUser,
                    "Batch " + i,
                    baseTime.plusSeconds(i * 10),
                    baseTime.plusSeconds(i * 10)
            );
        }

        mockMvc.perform(get("/api/v1/accounts/{accountId}/transactions", aliceAccount.getId())
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(20)))
                .andExpect(jsonPath("$.page", is(0)))
                .andExpect(jsonPath("$.size", is(20)))
                .andExpect(jsonPath("$.totalElements", is(25)))
                .andExpect(jsonPath("$.totalPages", is(2)))
                .andExpect(jsonPath("$.first", is(true)))
                .andExpect(jsonPath("$.last", is(false)));
    }

    @Test
    @DisplayName("8. Max size 100 enforced: rejects size > 100 with 400 Bad Request, permits size = 100")
    void test08_maxSize100Enforced() throws Exception {
        // size = 101 should return 400 Bad Request
        mockMvc.perform(get("/api/v1/accounts/{accountId}/transactions", aliceAccount.getId())
                        .param("size", "101")
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("Page size must not exceed 100")));

        // size = 100 should return 200 OK
        mockMvc.perform(get("/api/v1/accounts/{accountId}/transactions", aliceAccount.getId())
                        .param("size", "100")
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size", is(100)));
    }

    @Test
    @DisplayName("8b. Page size < 1 returns 400 Bad Request")
    void test08b_sizeLessThan1_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/{accountId}/transactions", aliceAccount.getId())
                        .param("size", "0")
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("Page size must be at least 1")));
    }

    @Test
    @DisplayName("8c. Negative page returns 400 Bad Request")
    void test08c_negativePage_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/{accountId}/transactions", aliceAccount.getId())
                        .param("page", "-1")
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("Page index must not be negative")));
    }

    // =========================================================================
    // 2. OWNERSHIP / AUTHORIZATION TESTS (Tests 9–13)
    // =========================================================================

    @Test
    @DisplayName("9. Other user's transactions are not visible and querying other user's account returns 404")
    void test09_otherUsersTransactionsNotVisible() throws Exception {
        Instant now = Instant.parse("2026-09-24T10:00:00Z");
        Account charlieAccount = accountRepository.save(new Account(
                charlieUser,
                "USD",
                new BigDecimal("1000.0000"),
                AccountType.USER_CHECKING,
                AccountStatus.ACTIVE,
                "ACCT-CHARLIE-V7"
        ));

        // Transaction strictly between Bob and Charlie
        insertTransaction(
                UUID.randomUUID(),
                "bob-charlie-001",
                bobAccount,
                charlieAccount,
                new BigDecimal("50.0000"),
                "USD",
                TransactionType.TRANSFER,
                TransactionStatus.COMPLETED,
                bobUser,
                "Bob to Charlie",
                now,
                now
        );

        // Alice queries her own account: Bob's transaction is NOT visible
        mockMvc.perform(get("/api/v1/accounts/{accountId}/transactions", aliceAccount.getId())
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.totalElements", is(0)));

        // Alice attempts to query Bob's account directly: returns 404 (does NOT leak existence via 403)
        mockMvc.perform(get("/api/v1/accounts/{accountId}/transactions", bobAccount.getId())
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status", is(404)))
                .andExpect(jsonPath("$.error", is("Not Found")));
    }

    @Test
    @DisplayName("10. Nonexistent account returns 404 Not Found")
    void test10_nonexistentAccountReturns404() throws Exception {
        UUID nonexistentId = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/accounts/{accountId}/transactions", nonexistentId)
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status", is(404)))
                .andExpect(jsonPath("$.error", is("Not Found")));
    }

    @Test
    @DisplayName("11. SYSTEM_CLEARING account returns 404 Not Found (never exposed directly)")
    void test11_systemClearingAccountReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/{accountId}/transactions", DepositService.SYSTEM_CLEARING_ACCOUNT_ID)
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status", is(404)))
                .andExpect(jsonPath("$.error", is("Not Found")));
    }

    @Test
    @DisplayName("12. Unauthenticated request returns 401 Unauthorized")
    void test12_unauthenticatedRequestReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/{accountId}/transactions", aliceAccount.getId()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("13. Malformed UUID in path returns 400 Bad Request")
    void test13_malformedUuidReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/{accountId}/transactions", "not-a-valid-uuid")
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.error", is("Bad Request")));
    }

    // =========================================================================
    // 3. TRANSACTION DIRECTION TESTS (Tests 14–16)
    // =========================================================================

    @Test
    @DisplayName("14. Outgoing transfer shows direction DEBIT")
    void test14_outgoingTransfer_directionDebit() throws Exception {
        Instant now = Instant.parse("2026-09-24T10:00:00Z");
        insertTransaction(
                UUID.randomUUID(),
                "dir-out-001",
                aliceAccount,
                bobAccount,
                new BigDecimal("100.0000"),
                "USD",
                TransactionType.TRANSFER,
                TransactionStatus.COMPLETED,
                aliceUser,
                "Payment to Bob",
                now,
                now
        );

        mockMvc.perform(get("/api/v1/accounts/{accountId}/transactions", aliceAccount.getId())
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].direction", is("DEBIT")))
                .andExpect(jsonPath("$.content[0].sourceAccountId", is(aliceAccount.getId().toString())))
                .andExpect(jsonPath("$.content[0].destinationAccountId", is(bobAccount.getId().toString())));
    }

    @Test
    @DisplayName("15. Incoming transfer shows direction CREDIT")
    void test15_incomingTransfer_directionCredit() throws Exception {
        Instant now = Instant.parse("2026-09-24T10:00:00Z");
        insertTransaction(
                UUID.randomUUID(),
                "dir-in-001",
                bobAccount,
                aliceAccount,
                new BigDecimal("150.0000"),
                "USD",
                TransactionType.TRANSFER,
                TransactionStatus.COMPLETED,
                bobUser,
                "Repayment from Bob",
                now,
                now
        );

        mockMvc.perform(get("/api/v1/accounts/{accountId}/transactions", aliceAccount.getId())
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].direction", is("CREDIT")))
                .andExpect(jsonPath("$.content[0].sourceAccountId", is(bobAccount.getId().toString())))
                .andExpect(jsonPath("$.content[0].destinationAccountId", is(aliceAccount.getId().toString())));
    }

    @Test
    @DisplayName("16. Deposit shows direction CREDIT")
    void test16_deposit_directionCredit() throws Exception {
        Instant now = Instant.parse("2026-09-24T10:00:00Z");
        insertTransaction(
                UUID.randomUUID(),
                "dir-dep-001",
                clearingAccount,
                aliceAccount,
                new BigDecimal("500.0000"),
                "USD",
                TransactionType.DEPOSIT,
                TransactionStatus.COMPLETED,
                null,
                "Deposit from clearing",
                now,
                now
        );

        mockMvc.perform(get("/api/v1/accounts/{accountId}/transactions", aliceAccount.getId())
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].direction", is("CREDIT")))
                .andExpect(jsonPath("$.content[0].transactionType", is("DEPOSIT")))
                .andExpect(jsonPath("$.content[0].sourceAccountId", is(clearingAccount.getId().toString())))
                .andExpect(jsonPath("$.content[0].destinationAccountId", is(aliceAccount.getId().toString())));
    }

    // =========================================================================
    // 4. FILTERS TESTS (Tests 17–25)
    // =========================================================================

    @Test
    @DisplayName("17. Filter by transactionType = TRANSFER works")
    void test17_filterByTransactionTypeTransfer() throws Exception {
        Instant now = Instant.parse("2026-09-24T10:00:00Z");
        insertTransaction(UUID.randomUUID(), "f-transfer", aliceAccount, bobAccount, new BigDecimal("10.0000"), "USD", TransactionType.TRANSFER, TransactionStatus.COMPLETED, aliceUser, "Transfer", now, now);
        insertTransaction(UUID.randomUUID(), "f-deposit", clearingAccount, aliceAccount, new BigDecimal("20.0000"), "USD", TransactionType.DEPOSIT, TransactionStatus.COMPLETED, null, "Deposit", now.plusSeconds(1), now.plusSeconds(1));

        mockMvc.perform(get("/api/v1/accounts/{accountId}/transactions", aliceAccount.getId())
                        .param("transactionType", "TRANSFER")
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].transactionType", is("TRANSFER")))
                .andExpect(jsonPath("$.totalElements", is(1)));
    }

    @Test
    @DisplayName("18. Filter by transactionType = DEPOSIT works")
    void test18_filterByTransactionTypeDeposit() throws Exception {
        Instant now = Instant.parse("2026-09-24T10:00:00Z");
        insertTransaction(UUID.randomUUID(), "f-transfer-2", aliceAccount, bobAccount, new BigDecimal("10.0000"), "USD", TransactionType.TRANSFER, TransactionStatus.COMPLETED, aliceUser, "Transfer", now, now);
        insertTransaction(UUID.randomUUID(), "f-deposit-2", clearingAccount, aliceAccount, new BigDecimal("20.0000"), "USD", TransactionType.DEPOSIT, TransactionStatus.COMPLETED, null, "Deposit", now.plusSeconds(1), now.plusSeconds(1));

        mockMvc.perform(get("/api/v1/accounts/{accountId}/transactions", aliceAccount.getId())
                        .param("transactionType", "DEPOSIT")
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].transactionType", is("DEPOSIT")))
                .andExpect(jsonPath("$.totalElements", is(1)));
    }

    @Test
    @DisplayName("19. Invalid transactionType returns 400 Bad Request")
    void test19_invalidTransactionTypeReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/{accountId}/transactions", aliceAccount.getId())
                        .param("transactionType", "INVALID_TYPE")
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("Invalid transaction type")));
    }

    @Test
    @DisplayName("20. Filter by status = COMPLETED works")
    void test20_filterByStatusCompleted() throws Exception {
        Instant now = Instant.parse("2026-09-24T10:00:00Z");
        insertTransaction(UUID.randomUUID(), "st-comp", aliceAccount, bobAccount, new BigDecimal("10.0000"), "USD", TransactionType.TRANSFER, TransactionStatus.COMPLETED, aliceUser, "Completed", now, now);
        insertTransaction(UUID.randomUUID(), "st-fail", aliceAccount, bobAccount, new BigDecimal("20.0000"), "USD", TransactionType.TRANSFER, TransactionStatus.FAILED, aliceUser, "Failed", now.plusSeconds(1), null);

        mockMvc.perform(get("/api/v1/accounts/{accountId}/transactions", aliceAccount.getId())
                        .param("status", "COMPLETED")
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].status", is("COMPLETED")))
                .andExpect(jsonPath("$.totalElements", is(1)));
    }

    @Test
    @DisplayName("20b. Filter by status = FAILED works")
    void test20b_filterByStatusFailed() throws Exception {
        Instant now = Instant.parse("2026-09-24T10:00:00Z");
        insertTransaction(UUID.randomUUID(), "st-comp-b", aliceAccount, bobAccount, new BigDecimal("10.0000"), "USD", TransactionType.TRANSFER, TransactionStatus.COMPLETED, aliceUser, "Completed", now, now);
        insertTransaction(UUID.randomUUID(), "st-fail-b", aliceAccount, bobAccount, new BigDecimal("20.0000"), "USD", TransactionType.TRANSFER, TransactionStatus.FAILED, aliceUser, "Failed", now.plusSeconds(1), null);

        mockMvc.perform(get("/api/v1/accounts/{accountId}/transactions", aliceAccount.getId())
                        .param("status", "FAILED")
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].status", is("FAILED")))
                .andExpect(jsonPath("$.totalElements", is(1)));
    }

    @Test
    @DisplayName("21. Invalid status returns 400 Bad Request")
    void test21_invalidStatusReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/{accountId}/transactions", aliceAccount.getId())
                        .param("status", "NOT_A_STATUS")
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("Invalid transaction status")));
    }

    @Test
    @DisplayName("22. from filter works (inclusive: createdAt >= from)")
    void test22_fromFilterWorks_inclusive() throws Exception {
        Instant t1 = Instant.parse("2026-09-24T08:00:00Z");
        Instant t2 = Instant.parse("2026-09-24T09:00:00Z");
        Instant t3 = Instant.parse("2026-09-24T10:00:00Z");

        insertTransaction(UUID.randomUUID(), "date-1", aliceAccount, bobAccount, new BigDecimal("1.0000"), "USD", TransactionType.TRANSFER, TransactionStatus.COMPLETED, aliceUser, "T1", t1, t1);
        insertTransaction(UUID.randomUUID(), "date-2", aliceAccount, bobAccount, new BigDecimal("2.0000"), "USD", TransactionType.TRANSFER, TransactionStatus.COMPLETED, aliceUser, "T2", t2, t2);
        insertTransaction(UUID.randomUUID(), "date-3", aliceAccount, bobAccount, new BigDecimal("3.0000"), "USD", TransactionType.TRANSFER, TransactionStatus.COMPLETED, aliceUser, "T3", t3, t3);

        // Filter from=t2 -> should include t2 and t3 (inclusive)
        mockMvc.perform(get("/api/v1/accounts/{accountId}/transactions", aliceAccount.getId())
                        .param("from", t2.toString())
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[0].description", is("T3")))
                .andExpect(jsonPath("$.content[1].description", is("T2")));
    }

    @Test
    @DisplayName("23. to filter works (exclusive: createdAt < to)")
    void test23_toFilterWorks_exclusive() throws Exception {
        Instant t1 = Instant.parse("2026-09-24T08:00:00Z");
        Instant t2 = Instant.parse("2026-09-24T09:00:00Z");
        Instant t3 = Instant.parse("2026-09-24T10:00:00Z");

        insertTransaction(UUID.randomUUID(), "to-1", aliceAccount, bobAccount, new BigDecimal("1.0000"), "USD", TransactionType.TRANSFER, TransactionStatus.COMPLETED, aliceUser, "T1", t1, t1);
        insertTransaction(UUID.randomUUID(), "to-2", aliceAccount, bobAccount, new BigDecimal("2.0000"), "USD", TransactionType.TRANSFER, TransactionStatus.COMPLETED, aliceUser, "T2", t2, t2);
        insertTransaction(UUID.randomUUID(), "to-3", aliceAccount, bobAccount, new BigDecimal("3.0000"), "USD", TransactionType.TRANSFER, TransactionStatus.COMPLETED, aliceUser, "T3", t3, t3);

        // Filter to=t3 -> should include t1 and t2 (exclusive of t3)
        mockMvc.perform(get("/api/v1/accounts/{accountId}/transactions", aliceAccount.getId())
                        .param("to", t3.toString())
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[0].description", is("T2")))
                .andExpect(jsonPath("$.content[1].description", is("T1")));
    }

    @Test
    @DisplayName("24. from + to filter combined works")
    void test24_fromAndToFilterCombinedWorks() throws Exception {
        Instant t1 = Instant.parse("2026-09-24T08:00:00Z");
        Instant t2 = Instant.parse("2026-09-24T09:00:00Z");
        Instant t3 = Instant.parse("2026-09-24T10:00:00Z");

        insertTransaction(UUID.randomUUID(), "comb-1", aliceAccount, bobAccount, new BigDecimal("1.0000"), "USD", TransactionType.TRANSFER, TransactionStatus.COMPLETED, aliceUser, "T1", t1, t1);
        insertTransaction(UUID.randomUUID(), "comb-2", aliceAccount, bobAccount, new BigDecimal("2.0000"), "USD", TransactionType.TRANSFER, TransactionStatus.COMPLETED, aliceUser, "T2", t2, t2);
        insertTransaction(UUID.randomUUID(), "comb-3", aliceAccount, bobAccount, new BigDecimal("3.0000"), "USD", TransactionType.TRANSFER, TransactionStatus.COMPLETED, aliceUser, "T3", t3, t3);

        // Filter from=t2 & to=t3 -> only t2
        mockMvc.perform(get("/api/v1/accounts/{accountId}/transactions", aliceAccount.getId())
                        .param("from", t2.toString())
                        .param("to", t3.toString())
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].description", is("T2")));
    }

    @Test
    @DisplayName("25. Invalid timestamp format returns 400 Bad Request")
    void test25_invalidTimestampReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/{accountId}/transactions", aliceAccount.getId())
                        .param("from", "not-a-timestamp")
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("Invalid ISO-8601 'from' timestamp")));

        mockMvc.perform(get("/api/v1/accounts/{accountId}/transactions", aliceAccount.getId())
                        .param("to", "2026/09/24 10:00:00")
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("Invalid ISO-8601 'to' timestamp")));
    }

    @Test
    @DisplayName("25b. from > to returns 400 Bad Request")
    void test25b_fromGreaterThanToReturns400() throws Exception {
        Instant later = Instant.parse("2026-09-24T12:00:00Z");
        Instant earlier = Instant.parse("2026-09-24T10:00:00Z");

        mockMvc.perform(get("/api/v1/accounts/{accountId}/transactions", aliceAccount.getId())
                        .param("from", later.toString())
                        .param("to", earlier.toString())
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("must be before or equal to")));
    }

    @Test
    @DisplayName("25c. from == to returns 200 OK with empty content")
    void test25c_fromEqualToToReturnsEmptyPage() throws Exception {
        Instant t = Instant.parse("2026-09-24T10:00:00Z");

        mockMvc.perform(get("/api/v1/accounts/{accountId}/transactions", aliceAccount.getId())
                        .param("from", t.toString())
                        .param("to", t.toString())
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.totalElements", is(0)));
    }

    // =========================================================================
    // 5. COMBINED / BOUNDARY CASES (Tests 26–28)
    // =========================================================================

    @Test
    @DisplayName("26. Multiple filters together: transactionType + status + date range")
    void test26_multipleFiltersTogether() throws Exception {
        Instant t1 = Instant.parse("2026-09-24T08:00:00Z");
        Instant t2 = Instant.parse("2026-09-24T09:00:00Z");
        Instant t3 = Instant.parse("2026-09-24T10:00:00Z");

        // Match target: TRANSFER, COMPLETED, t2
        insertTransaction(UUID.randomUUID(), "multi-1", aliceAccount, bobAccount, new BigDecimal("10.0000"), "USD", TransactionType.TRANSFER, TransactionStatus.COMPLETED, aliceUser, "Target", t2, t2);
        // Different type
        insertTransaction(UUID.randomUUID(), "multi-2", clearingAccount, aliceAccount, new BigDecimal("20.0000"), "USD", TransactionType.DEPOSIT, TransactionStatus.COMPLETED, null, "Diff type", t2, t2);
        // Different status
        insertTransaction(UUID.randomUUID(), "multi-3", aliceAccount, bobAccount, new BigDecimal("30.0000"), "USD", TransactionType.TRANSFER, TransactionStatus.FAILED, aliceUser, "Diff status", t2, null);
        // Different time
        insertTransaction(UUID.randomUUID(), "multi-4", aliceAccount, bobAccount, new BigDecimal("40.0000"), "USD", TransactionType.TRANSFER, TransactionStatus.COMPLETED, aliceUser, "Diff time", t1, t1);

        mockMvc.perform(get("/api/v1/accounts/{accountId}/transactions", aliceAccount.getId())
                        .param("transactionType", "TRANSFER")
                        .param("status", "COMPLETED")
                        .param("from", t2.toString())
                        .param("to", t3.toString())
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].description", is("Target")))
                .andExpect(jsonPath("$.totalElements", is(1)));
    }

    @Test
    @DisplayName("27. Pagination with filters works seamlessly")
    void test27_paginationWithFilters() throws Exception {
        Instant baseTime = Instant.parse("2026-09-24T10:00:00Z");
        // 5 transfers, 5 deposits
        for (int i = 0; i < 5; i++) {
            insertTransaction(UUID.randomUUID(), "pwf-t-" + i, aliceAccount, bobAccount, new BigDecimal("10.0000"), "USD", TransactionType.TRANSFER, TransactionStatus.COMPLETED, aliceUser, "Transfer " + i, baseTime.plusSeconds(i * 10), baseTime.plusSeconds(i * 10));
            insertTransaction(UUID.randomUUID(), "pwf-d-" + i, clearingAccount, aliceAccount, new BigDecimal("20.0000"), "USD", TransactionType.DEPOSIT, TransactionStatus.COMPLETED, null, "Deposit " + i, baseTime.plusSeconds(i * 10 + 5), baseTime.plusSeconds(i * 10 + 5));
        }

        // Query only TRANSFER, size=2, page=1
        mockMvc.perform(get("/api/v1/accounts/{accountId}/transactions", aliceAccount.getId())
                        .param("transactionType", "TRANSFER")
                        .param("page", "1")
                        .param("size", "2")
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.page", is(1)))
                .andExpect(jsonPath("$.size", is(2)))
                .andExpect(jsonPath("$.totalElements", is(5)))
                .andExpect(jsonPath("$.totalPages", is(3)))
                .andExpect(jsonPath("$.content[0].transactionType", is("TRANSFER")))
                .andExpect(jsonPath("$.content[1].transactionType", is("TRANSFER")));
    }

    @Test
    @DisplayName("28. Account with no matching transactions for filter returns empty page")
    void test28_accountWithNoMatchingTransactionsForFilterReturnsEmptyPage() throws Exception {
        Instant now = Instant.parse("2026-09-24T10:00:00Z");
        insertTransaction(UUID.randomUUID(), "nomatch-1", aliceAccount, bobAccount, new BigDecimal("10.0000"), "USD", TransactionType.TRANSFER, TransactionStatus.COMPLETED, aliceUser, "Transfer", now, now);

        mockMvc.perform(get("/api/v1/accounts/{accountId}/transactions", aliceAccount.getId())
                        .param("status", "FAILED")
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.totalElements", is(0)))
                .andExpect(jsonPath("$.totalPages", is(0)))
                .andExpect(jsonPath("$.first", is(true)))
                .andExpect(jsonPath("$.last", is(true)));
    }

    // =========================================================================
    // 6. ISOLATION / SAFETY TESTS (Tests 29–31)
    // =========================================================================

    @Test
    @DisplayName("29. History request leaves account balances completely unchanged")
    void test29_historyRequestLeavesAccountBalancesUnchanged() throws Exception {
        BigDecimal aliceBalanceBefore = accountRepository.findById(aliceAccount.getId()).orElseThrow().getBalance();
        BigDecimal bobBalanceBefore = accountRepository.findById(bobAccount.getId()).orElseThrow().getBalance();
        BigDecimal clearingBalanceBefore = accountRepository.findById(clearingAccount.getId()).orElseThrow().getBalance();

        mockMvc.perform(get("/api/v1/accounts/{accountId}/transactions", aliceAccount.getId())
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isOk());

        BigDecimal aliceBalanceAfter = accountRepository.findById(aliceAccount.getId()).orElseThrow().getBalance();
        BigDecimal bobBalanceAfter = accountRepository.findById(bobAccount.getId()).orElseThrow().getBalance();
        BigDecimal clearingBalanceAfter = accountRepository.findById(clearingAccount.getId()).orElseThrow().getBalance();

        assertThat(aliceBalanceAfter).isEqualByComparingTo(aliceBalanceBefore);
        assertThat(bobBalanceAfter).isEqualByComparingTo(bobBalanceBefore);
        assertThat(clearingBalanceAfter).isEqualByComparingTo(clearingBalanceBefore);
    }

    @Test
    @DisplayName("30. History request creates no transactions")
    void test30_historyRequestCreatesNoTransactions() throws Exception {
        long txCountBefore = transactionRepository.count();

        mockMvc.perform(get("/api/v1/accounts/{accountId}/transactions", aliceAccount.getId())
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isOk());

        long txCountAfter = transactionRepository.count();
        assertThat(txCountAfter).isEqualTo(txCountBefore);
    }

    @Test
    @DisplayName("31. History request modifies no ledger entries")
    void test31_historyRequestModifiesNoLedgerEntries() throws Exception {
        long entryCountBefore = ledgerEntryRepository.count();

        mockMvc.perform(get("/api/v1/accounts/{accountId}/transactions", aliceAccount.getId())
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isOk());

        long entryCountAfter = ledgerEntryRepository.count();
        assertThat(entryCountAfter).isEqualTo(entryCountBefore);
    }

    // =========================================================================
    // 7. FULL END-TO-END OPERATION (Test 32)
    // =========================================================================

    @Test
    @DisplayName("32. End-to-end lifecycle: Deposit and Transfer transactions appear with correct directions")
    void test32_endToEndLifecycle_depositAndTransferHistoryVisible() throws Exception {
        // Step 1: Alice deposits 1000 USD via DepositService
        DepositRequestDto depositReq = new DepositRequestDto(
                aliceAccount.getId(),
                new BigDecimal("1000.00"),
                "USD",
                "Payroll deposit"
        );
        SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        aliceUser.getEmail(), null, java.util.Collections.emptyList()
                )
        );
        depositService.executeDeposit("idem-e2e-dep", depositReq);

        // Step 2: Alice transfers 200 USD to Bob via TransferService
        TransferRequestDto transferReq = new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("200.00"),
                "USD"
        );
        transferService.executeTransfer("idem-e2e-tx", transferReq);

        // Step 3: Alice queries her transaction history
        mockMvc.perform(get("/api/v1/accounts/{accountId}/transactions", aliceAccount.getId())
                        .with(user(aliceUser.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                // Newest first: Transfer was executed after deposit
                .andExpect(jsonPath("$.content[0].transactionType", is("TRANSFER")))
                .andExpect(jsonPath("$.content[0].direction", is("DEBIT")))
                .andExpect(jsonPath("$.content[0].amount", is(200.0)))
                .andExpect(jsonPath("$.content[1].transactionType", is("DEPOSIT")))
                .andExpect(jsonPath("$.content[1].direction", is("CREDIT")))
                .andExpect(jsonPath("$.content[1].amount", is(1000.0)))
                .andExpect(jsonPath("$.totalElements", is(2)));

        // Step 4: Bob queries his transaction history
        mockMvc.perform(get("/api/v1/accounts/{accountId}/transactions", bobAccount.getId())
                        .with(user(bobUser.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].transactionType", is("TRANSFER")))
                .andExpect(jsonPath("$.content[0].direction", is("CREDIT")))
                .andExpect(jsonPath("$.content[0].amount", is(200.0)))
                .andExpect(jsonPath("$.totalElements", is(1)));
    }
}
