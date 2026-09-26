package com.parth.ledger.account;

import com.parth.ledger.BaseIntegrationTest;
import com.parth.ledger.deposit.dto.DepositRequestDto;
import com.parth.ledger.deposit.service.DepositService;
import com.parth.ledger.ledger.LedgerEntryRepository;
import com.parth.ledger.reconciliation.ReconciliationStatus;
import com.parth.ledger.reconciliation.dto.ReconciliationResultDto;
import com.parth.ledger.reconciliation.service.ReconciliationService;
import com.parth.ledger.statement.dto.AccountStatementResponseDto;
import com.parth.ledger.statement.service.AccountStatementService;
import com.parth.ledger.transaction.TransactionRepository;
import com.parth.ledger.transaction.dto.TransactionHistoryPageResponseDto;
import com.parth.ledger.transaction.dto.TransferRequestDto;
import com.parth.ledger.transaction.service.TransactionHistoryService;
import com.parth.ledger.transaction.service.TransferService;
import com.parth.ledger.user.User;
import com.parth.ledger.user.UserRepository;
import com.parth.ledger.withdrawal.dto.WithdrawalRequestDto;
import com.parth.ledger.withdrawal.service.WithdrawalService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@DisplayName("V10 Account Lifecycle & Administrative Operations Integration Tests")
class AccountLifecycleIntegrationTest extends BaseIntegrationTest {

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
    private DepositService depositService;

    @Autowired
    private WithdrawalService withdrawalService;

    @Autowired
    private ReconciliationService reconciliationService;

    @Autowired
    private TransactionHistoryService transactionHistoryService;

    @Autowired
    private AccountStatementService accountStatementService;

    @Autowired
    private AccountService accountService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User adminUser;
    private User aliceUser;
    private User bobUser;
    private Account aliceAccount;
    private Account bobAccount;
    private Account clearingAccount;

    public static final UUID CLEARING_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

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

        adminUser = userRepository.save(new User("admin@ledger.com", "Admin User"));
        aliceUser = userRepository.save(new User("alice@ledger.com", "Alice"));
        bobUser = userRepository.save(new User("bob@ledger.com", "Bob"));

        aliceAccount = accountRepository.save(new Account(aliceUser, "INR", BigDecimal.ZERO.setScale(4)));
        bobAccount = accountRepository.save(new Account(bobUser, "INR", BigDecimal.ZERO.setScale(4)));

        clearingAccount = ensureSystemClearingAccount(new BigDecimal("100000.0000"));
    }

    private Account ensureSystemClearingAccount(BigDecimal initialBalance) {
        jdbcTemplate.update(
                "INSERT INTO accounts (id, user_id, currency, balance, version, created_at, updated_at, account_type, status, account_number) " +
                        "VALUES ('00000000-0000-0000-0000-000000000001', NULL, 'INR', ?, 0, NOW(), NOW(), 'SYSTEM_CLEARING', 'ACTIVE', 'ACCT-SYSTEM-CLEARING-01') " +
                        "ON CONFLICT (id) DO UPDATE SET balance = EXCLUDED.balance, status = 'ACTIVE'",
                initialBalance
        );
        return accountRepository.findById(CLEARING_ID).orElseThrow();
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

    private void fundAccount(User user, Account account, BigDecimal amount) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user.getEmail(), null, Collections.emptyList())
        );
        try {
            depositService.executeDeposit("fund-" + UUID.randomUUID(), new DepositRequestDto(account.getId(), amount, "INR", "Funding"));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void setAuthenticatedUser(User user) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user.getEmail(), null, Collections.emptyList())
        );
    }

    // =========================================================================
    // 1. BASIC LIFECYCLE TRANSITIONS (1–9)
    // =========================================================================

    @Nested
    @DisplayName("1. Basic Lifecycle State Machine Transitions")
    class BasicLifecycleTests {

        @Test
        @DisplayName("1. ACTIVE → FROZEN succeeds (200 OK)")
        void activeToFrozenSuccess() throws Exception {
            mockMvc.perform(post("/api/v1/accounts/" + aliceAccount.getId() + "/freeze")
                            .with(user("admin@ledger.com").roles("ADMIN")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status", is("FROZEN")))
                    .andExpect(jsonPath("$.accountId", is(aliceAccount.getId().toString())));

            Account reloaded = accountRepository.findById(aliceAccount.getId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(AccountStatus.FROZEN);
            assertThat(reloaded.isFrozen()).isTrue();
        }

        @Test
        @DisplayName("2. FROZEN → FROZEN idempotent no-op returns 200 with current representation")
        void frozenToFrozenIdempotent() throws Exception {
            aliceAccount.setStatus(AccountStatus.FROZEN);
            accountRepository.save(aliceAccount);

            mockMvc.perform(post("/api/v1/accounts/" + aliceAccount.getId() + "/freeze")
                            .with(user("admin@ledger.com").roles("ADMIN")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status", is("FROZEN")))
                    .andExpect(jsonPath("$.accountId", is(aliceAccount.getId().toString())));

            Account reloaded = accountRepository.findById(aliceAccount.getId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(AccountStatus.FROZEN);
        }

        @Test
        @DisplayName("3. FROZEN → ACTIVE succeeds (200 OK)")
        void frozenToActiveSuccess() throws Exception {
            aliceAccount.setStatus(AccountStatus.FROZEN);
            accountRepository.save(aliceAccount);

            mockMvc.perform(post("/api/v1/accounts/" + aliceAccount.getId() + "/unfreeze")
                            .with(user("admin@ledger.com").roles("ADMIN")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status", is("ACTIVE")))
                    .andExpect(jsonPath("$.accountId", is(aliceAccount.getId().toString())));

            Account reloaded = accountRepository.findById(aliceAccount.getId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(AccountStatus.ACTIVE);
            assertThat(reloaded.isActive()).isTrue();
        }

        @Test
        @DisplayName("4. ACTIVE → ACTIVE unfreeze rejected with 422 Unprocessable Content")
        void activeToActiveUnfreezeRejected() throws Exception {
            mockMvc.perform(post("/api/v1/accounts/" + aliceAccount.getId() + "/unfreeze")
                            .with(user("admin@ledger.com").roles("ADMIN")))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.status", is(422)))
                    .andExpect(jsonPath("$.message", containsString("already ACTIVE")));

            Account reloaded = accountRepository.findById(aliceAccount.getId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        }

        @Test
        @DisplayName("5. ACTIVE zero-balance → CLOSED succeeds (200 OK)")
        void activeZeroBalanceToClosedSuccess() throws Exception {
            mockMvc.perform(post("/api/v1/accounts/" + aliceAccount.getId() + "/close")
                            .with(user("alice@ledger.com")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status", is("CLOSED")))
                    .andExpect(jsonPath("$.accountId", is(aliceAccount.getId().toString())));

            Account reloaded = accountRepository.findById(aliceAccount.getId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(AccountStatus.CLOSED);
            assertThat(reloaded.isClosed()).isTrue();
        }

        @Test
        @DisplayName("6. FROZEN zero-balance → CLOSED succeeds (200 OK)")
        void frozenZeroBalanceToClosedSuccess() throws Exception {
            aliceAccount.setStatus(AccountStatus.FROZEN);
            accountRepository.save(aliceAccount);

            mockMvc.perform(post("/api/v1/accounts/" + aliceAccount.getId() + "/close")
                            .with(user("alice@ledger.com")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status", is("CLOSED")))
                    .andExpect(jsonPath("$.accountId", is(aliceAccount.getId().toString())));

            Account reloaded = accountRepository.findById(aliceAccount.getId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(AccountStatus.CLOSED);
            assertThat(reloaded.isClosed()).isTrue();
        }

        @Test
        @DisplayName("7. CLOSED → CLOSED idempotent no-op returns 200 with current representation")
        void closedToClosedIdempotent() throws Exception {
            aliceAccount.setStatus(AccountStatus.CLOSED);
            accountRepository.save(aliceAccount);

            mockMvc.perform(post("/api/v1/accounts/" + aliceAccount.getId() + "/close")
                            .with(user("alice@ledger.com")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status", is("CLOSED")))
                    .andExpect(jsonPath("$.accountId", is(aliceAccount.getId().toString())));

            Account reloaded = accountRepository.findById(aliceAccount.getId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(AccountStatus.CLOSED);
        }

        @Test
        @DisplayName("8. CLOSED → ACTIVE rejected with 422 Unprocessable Content (AccountClosedException)")
        void closedToActiveRejected() throws Exception {
            aliceAccount.setStatus(AccountStatus.CLOSED);
            accountRepository.save(aliceAccount);

            mockMvc.perform(post("/api/v1/accounts/" + aliceAccount.getId() + "/unfreeze")
                            .with(user("admin@ledger.com").roles("ADMIN")))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.status", is(422)))
                    .andExpect(jsonPath("$.message", containsString("CLOSED")));

            Account reloaded = accountRepository.findById(aliceAccount.getId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(AccountStatus.CLOSED);
        }

        @Test
        @DisplayName("9. CLOSED → FROZEN rejected with 422 Unprocessable Content (AccountClosedException)")
        void closedToFrozenRejected() throws Exception {
            aliceAccount.setStatus(AccountStatus.CLOSED);
            accountRepository.save(aliceAccount);

            mockMvc.perform(post("/api/v1/accounts/" + aliceAccount.getId() + "/freeze")
                            .with(user("admin@ledger.com").roles("ADMIN")))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.status", is(422)))
                    .andExpect(jsonPath("$.message", containsString("CLOSED")));

            Account reloaded = accountRepository.findById(aliceAccount.getId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(AccountStatus.CLOSED);
        }
    }

    // =========================================================================
    // 2. CLOSING SEMANTICS & ZERO-BALANCE RULE (10–11)
    // =========================================================================

    @Nested
    @DisplayName("2. Closing Semantics with Non-Zero Balance")
    class ClosingSemanticsTests {

        @Test
        @DisplayName("10. ACTIVE non-zero balance closure rejected with 422 Unprocessable Content")
        void activeNonZeroBalanceCloseRejected() throws Exception {
            fundAccount(aliceUser, aliceAccount, new BigDecimal("100.0000"));

            mockMvc.perform(post("/api/v1/accounts/" + aliceAccount.getId() + "/close")
                            .with(user("alice@ledger.com")))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.status", is(422)))
                    .andExpect(jsonPath("$.message", containsString("non-zero balance")));

            Account reloaded = accountRepository.findById(aliceAccount.getId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(AccountStatus.ACTIVE);
            assertThat(reloaded.getBalance()).isEqualByComparingTo("100.0000");
        }

        @Test
        @DisplayName("11. FROZEN non-zero balance closure rejected with 422 Unprocessable Content")
        void frozenNonZeroBalanceCloseRejected() throws Exception {
            fundAccount(aliceUser, aliceAccount, new BigDecimal("250.0000"));
            aliceAccount = accountRepository.findById(aliceAccount.getId()).orElseThrow();
            aliceAccount.setStatus(AccountStatus.FROZEN);
            accountRepository.save(aliceAccount);

            mockMvc.perform(post("/api/v1/accounts/" + aliceAccount.getId() + "/close")
                            .with(user("alice@ledger.com")))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.status", is(422)))
                    .andExpect(jsonPath("$.message", containsString("non-zero balance")));

            Account reloaded = accountRepository.findById(aliceAccount.getId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(AccountStatus.FROZEN);
            assertThat(reloaded.getBalance()).isEqualByComparingTo("250.0000");
        }
    }

    // =========================================================================
    // 3. SECURITY & AUTHORIZATION (12–19)
    // =========================================================================

    @Nested
    @DisplayName("3. Security & Authorization Boundaries")
    class SecurityTests {

        @Test
        @DisplayName("12. Unauthenticated freeze returns 401 Unauthorized")
        void unauthenticatedFreezeRejected() throws Exception {
            mockMvc.perform(post("/api/v1/accounts/" + aliceAccount.getId() + "/freeze"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("13. Non-admin freeze returns 403 Forbidden")
        void nonAdminFreezeForbidden() throws Exception {
            mockMvc.perform(post("/api/v1/accounts/" + aliceAccount.getId() + "/freeze")
                            .with(user("alice@ledger.com")))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("14. Non-admin unfreeze returns 403 Forbidden")
        void nonAdminUnfreezeForbidden() throws Exception {
            mockMvc.perform(post("/api/v1/accounts/" + aliceAccount.getId() + "/unfreeze")
                            .with(user("alice@ledger.com")))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("15. Owner can close their own account (200 OK)")
        void ownerCloseSuccess() throws Exception {
            mockMvc.perform(post("/api/v1/accounts/" + aliceAccount.getId() + "/close")
                            .with(user("alice@ledger.com")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status", is("CLOSED")));
        }

        @Test
        @DisplayName("16. Foreign account close returns 404 Not Found (anti-enumeration)")
        void foreignAccountCloseReturns404() throws Exception {
            mockMvc.perform(post("/api/v1/accounts/" + aliceAccount.getId() + "/close")
                            .with(user("bob@ledger.com")))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status", is(404)));
        }

        @Test
        @DisplayName("17. SYSTEM_CLEARING freeze rejected (400 Bad Request)")
        void systemClearingFreezeRejected() throws Exception {
            mockMvc.perform(post("/api/v1/accounts/" + CLEARING_ID + "/freeze")
                            .with(user("admin@ledger.com").roles("ADMIN")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status", is(400)))
                    .andExpect(jsonPath("$.message", containsString("USER_CHECKING")));

            Account clearing = accountRepository.findById(CLEARING_ID).orElseThrow();
            assertThat(clearing.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        }

        @Test
        @DisplayName("18. SYSTEM_CLEARING unfreeze rejected (400 Bad Request)")
        void systemClearingUnfreezeRejected() throws Exception {
            mockMvc.perform(post("/api/v1/accounts/" + CLEARING_ID + "/unfreeze")
                            .with(user("admin@ledger.com").roles("ADMIN")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status", is(400)))
                    .andExpect(jsonPath("$.message", containsString("USER_CHECKING")));

            Account clearing = accountRepository.findById(CLEARING_ID).orElseThrow();
            assertThat(clearing.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        }

        @Test
        @DisplayName("19. SYSTEM_CLEARING close returns 404 Not Found")
        void systemClearingCloseReturns404() throws Exception {
            mockMvc.perform(post("/api/v1/accounts/" + CLEARING_ID + "/close")
                            .with(user("alice@ledger.com")))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status", is(404)));

            Account clearing = accountRepository.findById(CLEARING_ID).orElseThrow();
            assertThat(clearing.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        }
    }

    // =========================================================================
    // 4. FINANCIAL INVARIANTS (20–25)
    // =========================================================================

    @Nested
    @DisplayName("4. Financial Invariants & Read-Only Audit Access")
    class FinancialInvariantsTests {

        @Test
        @DisplayName("20. Lifecycle transitions never change account balance")
        void lifecycleDoesNotChangeBalance() throws Exception {
            fundAccount(aliceUser, aliceAccount, new BigDecimal("300.0000"));
            aliceAccount = accountRepository.findById(aliceAccount.getId()).orElseThrow();
            BigDecimal initialBalance = aliceAccount.getBalance();

            // Freeze
            mockMvc.perform(post("/api/v1/accounts/" + aliceAccount.getId() + "/freeze")
                            .with(user("admin@ledger.com").roles("ADMIN")))
                    .andExpect(status().isOk());
            assertThat(accountRepository.findById(aliceAccount.getId()).orElseThrow().getBalance())
                    .isEqualByComparingTo(initialBalance);

            // Unfreeze
            mockMvc.perform(post("/api/v1/accounts/" + aliceAccount.getId() + "/unfreeze")
                            .with(user("admin@ledger.com").roles("ADMIN")))
                    .andExpect(status().isOk());
            assertThat(accountRepository.findById(aliceAccount.getId()).orElseThrow().getBalance())
                    .isEqualByComparingTo(initialBalance);
        }

        @Test
        @DisplayName("21. Lifecycle transitions create zero transaction records")
        void lifecycleCreatesNoTransaction() throws Exception {
            long txCountBefore = transactionRepository.count();

            mockMvc.perform(post("/api/v1/accounts/" + aliceAccount.getId() + "/freeze")
                            .with(user("admin@ledger.com").roles("ADMIN")))
                    .andExpect(status().isOk());
            mockMvc.perform(post("/api/v1/accounts/" + aliceAccount.getId() + "/unfreeze")
                            .with(user("admin@ledger.com").roles("ADMIN")))
                    .andExpect(status().isOk());
            mockMvc.perform(post("/api/v1/accounts/" + aliceAccount.getId() + "/close")
                            .with(user("alice@ledger.com")))
                    .andExpect(status().isOk());

            assertThat(transactionRepository.count()).isEqualTo(txCountBefore);
        }

        @Test
        @DisplayName("22. Lifecycle transitions create zero ledger entries")
        void lifecycleCreatesNoLedgerEntries() throws Exception {
            long ledgerCountBefore = ledgerEntryRepository.count();

            mockMvc.perform(post("/api/v1/accounts/" + aliceAccount.getId() + "/freeze")
                            .with(user("admin@ledger.com").roles("ADMIN")))
                    .andExpect(status().isOk());
            mockMvc.perform(post("/api/v1/accounts/" + aliceAccount.getId() + "/unfreeze")
                            .with(user("admin@ledger.com").roles("ADMIN")))
                    .andExpect(status().isOk());
            mockMvc.perform(post("/api/v1/accounts/" + aliceAccount.getId() + "/close")
                            .with(user("alice@ledger.com")))
                    .andExpect(status().isOk());

            assertThat(ledgerEntryRepository.count()).isEqualTo(ledgerCountBefore);
        }

        @Test
        @DisplayName("23. Reconciliation remains consistent after lifecycle transitions")
        void reconciliationRemainsConsistent() throws Exception {
            fundAccount(aliceUser, aliceAccount, new BigDecimal("150.0000"));

            // Freeze & verify reconciliation
            mockMvc.perform(post("/api/v1/accounts/" + aliceAccount.getId() + "/freeze")
                            .with(user("admin@ledger.com").roles("ADMIN")))
                    .andExpect(status().isOk());

            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken("alice@ledger.com", null, Collections.emptyList())
            );
            ReconciliationResultDto reconFrozen = reconciliationService.reconcileAccount(aliceAccount.getId());
            assertThat(reconFrozen.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
            assertThat(reconFrozen.difference()).isEqualByComparingTo("0.0000");

            // Unfreeze & verify reconciliation
            mockMvc.perform(post("/api/v1/accounts/" + aliceAccount.getId() + "/unfreeze")
                            .with(user("admin@ledger.com").roles("ADMIN")))
                    .andExpect(status().isOk());

            setAuthenticatedUser(aliceUser);
            ReconciliationResultDto reconActive = reconciliationService.reconcileAccount(aliceAccount.getId());
            assertThat(reconActive.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
            assertThat(reconActive.difference()).isEqualByComparingTo("0.0000");
        }

        @Test
        @DisplayName("24. Account statements remain readable for FROZEN and CLOSED accounts")
        void statementsReadableForFrozenAndClosed() throws Exception {
            fundAccount(aliceUser, aliceAccount, new BigDecimal("200.0000"));

            // Freeze
            mockMvc.perform(post("/api/v1/accounts/" + aliceAccount.getId() + "/freeze")
                            .with(user("admin@ledger.com").roles("ADMIN")))
                    .andExpect(status().isOk());

            setAuthenticatedUser(aliceUser);
            AccountStatementResponseDto stmtFrozen = accountStatementService.getStatement(
                    aliceAccount.getId(), null, null, null, null, 0, 10
            );
            assertThat(stmtFrozen.closingBalance()).isEqualByComparingTo("200.0000");
            assertThat(stmtFrozen.entries()).hasSize(1);

            // Unfreeze and withdraw all to reach balance 0, then close
            mockMvc.perform(post("/api/v1/accounts/" + aliceAccount.getId() + "/unfreeze")
                            .with(user("admin@ledger.com").roles("ADMIN")))
                    .andExpect(status().isOk());

            setAuthenticatedUser(aliceUser);
            withdrawalService.executeWithdrawal("wdr-stmt-001", new WithdrawalRequestDto(aliceAccount.getId(), new BigDecimal("200.0000"), "INR", "Empty"));

            mockMvc.perform(post("/api/v1/accounts/" + aliceAccount.getId() + "/close")
                            .with(user("alice@ledger.com")))
                    .andExpect(status().isOk());

            setAuthenticatedUser(aliceUser);
            AccountStatementResponseDto stmtClosed = accountStatementService.getStatement(
                    aliceAccount.getId(), null, null, null, null, 0, 10
            );
            assertThat(stmtClosed.closingBalance()).isEqualByComparingTo("0.0000");
            assertThat(stmtClosed.entries()).hasSize(2);
        }

        @Test
        @DisplayName("25. Transaction history remains readable for FROZEN and CLOSED accounts")
        void transactionHistoryReadableForFrozenAndClosed() throws Exception {
            fundAccount(aliceUser, aliceAccount, new BigDecimal("100.0000"));

            mockMvc.perform(post("/api/v1/accounts/" + aliceAccount.getId() + "/freeze")
                            .with(user("admin@ledger.com").roles("ADMIN")))
                    .andExpect(status().isOk());

            setAuthenticatedUser(aliceUser);
            TransactionHistoryPageResponseDto historyFrozen = transactionHistoryService.getTransactionHistory(
                    aliceAccount.getId(), null, null, null, null, 0, 10
            );
            assertThat(historyFrozen.totalElements()).isEqualTo(1);

            // Unfreeze and withdraw all to reach balance 0, then close
            mockMvc.perform(post("/api/v1/accounts/" + aliceAccount.getId() + "/unfreeze")
                            .with(user("admin@ledger.com").roles("ADMIN")))
                    .andExpect(status().isOk());

            setAuthenticatedUser(aliceUser);
            withdrawalService.executeWithdrawal("wdr-hist-001", new WithdrawalRequestDto(aliceAccount.getId(), new BigDecimal("100.0000"), "INR", "Empty"));

            mockMvc.perform(post("/api/v1/accounts/" + aliceAccount.getId() + "/close")
                            .with(user("alice@ledger.com")))
                    .andExpect(status().isOk());

            setAuthenticatedUser(aliceUser);
            TransactionHistoryPageResponseDto historyClosed = transactionHistoryService.getTransactionHistory(
                    aliceAccount.getId(), null, null, null, null, 0, 10
            );
            assertThat(historyClosed.totalElements()).isEqualTo(2);
        }
    }

    // =========================================================================
    // 5. FINANCIAL OPERATION BLOCKING (26–33)
    // =========================================================================

    @Nested
    @DisplayName("5. Financial Operation Blocking & Unfreeze Restoration")
    class FinancialBlockingTests {

        @Test
        @DisplayName("26. Outgoing transfer from FROZEN account is rejected with 422")
        void frozenOutgoingTransferRejected() throws Exception {
            fundAccount(aliceUser, aliceAccount, new BigDecimal("500.0000"));
            aliceAccount = accountRepository.findById(aliceAccount.getId()).orElseThrow();
            aliceAccount.setStatus(AccountStatus.FROZEN);
            accountRepository.save(aliceAccount);

            TransferRequestDto request = new TransferRequestDto(aliceAccount.getId(), bobAccount.getId(), new BigDecimal("50.0000"), "INR");

            mockMvc.perform(post("/api/v1/transfers")
                            .with(user("alice@ledger.com"))
                            .header("Idempotency-Key", "tx-frz-out-01")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.status", is(422)))
                    .andExpect(jsonPath("$.message", containsString("FROZEN")));
        }

        @Test
        @DisplayName("27. Incoming transfer to FROZEN account is rejected with 422")
        void frozenIncomingTransferRejected() throws Exception {
            fundAccount(bobUser, bobAccount, new BigDecimal("500.0000"));
            aliceAccount.setStatus(AccountStatus.FROZEN);
            accountRepository.save(aliceAccount);

            TransferRequestDto request = new TransferRequestDto(bobAccount.getId(), aliceAccount.getId(), new BigDecimal("50.0000"), "INR");

            mockMvc.perform(post("/api/v1/transfers")
                            .with(user("bob@ledger.com"))
                            .header("Idempotency-Key", "tx-frz-in-01")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.status", is(422)))
                    .andExpect(jsonPath("$.message", containsString("FROZEN")));
        }

        @Test
        @DisplayName("28. Deposit into FROZEN account is rejected with 422")
        void frozenDepositRejected() throws Exception {
            aliceAccount.setStatus(AccountStatus.FROZEN);
            accountRepository.save(aliceAccount);

            DepositRequestDto request = new DepositRequestDto(aliceAccount.getId(), new BigDecimal("100.0000"), "INR", "Deposit");

            mockMvc.perform(post("/api/v1/deposits")
                            .with(user("alice@ledger.com"))
                            .header("Idempotency-Key", "dep-frz-01")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.status", is(422)))
                    .andExpect(jsonPath("$.message", containsString("FROZEN")));
        }

        @Test
        @DisplayName("29. Withdrawal from FROZEN account is rejected with 422")
        void frozenWithdrawalRejected() throws Exception {
            fundAccount(aliceUser, aliceAccount, new BigDecimal("300.0000"));
            aliceAccount = accountRepository.findById(aliceAccount.getId()).orElseThrow();
            aliceAccount.setStatus(AccountStatus.FROZEN);
            accountRepository.save(aliceAccount);

            WithdrawalRequestDto request = new WithdrawalRequestDto(aliceAccount.getId(), new BigDecimal("50.0000"), "INR", "Cash");

            mockMvc.perform(post("/api/v1/withdrawals")
                            .with(user("alice@ledger.com"))
                            .header("Idempotency-Key", "wdr-frz-01")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.status", is(422)))
                    .andExpect(jsonPath("$.message", containsString("FROZEN")));
        }

        @Test
        @DisplayName("30. Unfreeze restores all transfer, deposit, and withdrawal operations")
        void unfreezeRestoresFinancialOperations() throws Exception {
            aliceAccount.setStatus(AccountStatus.FROZEN);
            accountRepository.save(aliceAccount);

            // Unfreeze
            mockMvc.perform(post("/api/v1/accounts/" + aliceAccount.getId() + "/unfreeze")
                            .with(user("admin@ledger.com").roles("ADMIN")))
                    .andExpect(status().isOk());

            // 1. Deposit succeeds
            DepositRequestDto depositReq = new DepositRequestDto(aliceAccount.getId(), new BigDecimal("100.0000"), "INR", "Dep");
            mockMvc.perform(post("/api/v1/deposits")
                            .with(user("alice@ledger.com"))
                            .header("Idempotency-Key", "dep-unfrz-01")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(depositReq)))
                    .andExpect(status().isCreated());

            // 2. Outgoing transfer succeeds
            TransferRequestDto transferReq = new TransferRequestDto(aliceAccount.getId(), bobAccount.getId(), new BigDecimal("40.0000"), "INR");
            mockMvc.perform(post("/api/v1/transfers")
                            .with(user("alice@ledger.com"))
                            .header("Idempotency-Key", "tx-unfrz-01")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(transferReq)))
                    .andExpect(status().isOk());

            // 3. Withdrawal succeeds
            WithdrawalRequestDto withdrawalReq = new WithdrawalRequestDto(aliceAccount.getId(), new BigDecimal("30.0000"), "INR", "Wdr");
            mockMvc.perform(post("/api/v1/withdrawals")
                            .with(user("alice@ledger.com"))
                            .header("Idempotency-Key", "wdr-unfrz-01")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(withdrawalReq)))
                    .andExpect(status().isCreated());

            assertThat(accountRepository.findById(aliceAccount.getId()).orElseThrow().getBalance())
                    .isEqualByComparingTo("30.0000");
        }

        @Test
        @DisplayName("31. Closed account transfer is rejected with 422")
        void closedTransferRejected() throws Exception {
            aliceAccount.setStatus(AccountStatus.CLOSED);
            accountRepository.save(aliceAccount);

            fundAccount(bobUser, bobAccount, new BigDecimal("100.0000"));

            TransferRequestDto request = new TransferRequestDto(bobAccount.getId(), aliceAccount.getId(), new BigDecimal("20.0000"), "INR");

            mockMvc.perform(post("/api/v1/transfers")
                            .with(user("bob@ledger.com"))
                            .header("Idempotency-Key", "tx-closed-01")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.status", is(422)))
                    .andExpect(jsonPath("$.message", containsString("CLOSED")));
        }

        @Test
        @DisplayName("32. Closed account deposit is rejected with 422")
        void closedDepositRejected() throws Exception {
            aliceAccount.setStatus(AccountStatus.CLOSED);
            accountRepository.save(aliceAccount);

            DepositRequestDto request = new DepositRequestDto(aliceAccount.getId(), new BigDecimal("50.0000"), "INR", "Dep");

            mockMvc.perform(post("/api/v1/deposits")
                            .with(user("alice@ledger.com"))
                            .header("Idempotency-Key", "dep-closed-01")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.status", is(422)))
                    .andExpect(jsonPath("$.message", containsString("CLOSED")));
        }

        @Test
        @DisplayName("33. Closed account withdrawal is rejected with 422")
        void closedWithdrawalRejected() throws Exception {
            aliceAccount.setStatus(AccountStatus.CLOSED);
            accountRepository.save(aliceAccount);

            WithdrawalRequestDto request = new WithdrawalRequestDto(aliceAccount.getId(), new BigDecimal("10.0000"), "INR", "Wdr");

            mockMvc.perform(post("/api/v1/withdrawals")
                            .with(user("alice@ledger.com"))
                            .header("Idempotency-Key", "wdr-closed-01")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.status", is(422)))
                    .andExpect(jsonPath("$.message", containsString("CLOSED")));
        }
    }

    // =========================================================================
    // 6. CONCURRENCY & RACE CONDITIONS (34–42)
    // =========================================================================

    @Nested
    @DisplayName("6. Concurrency & Race Condition Verifications")
    class ConcurrencyTests {

        @Test
        @DisplayName("34. Concurrent freeze + transfer serializes safely")
        void concurrentFreezeAndTransfer() throws InterruptedException {
            fundAccount(aliceUser, aliceAccount, new BigDecimal("500.0000"));

            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(2);

            AtomicBoolean freezeSucceeded = new AtomicBoolean(false);
            AtomicBoolean transferSucceeded = new AtomicBoolean(false);

            executor.submit(() -> {
                try {
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken("admin@ledger.com", null,
                                    List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))
                    );
                    startLatch.await();
                    accountService.freezeAccount(aliceAccount.getId());
                    freezeSucceeded.set(true);
                } catch (Exception ignored) {
                } finally {
                    SecurityContextHolder.clearContext();
                    doneLatch.countDown();
                }
            });

            executor.submit(() -> {
                try {
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken("alice@ledger.com", null, Collections.emptyList())
                    );
                    startLatch.await();
                    transferService.executeTransfer("tx-conc-frz-01",
                            new TransferRequestDto(aliceAccount.getId(), bobAccount.getId(), new BigDecimal("100.0000"), "INR"));
                    transferSucceeded.set(true);
                } catch (Exception ignored) {
                } finally {
                    SecurityContextHolder.clearContext();
                    doneLatch.countDown();
                }
            });

            startLatch.countDown();
            assertThat(doneLatch.await(10, TimeUnit.SECONDS)).isTrue();
            executor.shutdown();

            Account reloaded = accountRepository.findById(aliceAccount.getId()).orElseThrow();
            assertThat(freezeSucceeded.get()).isTrue();
            assertThat(reloaded.getStatus()).isEqualTo(AccountStatus.FROZEN);

            // If transfer succeeded, balance is 400. If freeze happened first, balance is 500.
            if (transferSucceeded.get()) {
                assertThat(reloaded.getBalance()).isEqualByComparingTo("400.0000");
            } else {
                assertThat(reloaded.getBalance()).isEqualByComparingTo("500.0000");
            }
        }

        @Test
        @DisplayName("35. Concurrent freeze + deposit serializes safely")
        void concurrentFreezeAndDeposit() throws InterruptedException {
            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(2);

            AtomicBoolean freezeSucceeded = new AtomicBoolean(false);
            AtomicBoolean depositSucceeded = new AtomicBoolean(false);

            executor.submit(() -> {
                try {
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken("admin@ledger.com", null,
                                    List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))
                    );
                    startLatch.await();
                    accountService.freezeAccount(aliceAccount.getId());
                    freezeSucceeded.set(true);
                } catch (Exception ignored) {
                } finally {
                    SecurityContextHolder.clearContext();
                    doneLatch.countDown();
                }
            });

            executor.submit(() -> {
                try {
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken("alice@ledger.com", null, Collections.emptyList())
                    );
                    startLatch.await();
                    depositService.executeDeposit("dep-conc-frz-01",
                            new DepositRequestDto(aliceAccount.getId(), new BigDecimal("200.0000"), "INR", "Dep"));
                    depositSucceeded.set(true);
                } catch (Exception ignored) {
                } finally {
                    SecurityContextHolder.clearContext();
                    doneLatch.countDown();
                }
            });

            startLatch.countDown();
            assertThat(doneLatch.await(10, TimeUnit.SECONDS)).isTrue();
            executor.shutdown();

            Account reloaded = accountRepository.findById(aliceAccount.getId()).orElseThrow();
            assertThat(freezeSucceeded.get()).isTrue();
            assertThat(reloaded.getStatus()).isEqualTo(AccountStatus.FROZEN);

            if (depositSucceeded.get()) {
                assertThat(reloaded.getBalance()).isEqualByComparingTo("200.0000");
            } else {
                assertThat(reloaded.getBalance()).isEqualByComparingTo("0.0000");
            }
        }

        @Test
        @DisplayName("36. Concurrent freeze + withdrawal serializes safely")
        void concurrentFreezeAndWithdrawal() throws InterruptedException {
            fundAccount(aliceUser, aliceAccount, new BigDecimal("300.0000"));

            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(2);

            AtomicBoolean freezeSucceeded = new AtomicBoolean(false);
            AtomicBoolean withdrawalSucceeded = new AtomicBoolean(false);

            executor.submit(() -> {
                try {
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken("admin@ledger.com", null,
                                    List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))
                    );
                    startLatch.await();
                    accountService.freezeAccount(aliceAccount.getId());
                    freezeSucceeded.set(true);
                } catch (Exception ignored) {
                } finally {
                    SecurityContextHolder.clearContext();
                    doneLatch.countDown();
                }
            });

            executor.submit(() -> {
                try {
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken("alice@ledger.com", null, Collections.emptyList())
                    );
                    startLatch.await();
                    withdrawalService.executeWithdrawal("wdr-conc-frz-01",
                            new WithdrawalRequestDto(aliceAccount.getId(), new BigDecimal("100.0000"), "INR", "Cash"));
                    withdrawalSucceeded.set(true);
                } catch (Exception ignored) {
                } finally {
                    SecurityContextHolder.clearContext();
                    doneLatch.countDown();
                }
            });

            startLatch.countDown();
            assertThat(doneLatch.await(10, TimeUnit.SECONDS)).isTrue();
            executor.shutdown();

            Account reloaded = accountRepository.findById(aliceAccount.getId()).orElseThrow();
            assertThat(freezeSucceeded.get()).isTrue();
            assertThat(reloaded.getStatus()).isEqualTo(AccountStatus.FROZEN);

            if (withdrawalSucceeded.get()) {
                assertThat(reloaded.getBalance()).isEqualByComparingTo("200.0000");
            } else {
                assertThat(reloaded.getBalance()).isEqualByComparingTo("300.0000");
            }
        }

        @Test
        @DisplayName("37. Concurrent close + transfer source serializes safely")
        void concurrentCloseAndTransferSource() throws InterruptedException {
            fundAccount(aliceUser, aliceAccount, new BigDecimal("100.0000"));

            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(2);

            AtomicBoolean closeSucceeded = new AtomicBoolean(false);
            AtomicBoolean transferSucceeded = new AtomicBoolean(false);

            executor.submit(() -> {
                try {
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken("alice@ledger.com", null, Collections.emptyList())
                    );
                    startLatch.await();
                    accountService.closeAccount(aliceAccount.getId());
                    closeSucceeded.set(true);
                } catch (Exception ignored) {
                } finally {
                    SecurityContextHolder.clearContext();
                    doneLatch.countDown();
                }
            });

            executor.submit(() -> {
                try {
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken("alice@ledger.com", null, Collections.emptyList())
                    );
                    startLatch.await();
                    transferService.executeTransfer("tx-conc-cls-src-01",
                            new TransferRequestDto(aliceAccount.getId(), bobAccount.getId(), new BigDecimal("100.0000"), "INR"));
                    transferSucceeded.set(true);
                } catch (Exception ignored) {
                } finally {
                    SecurityContextHolder.clearContext();
                    doneLatch.countDown();
                }
            });

            startLatch.countDown();
            assertThat(doneLatch.await(10, TimeUnit.SECONDS)).isTrue();
            executor.shutdown();

            Account reloaded = accountRepository.findById(aliceAccount.getId()).orElseThrow();
            // Either transfer runs first (leaving balance 0) and close succeeds,
            // OR close runs first (fails due to balance 100) and transfer succeeds.
            if (closeSucceeded.get()) {
                assertThat(transferSucceeded.get()).isTrue();
                assertThat(reloaded.getStatus()).isEqualTo(AccountStatus.CLOSED);
                assertThat(reloaded.getBalance()).isEqualByComparingTo("0.0000");
            } else {
                assertThat(transferSucceeded.get()).isTrue();
                assertThat(reloaded.getStatus()).isEqualTo(AccountStatus.ACTIVE);
                assertThat(reloaded.getBalance()).isEqualByComparingTo("0.0000");
            }
        }

        @Test
        @DisplayName("38. Concurrent close + transfer destination serializes safely")
        void concurrentCloseAndTransferDestination() throws InterruptedException {
            fundAccount(bobUser, bobAccount, new BigDecimal("100.0000"));

            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(2);

            AtomicBoolean closeSucceeded = new AtomicBoolean(false);
            AtomicBoolean transferSucceeded = new AtomicBoolean(false);

            executor.submit(() -> {
                try {
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken("alice@ledger.com", null, Collections.emptyList())
                    );
                    startLatch.await();
                    accountService.closeAccount(aliceAccount.getId());
                    closeSucceeded.set(true);
                } catch (Exception ignored) {
                } finally {
                    SecurityContextHolder.clearContext();
                    doneLatch.countDown();
                }
            });

            executor.submit(() -> {
                try {
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken("bob@ledger.com", null, Collections.emptyList())
                    );
                    startLatch.await();
                    transferService.executeTransfer("tx-conc-cls-dst-01",
                            new TransferRequestDto(bobAccount.getId(), aliceAccount.getId(), new BigDecimal("50.0000"), "INR"));
                    transferSucceeded.set(true);
                } catch (Exception ignored) {
                } finally {
                    SecurityContextHolder.clearContext();
                    doneLatch.countDown();
                }
            });

            startLatch.countDown();
            assertThat(doneLatch.await(10, TimeUnit.SECONDS)).isTrue();
            executor.shutdown();

            Account reloaded = accountRepository.findById(aliceAccount.getId()).orElseThrow();
            // Exactly one must succeed:
            // If close won: transfer rejected because destination is CLOSED (balance = 0, status = CLOSED).
            // If transfer won: transfer completes (balance = 50), close rejected because balance > 0 (status = ACTIVE).
            assertThat(closeSucceeded.get() ^ transferSucceeded.get()).isTrue();
            if (closeSucceeded.get()) {
                assertThat(reloaded.getStatus()).isEqualTo(AccountStatus.CLOSED);
                assertThat(reloaded.getBalance()).isEqualByComparingTo("0.0000");
            } else {
                assertThat(reloaded.getStatus()).isEqualTo(AccountStatus.ACTIVE);
                assertThat(reloaded.getBalance()).isEqualByComparingTo("50.0000");
            }
        }

        @Test
        @DisplayName("39. Concurrent close + deposit serializes safely")
        void concurrentCloseAndDeposit() throws InterruptedException {
            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(2);

            AtomicBoolean closeSucceeded = new AtomicBoolean(false);
            AtomicBoolean depositSucceeded = new AtomicBoolean(false);

            executor.submit(() -> {
                try {
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken("alice@ledger.com", null, Collections.emptyList())
                    );
                    startLatch.await();
                    accountService.closeAccount(aliceAccount.getId());
                    closeSucceeded.set(true);
                } catch (Exception ignored) {
                } finally {
                    SecurityContextHolder.clearContext();
                    doneLatch.countDown();
                }
            });

            executor.submit(() -> {
                try {
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken("alice@ledger.com", null, Collections.emptyList())
                    );
                    startLatch.await();
                    depositService.executeDeposit("dep-conc-cls-01",
                            new DepositRequestDto(aliceAccount.getId(), new BigDecimal("100.0000"), "INR", "Dep"));
                    depositSucceeded.set(true);
                } catch (Exception ignored) {
                } finally {
                    SecurityContextHolder.clearContext();
                    doneLatch.countDown();
                }
            });

            startLatch.countDown();
            assertThat(doneLatch.await(10, TimeUnit.SECONDS)).isTrue();
            executor.shutdown();

            Account reloaded = accountRepository.findById(aliceAccount.getId()).orElseThrow();
            // Exactly one succeeds:
            assertThat(closeSucceeded.get() ^ depositSucceeded.get()).isTrue();
            if (closeSucceeded.get()) {
                assertThat(reloaded.getStatus()).isEqualTo(AccountStatus.CLOSED);
                assertThat(reloaded.getBalance()).isEqualByComparingTo("0.0000");
            } else {
                assertThat(reloaded.getStatus()).isEqualTo(AccountStatus.ACTIVE);
                assertThat(reloaded.getBalance()).isEqualByComparingTo("100.0000");
            }
        }

        @Test
        @DisplayName("40. Concurrent close + withdrawal exact balance serializes into consistent state")
        void concurrentCloseAndWithdrawalExactBalance() throws InterruptedException {
            fundAccount(aliceUser, aliceAccount, new BigDecimal("100.0000"));

            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(2);

            AtomicBoolean closeSucceeded = new AtomicBoolean(false);
            AtomicBoolean withdrawalSucceeded = new AtomicBoolean(false);

            executor.submit(() -> {
                try {
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken("alice@ledger.com", null, Collections.emptyList())
                    );
                    startLatch.await();
                    accountService.closeAccount(aliceAccount.getId());
                    closeSucceeded.set(true);
                } catch (Exception ignored) {
                } finally {
                    SecurityContextHolder.clearContext();
                    doneLatch.countDown();
                }
            });

            executor.submit(() -> {
                try {
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken("alice@ledger.com", null, Collections.emptyList())
                    );
                    startLatch.await();
                    withdrawalService.executeWithdrawal("wdr-conc-exact-01",
                            new WithdrawalRequestDto(aliceAccount.getId(), new BigDecimal("100.0000"), "INR", "Empty"));
                    withdrawalSucceeded.set(true);
                } catch (Exception ignored) {
                } finally {
                    SecurityContextHolder.clearContext();
                    doneLatch.countDown();
                }
            });

            startLatch.countDown();
            assertThat(doneLatch.await(10, TimeUnit.SECONDS)).isTrue();
            executor.shutdown();

            Account reloaded = accountRepository.findById(aliceAccount.getId()).orElseThrow();
            // Outcome 1: Withdrawal runs first -> balance 0, then close runs -> both succeed! Status is CLOSED, balance 0.
            // Outcome 2: Close runs first -> fails because balance is 100, then withdrawal succeeds. Status is ACTIVE, balance 0.
            assertThat(withdrawalSucceeded.get()).isTrue();
            assertThat(reloaded.getBalance()).isEqualByComparingTo("0.0000");
            if (closeSucceeded.get()) {
                assertThat(reloaded.getStatus()).isEqualTo(AccountStatus.CLOSED);
            } else {
                assertThat(reloaded.getStatus()).isEqualTo(AccountStatus.ACTIVE);
            }
        }

        @Test
        @DisplayName("41. Concurrent freeze + unfreeze serializes without deadlock")
        void concurrentFreezeAndUnfreeze() throws InterruptedException {
            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(2);

            AtomicInteger freezeCount = new AtomicInteger(0);
            AtomicInteger unfreezeCount = new AtomicInteger(0);

            executor.submit(() -> {
                try {
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken("admin@ledger.com", null,
                                    List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))
                    );
                    startLatch.await();
                    accountService.freezeAccount(aliceAccount.getId());
                    freezeCount.incrementAndGet();
                } catch (Exception ignored) {
                } finally {
                    SecurityContextHolder.clearContext();
                    doneLatch.countDown();
                }
            });

            executor.submit(() -> {
                try {
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken("admin@ledger.com", null,
                                    List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))
                    );
                    startLatch.await();
                    accountService.unfreezeAccount(aliceAccount.getId());
                    unfreezeCount.incrementAndGet();
                } catch (Exception ignored) {
                } finally {
                    SecurityContextHolder.clearContext();
                    doneLatch.countDown();
                }
            });

            startLatch.countDown();
            assertThat(doneLatch.await(10, TimeUnit.SECONDS)).isTrue();
            executor.shutdown();

            Account reloaded = accountRepository.findById(aliceAccount.getId()).orElseThrow();
            assertThat(reloaded.getStatus()).isIn(AccountStatus.ACTIVE, AccountStatus.FROZEN);
        }

        @Test
        @DisplayName("42. Concurrent close + freeze serializes safely")
        void concurrentCloseAndFreeze() throws InterruptedException {
            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(2);

            AtomicBoolean closeSucceeded = new AtomicBoolean(false);
            AtomicBoolean freezeSucceeded = new AtomicBoolean(false);

            executor.submit(() -> {
                try {
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken("alice@ledger.com", null, Collections.emptyList())
                    );
                    startLatch.await();
                    accountService.closeAccount(aliceAccount.getId());
                    closeSucceeded.set(true);
                } catch (Exception ignored) {
                } finally {
                    SecurityContextHolder.clearContext();
                    doneLatch.countDown();
                }
            });

            executor.submit(() -> {
                try {
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken("admin@ledger.com", null,
                                    List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))
                    );
                    startLatch.await();
                    accountService.freezeAccount(aliceAccount.getId());
                    freezeSucceeded.set(true);
                } catch (Exception ignored) {
                } finally {
                    SecurityContextHolder.clearContext();
                    doneLatch.countDown();
                }
            });

            startLatch.countDown();
            assertThat(doneLatch.await(10, TimeUnit.SECONDS)).isTrue();
            executor.shutdown();

            Account reloaded = accountRepository.findById(aliceAccount.getId()).orElseThrow();
            // If close won: status is CLOSED, freeze fails.
            // If freeze won: status became FROZEN, then close succeeded (balance 0) -> status is CLOSED.
            assertThat(reloaded.getStatus()).isEqualTo(AccountStatus.CLOSED);
        }
    }

    // =========================================================================
    // 7. DATABASE INTEGRITY CONSTRAINTS & TRIGGERS (43–47)
    // =========================================================================

    @Nested
    @DisplayName("7. PostgreSQL Database Integrity Constraints and Triggers")
    class DatabaseIntegrityTests {

        @Test
        @DisplayName("43. DB rejects CLOSED account with non-zero balance")
        void databaseRejectsClosedAccountWithNonZeroBalance() {
            UUID testId = UUID.randomUUID();
            assertThatThrownBy(() -> jdbcTemplate.update(
                    "INSERT INTO accounts (id, user_id, currency, balance, version, created_at, updated_at, account_type, status, account_number) " +
                            "VALUES (?, ?, 'INR', 100.0000, 0, NOW(), NOW(), 'USER_CHECKING', 'CLOSED', ?)",
                    testId, aliceUser.getId(), "ACCT-DB-CHK-01"
            )).isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("chk_accounts_closed_zero_balance");
        }

        @Test
        @DisplayName("44. DB rejects CLOSED → ACTIVE reopening via trigger")
        void databaseRejectsClosedToActiveReopening() {
            aliceAccount.setStatus(AccountStatus.CLOSED);
            accountRepository.save(aliceAccount);

            assertThatThrownBy(() -> jdbcTemplate.update(
                    "UPDATE accounts SET status = 'ACTIVE' WHERE id = ?",
                    aliceAccount.getId()
            )).isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("is terminal and cannot be reopened");
        }

        @Test
        @DisplayName("45. DB rejects CLOSED → FROZEN via trigger")
        void databaseRejectsClosedToFrozen() {
            aliceAccount.setStatus(AccountStatus.CLOSED);
            accountRepository.save(aliceAccount);

            assertThatThrownBy(() -> jdbcTemplate.update(
                    "UPDATE accounts SET status = 'FROZEN' WHERE id = ?",
                    aliceAccount.getId()
            )).isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("is terminal and cannot be reopened");
        }

        @Test
        @DisplayName("46. DB rejects balance mutation on CLOSED account via trigger")
        void databaseRejectsBalanceMutationOnClosedAccount() {
            aliceAccount.setStatus(AccountStatus.CLOSED);
            accountRepository.save(aliceAccount);

            assertThatThrownBy(() -> jdbcTemplate.update(
                    "UPDATE accounts SET balance = 50.0000 WHERE id = ?",
                    aliceAccount.getId()
            )).isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("Cannot modify balance of closed account");
        }

        @Test
        @DisplayName("47. DB rejects SYSTEM_CLEARING lifecycle mutation via trigger")
        void databaseRejectsSystemClearingLifecycleMutation() {
            assertThatThrownBy(() -> jdbcTemplate.update(
                    "UPDATE accounts SET status = 'FROZEN' WHERE id = ?",
                    CLEARING_ID
            )).isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("cannot transition away from ACTIVE");
        }
    }
}
