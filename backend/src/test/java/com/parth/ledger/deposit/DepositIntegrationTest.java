package com.parth.ledger.deposit;

import com.parth.ledger.BaseIntegrationTest;
import com.parth.ledger.account.Account;
import com.parth.ledger.account.AccountClosedException;
import com.parth.ledger.account.AccountFrozenException;
import com.parth.ledger.account.AccountNotFoundException;
import com.parth.ledger.account.AccountRepository;
import com.parth.ledger.account.AccountStatus;
import com.parth.ledger.account.AccountType;
import com.parth.ledger.account.InvalidAccountTypeException;
import com.parth.ledger.deposit.dto.DepositRequestDto;
import com.parth.ledger.deposit.dto.DepositResult;
import com.parth.ledger.deposit.service.DepositService;
import com.parth.ledger.idempotency.IdempotencyCacheService;
import com.parth.ledger.ledger.LedgerEntry;
import com.parth.ledger.ledger.LedgerEntryRepository;
import com.parth.ledger.ledger.LedgerEntryType;
import com.parth.ledger.reconciliation.ReconciliationStatus;
import com.parth.ledger.reconciliation.dto.ReconciliationResultDto;
import com.parth.ledger.reconciliation.service.ReconciliationService;
import com.parth.ledger.transaction.Transaction;
import com.parth.ledger.transaction.TransactionRepository;
import com.parth.ledger.transaction.TransactionStatus;
import com.parth.ledger.transaction.TransactionType;
import com.parth.ledger.transaction.dto.TransactionResponseDto;
import com.parth.ledger.transaction.exception.CurrencyMismatchException;
import com.parth.ledger.transaction.exception.IdempotencyConflictException;
import com.parth.ledger.transaction.exception.InsufficientBalanceException;
import com.parth.ledger.transaction.exception.InvalidAmountException;
import com.parth.ledger.user.User;
import com.parth.ledger.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

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
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class DepositIntegrationTest extends BaseIntegrationTest {

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

    @MockitoSpyBean
    private LedgerEntryRepository ledgerEntryRepository;

    @MockitoSpyBean
    private IdempotencyCacheService idempotencyCacheService;

    @Autowired
    private DepositService depositService;

    @Autowired
    private ReconciliationService reconciliationService;

    private User aliceUser;
    private User bobUser;
    private Account aliceAccount;
    private Account bobAccount;
    private Account clearingAccount;

    private static final BigDecimal INITIAL_CLEARING_BALANCE = new BigDecimal("10000.0000");

    @BeforeEach
    void setUp() {
        clearRedis();
        ledgerEntryRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();

        reset(ledgerEntryRepository);
        reset(idempotencyCacheService);

        aliceUser = userRepository.save(new User("alice.deposit@ledger.com", "Alice"));
        bobUser = userRepository.save(new User("bob.deposit@ledger.com", "Bob"));

        aliceAccount = accountRepository.save(new Account(aliceUser, "USD", BigDecimal.ZERO.setScale(4)));
        bobAccount = accountRepository.save(new Account(bobUser, "USD", BigDecimal.ZERO.setScale(4)));

        clearingAccount = ensureSystemClearingAccount(INITIAL_CLEARING_BALANCE);
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

    private Account ensureSystemClearingAccount(BigDecimal initialBalance) {
        jdbcTemplate.update(
                "INSERT INTO accounts (id, user_id, currency, balance, version, created_at, updated_at, account_type, status, account_number) " +
                        "VALUES ('00000000-0000-0000-0000-000000000001', NULL, 'USD', ?, 0, NOW(), NOW(), 'SYSTEM_CLEARING', 'ACTIVE', 'ACCT-SYSTEM-CLEARING-01') " +
                        "ON CONFLICT (id) DO UPDATE SET balance = EXCLUDED.balance, status = 'ACTIVE'",
                initialBalance
        );
        return accountRepository.findById(DepositService.SYSTEM_CLEARING_ACCOUNT_ID).orElseThrow();
    }

    private <T> T executeAsUser(String email, Supplier<T> action) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(email, null, Collections.emptyList())
        );
        try {
            return action.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    // =========================================================================
    // 1. BASIC TESTS (1–12)
    // =========================================================================
    @Nested
    @DisplayName("1. Basic Deposit Operation Tests")
    class BasicTests {

        @Test
        @DisplayName("1. Authenticated user can deposit")
        void authenticatedUserCanDeposit() throws Exception {
            DepositRequestDto request = new DepositRequestDto(
                    aliceAccount.getId(),
                    new BigDecimal("100.0000"),
                    "USD",
                    "Initial funding"
            );

            mockMvc.perform(post("/api/v1/deposits")
                            .with(user("alice.deposit@ledger.com"))
                            .header("Idempotency-Key", "dep-basic-001")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.transactionId", notNullValue()))
                    .andExpect(jsonPath("$.status", is("COMPLETED")));
        }

        @Test
        @DisplayName("2. Transaction type is DEPOSIT")
        void transactionTypeIsDeposit() {
            DepositRequestDto request = new DepositRequestDto(
                    aliceAccount.getId(),
                    new BigDecimal("100.0000"),
                    "USD",
                    "Funding"
            );

            TransactionResponseDto response = executeAsUser("alice.deposit@ledger.com",
                    () -> depositService.executeDeposit("dep-type-002", request));

            assertThat(response.transactionType()).isEqualTo(TransactionType.DEPOSIT);

            Transaction dbTx = transactionRepository.findById(response.transactionId()).orElseThrow();
            assertThat(dbTx.getTransactionType()).isEqualTo(TransactionType.DEPOSIT);
        }

        @Test
        @DisplayName("3. Source is SYSTEM_CLEARING")
        void sourceIsSystemClearing() {
            DepositRequestDto request = new DepositRequestDto(
                    aliceAccount.getId(),
                    new BigDecimal("100.0000"),
                    "USD"
            );

            TransactionResponseDto response = executeAsUser("alice.deposit@ledger.com",
                    () -> depositService.executeDeposit("dep-src-003", request));

            assertThat(response.sourceAccountId()).isEqualTo(DepositService.SYSTEM_CLEARING_ACCOUNT_ID);

            Transaction dbTx = transactionRepository.findById(response.transactionId()).orElseThrow();
            assertThat(dbTx.getSourceAccount().getId()).isEqualTo(DepositService.SYSTEM_CLEARING_ACCOUNT_ID);
            Account sourceAcc = accountRepository.findById(response.sourceAccountId()).orElseThrow();
            assertThat(sourceAcc.getAccountType()).isEqualTo(AccountType.SYSTEM_CLEARING);
        }

        @Test
        @DisplayName("4. Destination is authenticated user's account")
        void destinationIsAuthenticatedUsersAccount() {
            DepositRequestDto request = new DepositRequestDto(
                    aliceAccount.getId(),
                    new BigDecimal("150.0000"),
                    "USD"
            );

            TransactionResponseDto response = executeAsUser("alice.deposit@ledger.com",
                    () -> depositService.executeDeposit("dep-dst-004", request));

            assertThat(response.destinationAccountId()).isEqualTo(aliceAccount.getId());
            assertThat(response.initiatedByUserId()).isEqualTo(aliceUser.getId());

            Transaction dbTx = transactionRepository.findById(response.transactionId()).orElseThrow();
            assertThat(dbTx.getDestinationAccount().getId()).isEqualTo(aliceAccount.getId());
            assertThat(dbTx.getInitiatedByUser().getId()).isEqualTo(aliceUser.getId());
        }

        @Test
        @DisplayName("5. User balance increases correctly")
        void userBalanceIncreasesCorrectly() {
            BigDecimal depositAmount = new BigDecimal("250.0000");
            DepositRequestDto request = new DepositRequestDto(aliceAccount.getId(), depositAmount, "USD");

            executeAsUser("alice.deposit@ledger.com", () -> depositService.executeDeposit("dep-bal-005", request));

            Account updatedAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
            assertThat(updatedAlice.getBalance()).isEqualByComparingTo(new BigDecimal("250.0000"));
        }

        @Test
        @DisplayName("6. Clearing balance decreases correctly")
        void clearingBalanceDecreasesCorrectly() {
            BigDecimal depositAmount = new BigDecimal("250.0000");
            DepositRequestDto request = new DepositRequestDto(aliceAccount.getId(), depositAmount, "USD");

            executeAsUser("alice.deposit@ledger.com", () -> depositService.executeDeposit("dep-clr-006", request));

            Account updatedClearing = accountRepository.findById(DepositService.SYSTEM_CLEARING_ACCOUNT_ID).orElseThrow();
            assertThat(updatedClearing.getBalance()).isEqualByComparingTo(INITIAL_CLEARING_BALANCE.subtract(depositAmount));
        }

        @Test
        @DisplayName("7. Transaction is COMPLETED")
        void transactionIsCompleted() {
            DepositRequestDto request = new DepositRequestDto(aliceAccount.getId(), new BigDecimal("100.0000"), "USD");

            TransactionResponseDto response = executeAsUser("alice.deposit@ledger.com",
                    () -> depositService.executeDeposit("dep-status-007", request));

            assertThat(response.status()).isEqualTo(TransactionStatus.COMPLETED);
            assertThat(response.completedAt()).isNotNull();

            Transaction dbTx = transactionRepository.findById(response.transactionId()).orElseThrow();
            assertThat(dbTx.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
            assertThat(dbTx.getCompletedAt()).isNotNull();
        }

        @Test
        @DisplayName("8. Exactly two ledger entries are created")
        void exactlyTwoLedgerEntriesCreated() {
            DepositRequestDto request = new DepositRequestDto(aliceAccount.getId(), new BigDecimal("100.0000"), "USD");

            TransactionResponseDto response = executeAsUser("alice.deposit@ledger.com",
                    () -> depositService.executeDeposit("dep-entries-008", request));

            List<LedgerEntry> entries = ledgerEntryRepository.findByTransactionId(response.transactionId());
            assertThat(entries).hasSize(2);
        }

        @Test
        @DisplayName("9. Debit equals credit")
        void debitEqualsCredit() {
            BigDecimal amount = new BigDecimal("175.5000");
            DepositRequestDto request = new DepositRequestDto(aliceAccount.getId(), amount, "USD");

            TransactionResponseDto response = executeAsUser("alice.deposit@ledger.com",
                    () -> depositService.executeDeposit("dep-balance-009", request));

            List<LedgerEntry> entries = ledgerEntryRepository.findByTransactionId(response.transactionId());

            LedgerEntry debitEntry = entries.stream()
                    .filter(e -> e.getEntryType() == LedgerEntryType.DEBIT)
                    .findFirst().orElseThrow();
            LedgerEntry creditEntry = entries.stream()
                    .filter(e -> e.getEntryType() == LedgerEntryType.CREDIT)
                    .findFirst().orElseThrow();

            assertThat(debitEntry.getAccount().getId()).isEqualTo(DepositService.SYSTEM_CLEARING_ACCOUNT_ID);
            assertThat(debitEntry.getAmount()).isEqualByComparingTo(amount);

            assertThat(creditEntry.getAccount().getId()).isEqualTo(aliceAccount.getId());
            assertThat(creditEntry.getAmount()).isEqualByComparingTo(amount);

            assertThat(debitEntry.getAmount()).isEqualByComparingTo(creditEntry.getAmount());
        }

        @Test
        @DisplayName("10. Currencies are correct")
        void currenciesAreCorrect() {
            DepositRequestDto request = new DepositRequestDto(aliceAccount.getId(), new BigDecimal("100.0000"), "USD");

            TransactionResponseDto response = executeAsUser("alice.deposit@ledger.com",
                    () -> depositService.executeDeposit("dep-curr-010", request));

            assertThat(response.currency()).isEqualTo("USD");

            List<LedgerEntry> entries = ledgerEntryRepository.findByTransactionId(response.transactionId());
            for (LedgerEntry entry : entries) {
                assertThat(entry.getCurrency()).isEqualTo("USD");
            }
        }

        @Test
        @DisplayName("11. Description persists")
        void descriptionPersists() {
            String desc = "Payroll deposit for September";
            DepositRequestDto request = new DepositRequestDto(aliceAccount.getId(), new BigDecimal("100.0000"), "USD", desc);

            TransactionResponseDto response = executeAsUser("alice.deposit@ledger.com",
                    () -> depositService.executeDeposit("dep-desc-011", request));

            assertThat(response.description()).isEqualTo(desc);

            Transaction dbTx = transactionRepository.findById(response.transactionId()).orElseThrow();
            assertThat(dbTx.getDescription()).isEqualTo(desc);
        }

        @Test
        @DisplayName("12. Blank description becomes null")
        void blankDescriptionBecomesNull() {
            DepositRequestDto request = new DepositRequestDto(aliceAccount.getId(), new BigDecimal("100.0000"), "USD", "   ");

            TransactionResponseDto response = executeAsUser("alice.deposit@ledger.com",
                    () -> depositService.executeDeposit("dep-desc-012", request));

            assertThat(response.description()).isNull();

            Transaction dbTx = transactionRepository.findById(response.transactionId()).orElseThrow();
            assertThat(dbTx.getDescription()).isNull();
        }
    }

    // =========================================================================
    // 2. VALIDATION TESTS (13–24)
    // =========================================================================
    @Nested
    @DisplayName("2. Validation Tests")
    class ValidationTests {

        @Test
        @DisplayName("13. Zero amount rejected")
        void zeroAmountRejected() throws Exception {
            String payload = String.format("{\"accountId\": \"%s\", \"amount\": 0.00, \"currency\": \"USD\"}",
                    aliceAccount.getId());

            mockMvc.perform(post("/api/v1/deposits")
                            .with(user("alice.deposit@ledger.com"))
                            .header("Idempotency-Key", "dep-val-013")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status", is(400)));

            assertThat(accountRepository.findById(aliceAccount.getId()).orElseThrow().getBalance())
                    .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(transactionRepository.count()).isZero();
        }

        @Test
        @DisplayName("14. Negative amount rejected")
        void negativeAmountRejected() throws Exception {
            String payload = String.format("{\"accountId\": \"%s\", \"amount\": -50.00, \"currency\": \"USD\"}",
                    aliceAccount.getId());

            mockMvc.perform(post("/api/v1/deposits")
                            .with(user("alice.deposit@ledger.com"))
                            .header("Idempotency-Key", "dep-val-014")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status", is(400)));

            assertThat(accountRepository.findById(aliceAccount.getId()).orElseThrow().getBalance())
                    .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(transactionRepository.count()).isZero();
        }

        @Test
        @DisplayName("15. Malformed amount rejected")
        void malformedAmountRejected() throws Exception {
            String payload = String.format("{\"accountId\": \"%s\", \"amount\": \"not-a-number\", \"currency\": \"USD\"}",
                    aliceAccount.getId());

            mockMvc.perform(post("/api/v1/deposits")
                            .with(user("alice.deposit@ledger.com"))
                            .header("Idempotency-Key", "dep-val-015")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status", is(400)));
        }

        @Test
        @DisplayName("16. Invalid currency rejected")
        void invalidCurrencyRejected() throws Exception {
            // Lowercase currency
            String payload1 = String.format("{\"accountId\": \"%s\", \"amount\": 100.00, \"currency\": \"usd\"}",
                    aliceAccount.getId());

            mockMvc.perform(post("/api/v1/deposits")
                            .with(user("alice.deposit@ledger.com"))
                            .header("Idempotency-Key", "dep-val-016a")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload1))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status", is(400)));

            // Too long currency
            String payload2 = String.format("{\"accountId\": \"%s\", \"amount\": 100.00, \"currency\": \"USDD\"}",
                    aliceAccount.getId());

            mockMvc.perform(post("/api/v1/deposits")
                            .with(user("alice.deposit@ledger.com"))
                            .header("Idempotency-Key", "dep-val-016b")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload2))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status", is(400)));
        }

        @Test
        @DisplayName("17. Currency mismatch rejected")
        void currencyMismatchRejected() throws Exception {
            // EUR account for Alice
            Account aliceEur = accountRepository.save(new Account(aliceUser, "EUR", BigDecimal.ZERO));

            // Request EUR for EUR destination -> mismatches USD SYSTEM_CLEARING
            DepositRequestDto requestEur = new DepositRequestDto(aliceEur.getId(), new BigDecimal("100.0000"), "EUR");

            mockMvc.perform(post("/api/v1/deposits")
                            .with(user("alice.deposit@ledger.com"))
                            .header("Idempotency-Key", "dep-val-017a")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(requestEur)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status", is(400)))
                    .andExpect(jsonPath("$.message", containsString("Currency mismatch")));

            // Request USD for EUR destination -> mismatches destination currency
            DepositRequestDto requestUsdOnEur = new DepositRequestDto(aliceEur.getId(), new BigDecimal("100.0000"), "USD");

            mockMvc.perform(post("/api/v1/deposits")
                            .with(user("alice.deposit@ledger.com"))
                            .header("Idempotency-Key", "dep-val-017b")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(requestUsdOnEur)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status", is(400)))
                    .andExpect(jsonPath("$.message", containsString("Currency mismatch")));
        }

        @Test
        @DisplayName("18. SYSTEM_CLEARING destination rejected")
        void systemClearingDestinationRejected() throws Exception {
            DepositRequestDto request = new DepositRequestDto(
                    DepositService.SYSTEM_CLEARING_ACCOUNT_ID,
                    new BigDecimal("100.0000"),
                    "USD"
            );

            mockMvc.perform(post("/api/v1/deposits")
                            .with(user("alice.deposit@ledger.com"))
                            .header("Idempotency-Key", "dep-val-018")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status", is(400)))
                    .andExpect(jsonPath("$.message", containsString("SYSTEM_CLEARING")));
        }

        @Test
        @DisplayName("19. FROZEN destination rejected")
        void frozenDestinationRejected() throws Exception {
            aliceAccount.setStatus(AccountStatus.FROZEN);
            accountRepository.save(aliceAccount);

            DepositRequestDto request = new DepositRequestDto(
                    aliceAccount.getId(),
                    new BigDecimal("100.0000"),
                    "USD"
            );

            mockMvc.perform(post("/api/v1/deposits")
                            .with(user("alice.deposit@ledger.com"))
                            .header("Idempotency-Key", "dep-val-019")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.status", is(422)))
                    .andExpect(jsonPath("$.message", containsString("FROZEN")));

            // Balance unchanged
            assertThat(accountRepository.findById(aliceAccount.getId()).orElseThrow().getBalance())
                    .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(transactionRepository.count()).isZero();
            assertThat(ledgerEntryRepository.count()).isZero();
        }

        @Test
        @DisplayName("20. CLOSED destination rejected")
        void closedDestinationRejected() throws Exception {
            aliceAccount.setStatus(AccountStatus.CLOSED);
            accountRepository.save(aliceAccount);

            DepositRequestDto request = new DepositRequestDto(
                    aliceAccount.getId(),
                    new BigDecimal("100.0000"),
                    "USD"
            );

            mockMvc.perform(post("/api/v1/deposits")
                            .with(user("alice.deposit@ledger.com"))
                            .header("Idempotency-Key", "dep-val-020")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.status", is(422)))
                    .andExpect(jsonPath("$.message", containsString("CLOSED")));

            // Balance unchanged
            assertThat(accountRepository.findById(aliceAccount.getId()).orElseThrow().getBalance())
                    .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(transactionRepository.count()).isZero();
            assertThat(ledgerEntryRepository.count()).isZero();
        }

        @Test
        @DisplayName("21. Another user's account rejected (404)")
        void anotherUsersAccountRejected() throws Exception {
            // Alice attempts to deposit into Bob's account
            DepositRequestDto request = new DepositRequestDto(
                    bobAccount.getId(),
                    new BigDecimal("100.0000"),
                    "USD"
            );

            mockMvc.perform(post("/api/v1/deposits")
                            .with(user("alice.deposit@ledger.com"))
                            .header("Idempotency-Key", "dep-val-021")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status", is(404)))
                    .andExpect(jsonPath("$.message", containsString("not found")));

            assertThat(accountRepository.findById(bobAccount.getId()).orElseThrow().getBalance())
                    .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(transactionRepository.count()).isZero();
        }

        @Test
        @DisplayName("22. Unauthenticated request rejected (401)")
        void unauthenticatedRequestRejected() throws Exception {
            DepositRequestDto request = new DepositRequestDto(
                    aliceAccount.getId(),
                    new BigDecimal("100.0000"),
                    "USD"
            );

            mockMvc.perform(post("/api/v1/deposits")
                            .header("Idempotency-Key", "dep-val-022")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("23. Missing Idempotency-Key rejected (400)")
        void missingIdempotencyKeyRejected() throws Exception {
            DepositRequestDto request = new DepositRequestDto(
                    aliceAccount.getId(),
                    new BigDecimal("100.0000"),
                    "USD"
            );

            mockMvc.perform(post("/api/v1/deposits")
                            .with(user("alice.deposit@ledger.com"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status", is(400)));
        }

        @Test
        @DisplayName("24. Missing accountId rejected (400)")
        void missingAccountIdRejected() throws Exception {
            String payload = "{\"amount\": 100.00, \"currency\": \"USD\"}";

            mockMvc.perform(post("/api/v1/deposits")
                            .with(user("alice.deposit@ledger.com"))
                            .header("Idempotency-Key", "dep-val-024")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status", is(400)))
                    .andExpect(jsonPath("$.message", containsString("Validation failed")));
        }
    }

    // =========================================================================
    // 3. CLEARING BALANCE TESTS (25–29)
    // =========================================================================
    @Nested
    @DisplayName("3. System Clearing Balance Tests")
    class ClearingBalanceTests {

        @Test
        @DisplayName("25. Insufficient clearing balance rejected")
        void insufficientClearingBalanceRejected() throws Exception {
            // Set clearing balance to only 50.0000
            ensureSystemClearingAccount(new BigDecimal("50.0000"));

            DepositRequestDto request = new DepositRequestDto(
                    aliceAccount.getId(),
                    new BigDecimal("100.0000"),
                    "USD"
            );

            mockMvc.perform(post("/api/v1/deposits")
                            .with(user("alice.deposit@ledger.com"))
                            .header("Idempotency-Key", "dep-clr-025")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.status", is(422)))
                    .andExpect(jsonPath("$.message", containsString("Insufficient balance")));
        }

        @Test
        @DisplayName("26. Clearing balance never becomes negative")
        void clearingBalanceNeverBecomesNegative() {
            ensureSystemClearingAccount(new BigDecimal("50.0000"));

            DepositRequestDto request = new DepositRequestDto(
                    aliceAccount.getId(),
                    new BigDecimal("100.0000"),
                    "USD"
            );

            assertThatThrownBy(() -> executeAsUser("alice.deposit@ledger.com",
                    () -> depositService.executeDeposit("dep-clr-026", request)))
                    .isInstanceOf(InsufficientBalanceException.class);

            Account checkClearing = accountRepository.findById(DepositService.SYSTEM_CLEARING_ACCOUNT_ID).orElseThrow();
            assertThat(checkClearing.getBalance()).isEqualByComparingTo(new BigDecimal("50.0000"));
            assertThat(checkClearing.getBalance()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("27. Rejected deposit causes no balance mutation")
        void rejectedDepositCausesNoBalanceMutation() {
            ensureSystemClearingAccount(new BigDecimal("25.0000"));

            DepositRequestDto request = new DepositRequestDto(
                    aliceAccount.getId(),
                    new BigDecimal("100.0000"),
                    "USD"
            );

            assertThatThrownBy(() -> executeAsUser("alice.deposit@ledger.com",
                    () -> depositService.executeDeposit("dep-clr-027", request)))
                    .isInstanceOf(InsufficientBalanceException.class);

            assertThat(accountRepository.findById(aliceAccount.getId()).orElseThrow().getBalance())
                    .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(accountRepository.findById(DepositService.SYSTEM_CLEARING_ACCOUNT_ID).orElseThrow().getBalance())
                    .isEqualByComparingTo(new BigDecimal("25.0000"));
        }

        @Test
        @DisplayName("28. Rejected deposit creates no financial transaction")
        void rejectedDepositCreatesNoTransaction() {
            ensureSystemClearingAccount(new BigDecimal("10.0000"));

            DepositRequestDto request = new DepositRequestDto(
                    aliceAccount.getId(),
                    new BigDecimal("100.0000"),
                    "USD"
            );

            assertThatThrownBy(() -> executeAsUser("alice.deposit@ledger.com",
                    () -> depositService.executeDeposit("dep-clr-028", request)))
                    .isInstanceOf(InsufficientBalanceException.class);

            assertThat(transactionRepository.findByIdempotencyKey("dep-clr-028")).isEmpty();
            assertThat(transactionRepository.count()).isZero();
        }

        @Test
        @DisplayName("29. Rejected deposit creates no ledger entries")
        void rejectedDepositCreatesNoLedgerEntries() {
            ensureSystemClearingAccount(new BigDecimal("10.0000"));

            DepositRequestDto request = new DepositRequestDto(
                    aliceAccount.getId(),
                    new BigDecimal("100.0000"),
                    "USD"
            );

            assertThatThrownBy(() -> executeAsUser("alice.deposit@ledger.com",
                    () -> depositService.executeDeposit("dep-clr-029", request)))
                    .isInstanceOf(InsufficientBalanceException.class);

            assertThat(ledgerEntryRepository.count()).isZero();
        }
    }

    // =========================================================================
    // 4. IDEMPOTENCY TESTS (30–37)
    // =========================================================================
    @Nested
    @DisplayName("4. Idempotency Tests")
    class IdempotencyTests {

        @Test
        @DisplayName("30. Identical retry returns same result (201 Created then 200 OK)")
        void identicalRetryReturnsSameResult() throws Exception {
            DepositRequestDto request = new DepositRequestDto(
                    aliceAccount.getId(),
                    new BigDecimal("100.0000"),
                    "USD",
                    "Idempotent funding"
            );

            // First request: 201 Created
            String firstResponse = mockMvc.perform(post("/api/v1/deposits")
                            .with(user("alice.deposit@ledger.com"))
                            .header("Idempotency-Key", "dep-idem-030")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();

            TransactionResponseDto dto1 = objectMapper.readValue(firstResponse, TransactionResponseDto.class);

            // Identical retry: 200 OK
            String secondResponse = mockMvc.perform(post("/api/v1/deposits")
                            .with(user("alice.deposit@ledger.com"))
                            .header("Idempotency-Key", "dep-idem-030")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            TransactionResponseDto dto2 = objectMapper.readValue(secondResponse, TransactionResponseDto.class);

            assertThat(dto1.transactionId()).isEqualTo(dto2.transactionId());
            assertThat(dto1.status()).isEqualTo(dto2.status());
            assertThat(dto1.amount()).isEqualByComparingTo(dto2.amount());
            assertThat(dto1.createdAt()).isEqualTo(dto2.createdAt());
        }

        @Test
        @DisplayName("31. Identical retry creates only one financial effect")
        void identicalRetryCreatesOnlyOneFinancialEffect() {
            DepositRequestDto request = new DepositRequestDto(
                    aliceAccount.getId(),
                    new BigDecimal("100.0000"),
                    "USD"
            );

            TransactionResponseDto r1 = executeAsUser("alice.deposit@ledger.com",
                    () -> depositService.executeDeposit("dep-idem-031", request));
            TransactionResponseDto r2 = executeAsUser("alice.deposit@ledger.com",
                    () -> depositService.executeDeposit("dep-idem-031", request));

            assertThat(r1.transactionId()).isEqualTo(r2.transactionId());

            // Balances mutated exactly once
            Account checkAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
            Account checkClearing = accountRepository.findById(DepositService.SYSTEM_CLEARING_ACCOUNT_ID).orElseThrow();
            assertThat(checkAlice.getBalance()).isEqualByComparingTo(new BigDecimal("100.0000"));
            assertThat(checkClearing.getBalance()).isEqualByComparingTo(INITIAL_CLEARING_BALANCE.subtract(new BigDecimal("100.0000")));

            // Exactly 1 transaction row and 2 ledger entries
            assertThat(transactionRepository.count()).isEqualTo(1);
            assertThat(ledgerEntryRepository.count()).isEqualTo(2);
        }

        @Test
        @DisplayName("32. Same key with different amount returns 409 Conflict")
        void sameKeyWithDifferentAmountReturns409() throws Exception {
            DepositRequestDto req1 = new DepositRequestDto(aliceAccount.getId(), new BigDecimal("100.0000"), "USD");
            DepositRequestDto req2 = new DepositRequestDto(aliceAccount.getId(), new BigDecimal("200.0000"), "USD");

            mockMvc.perform(post("/api/v1/deposits")
                            .with(user("alice.deposit@ledger.com"))
                            .header("Idempotency-Key", "dep-idem-032")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req1)))
                    .andExpect(status().isCreated());

            mockMvc.perform(post("/api/v1/deposits")
                            .with(user("alice.deposit@ledger.com"))
                            .header("Idempotency-Key", "dep-idem-032")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req2)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status", is(409)))
                    .andExpect(jsonPath("$.message", containsString("Idempotency key")));
        }

        @Test
        @DisplayName("33. Same key with different account returns 409 Conflict")
        void sameKeyWithDifferentAccountReturns409() throws Exception {
            Account aliceSecondAccount = accountRepository.save(new Account(aliceUser, "USD"));

            DepositRequestDto req1 = new DepositRequestDto(aliceAccount.getId(), new BigDecimal("100.0000"), "USD");
            DepositRequestDto req2 = new DepositRequestDto(aliceSecondAccount.getId(), new BigDecimal("100.0000"), "USD");

            mockMvc.perform(post("/api/v1/deposits")
                            .with(user("alice.deposit@ledger.com"))
                            .header("Idempotency-Key", "dep-idem-033")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req1)))
                    .andExpect(status().isCreated());

            mockMvc.perform(post("/api/v1/deposits")
                            .with(user("alice.deposit@ledger.com"))
                            .header("Idempotency-Key", "dep-idem-033")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req2)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status", is(409)))
                    .andExpect(jsonPath("$.message", containsString("Idempotency key")));
        }

        @Test
        @DisplayName("34. Same key with different currency returns 409 Conflict")
        void sameKeyWithDifferentCurrencyReturns409() {
            DepositRequestDto req1 = new DepositRequestDto(aliceAccount.getId(), new BigDecimal("100.0000"), "USD");

            executeAsUser("alice.deposit@ledger.com",
                    () -> depositService.executeDeposit("dep-idem-034", req1));

            // Now attempt with different currency (EUR) using same key
            DepositRequestDto req2 = new DepositRequestDto(aliceAccount.getId(), new BigDecimal("100.0000"), "EUR");

            assertThatThrownBy(() -> executeAsUser("alice.deposit@ledger.com",
                    () -> depositService.executeDeposit("dep-idem-034", req2)))
                    .isInstanceOf(IdempotencyConflictException.class);
        }

        @Test
        @DisplayName("35. Same key with different description returns 409 Conflict")
        void sameKeyWithDifferentDescriptionReturns409() throws Exception {
            DepositRequestDto req1 = new DepositRequestDto(aliceAccount.getId(), new BigDecimal("100.0000"), "USD", "Desc 1");
            DepositRequestDto req2 = new DepositRequestDto(aliceAccount.getId(), new BigDecimal("100.0000"), "USD", "Desc 2");

            mockMvc.perform(post("/api/v1/deposits")
                            .with(user("alice.deposit@ledger.com"))
                            .header("Idempotency-Key", "dep-idem-035")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req1)))
                    .andExpect(status().isCreated());

            mockMvc.perform(post("/api/v1/deposits")
                            .with(user("alice.deposit@ledger.com"))
                            .header("Idempotency-Key", "dep-idem-035")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req2)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status", is(409)));
        }

        @Test
        @DisplayName("36. Redis unavailable falls back to PostgreSQL")
        void redisUnavailableFallsBackToPostgres() {
            DepositRequestDto request = new DepositRequestDto(aliceAccount.getId(), new BigDecimal("100.0000"), "USD");

            TransactionResponseDto first = executeAsUser("alice.deposit@ledger.com",
                    () -> depositService.executeDeposit("dep-idem-036", request));

            // Flush Redis cache so fast-path misses
            clearRedis();

            // Retry succeeds via PostgreSQL authoritative check
            TransactionResponseDto fallback = executeAsUser("alice.deposit@ledger.com",
                    () -> depositService.executeDeposit("dep-idem-036", request));

            assertThat(fallback.transactionId()).isEqualTo(first.transactionId());
            assertThat(fallback.status()).isEqualTo(TransactionStatus.COMPLETED);
        }

        @Test
        @DisplayName("37. Redis failure after commit does not roll back deposit")
        void redisFailureAfterCommitDoesNotRollbackDeposit() {
            DepositRequestDto request = new DepositRequestDto(aliceAccount.getId(), new BigDecimal("100.0000"), "USD");

            // Mock Redis failure during post-commit caching
            doThrow(new RedisConnectionFailureException("Simulated Redis post-commit write failure"))
                    .when(idempotencyCacheService).set(anyString(), any(TransactionResponseDto.class));

            long initialTxCount = transactionRepository.count();

            TransactionResponseDto response = executeAsUser("alice.deposit@ledger.com",
                    () -> depositService.executeDeposit("dep-idem-037", request));

            assertThat(response).isNotNull();
            assertThat(response.status()).isEqualTo(TransactionStatus.COMPLETED);

            // DB transaction committed successfully
            Account checkAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
            assertThat(checkAlice.getBalance()).isEqualByComparingTo(new BigDecimal("100.0000"));
            assertThat(transactionRepository.count()).isEqualTo(initialTxCount + 1);
            assertThat(ledgerEntryRepository.count()).isEqualTo(2);

            // Retry via PostgreSQL fallback succeeds
            TransactionResponseDto retry = executeAsUser("alice.deposit@ledger.com",
                    () -> depositService.executeDeposit("dep-idem-037", request));
            assertThat(retry.transactionId()).isEqualTo(response.transactionId());
        }
    }

    // =========================================================================
    // 5. FAILURE / ROLLBACK TESTS (38–41)
    // =========================================================================
    @Nested
    @DisplayName("5. Failure and Rollback Tests")
    class RollbackTests {

        @Test
        @DisplayName("38. Failure during transaction rolls back balance changes")
        void failureDuringTransactionRollsBackBalances() {
            DepositRequestDto request = new DepositRequestDto(aliceAccount.getId(), new BigDecimal("100.0000"), "USD");

            // Throw exception during ledger entry persistence
            doThrow(new RuntimeException("Simulated database constraint/trigger failure on ledger insert"))
                    .when(ledgerEntryRepository).save(any(LedgerEntry.class));

            assertThatThrownBy(() -> executeAsUser("alice.deposit@ledger.com",
                    () -> depositService.executeDeposit("dep-fail-038", request)))
                    .isInstanceOf(RuntimeException.class);

            // Balances must remain completely unchanged
            Account checkAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
            Account checkClearing = accountRepository.findById(DepositService.SYSTEM_CLEARING_ACCOUNT_ID).orElseThrow();
            assertThat(checkAlice.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(checkClearing.getBalance()).isEqualByComparingTo(INITIAL_CLEARING_BALANCE);
        }

        @Test
        @DisplayName("39. Failure before ledger persistence rolls back")
        void failureBeforeLedgerPersistenceRollsBack() {
            DepositRequestDto request = new DepositRequestDto(aliceAccount.getId(), new BigDecimal("100.0000"), "USD");

            doThrow(new RuntimeException("Simulated failure before ledger entries"))
                    .when(ledgerEntryRepository).save(any(LedgerEntry.class));

            assertThatThrownBy(() -> executeAsUser("alice.deposit@ledger.com",
                    () -> depositService.executeDeposit("dep-fail-039", request)))
                    .isInstanceOf(RuntimeException.class);

            assertThat(transactionRepository.findByIdempotencyKey("dep-fail-039")).isEmpty();
            assertThat(ledgerEntryRepository.count()).isZero();
        }

        @Test
        @DisplayName("40. Failure after balance mutation rolls back")
        void failureAfterBalanceMutationRollsBack() {
            DepositRequestDto request = new DepositRequestDto(aliceAccount.getId(), new BigDecimal("100.0000"), "USD");

            doThrow(new RuntimeException("Simulated failure after balance update"))
                    .when(ledgerEntryRepository).save(any(LedgerEntry.class));

            assertThatThrownBy(() -> executeAsUser("alice.deposit@ledger.com",
                    () -> depositService.executeDeposit("dep-fail-040", request)))
                    .isInstanceOf(RuntimeException.class);

            Account checkAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
            assertThat(checkAlice.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("41. Retry after rollback succeeds once")
        void retryAfterRollbackSucceedsOnce() {
            DepositRequestDto request = new DepositRequestDto(aliceAccount.getId(), new BigDecimal("100.0000"), "USD");

            // First attempt fails
            doThrow(new RuntimeException("Transient failure"))
                    .when(ledgerEntryRepository).save(any(LedgerEntry.class));

            assertThatThrownBy(() -> executeAsUser("alice.deposit@ledger.com",
                    () -> depositService.executeDeposit("dep-retry-041", request)))
                    .isInstanceOf(RuntimeException.class);

            // Reset spy so retry can succeed
            reset(ledgerEntryRepository);

            // Second attempt with the exact same idempotency key succeeds
            TransactionResponseDto response = executeAsUser("alice.deposit@ledger.com",
                    () -> depositService.executeDeposit("dep-retry-041", request));

            assertThat(response).isNotNull();
            assertThat(response.status()).isEqualTo(TransactionStatus.COMPLETED);

            Account checkAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
            assertThat(checkAlice.getBalance()).isEqualByComparingTo(new BigDecimal("100.0000"));
            assertThat(transactionRepository.count()).isEqualTo(1);
            assertThat(ledgerEntryRepository.count()).isEqualTo(2);
        }
    }

    // =========================================================================
    // 6. CONCURRENCY TESTS (42–45)
    // =========================================================================
    @Nested
    @DisplayName("6. Concurrency Tests")
    class ConcurrencyTests {

        @Test
        @DisplayName("42. Concurrent deposits remain balanced")
        void concurrentDepositsRemainBalanced() throws Exception {
            int threadCount = 10;
            BigDecimal amountPerThread = new BigDecimal("50.0000");
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger();

            for (int i = 0; i < threadCount; i++) {
                final int idx = i;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        DepositRequestDto request = new DepositRequestDto(
                                aliceAccount.getId(),
                                amountPerThread,
                                "USD",
                                "Concurrent deposit " + idx
                        );
                        executeAsUser("alice.deposit@ledger.com",
                                () -> depositService.executeDeposit("dep-conc-42-" + idx, request));
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(completed).isTrue();
            assertThat(successCount.get()).isEqualTo(threadCount);

            BigDecimal expectedTotal = amountPerThread.multiply(BigDecimal.valueOf(threadCount));
            Account checkAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
            Account checkClearing = accountRepository.findById(DepositService.SYSTEM_CLEARING_ACCOUNT_ID).orElseThrow();

            assertThat(checkAlice.getBalance()).isEqualByComparingTo(expectedTotal);
            assertThat(checkClearing.getBalance()).isEqualByComparingTo(INITIAL_CLEARING_BALANCE.subtract(expectedTotal));

            assertThat(transactionRepository.count()).isEqualTo(threadCount);
            assertThat(ledgerEntryRepository.count()).isEqualTo(threadCount * 2L);
        }

        @Test
        @DisplayName("43. Concurrent deposits cannot overdraw SYSTEM_CLEARING")
        void concurrentDepositsCannotOverdrawSystemClearing() throws Exception {
            // Clearing has exactly 150.0000. 10 threads try to deposit 50.0000 each.
            // Exactly 3 must succeed, 7 must fail with InsufficientBalanceException.
            ensureSystemClearingAccount(new BigDecimal("150.0000"));

            int threadCount = 10;
            BigDecimal amountPerThread = new BigDecimal("50.0000");
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger();
            AtomicInteger failureCount = new AtomicInteger();

            for (int i = 0; i < threadCount; i++) {
                final int idx = i;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        DepositRequestDto request = new DepositRequestDto(
                                aliceAccount.getId(),
                                amountPerThread,
                                "USD"
                        );
                        executeAsUser("alice.deposit@ledger.com",
                                () -> depositService.executeDeposit("dep-conc-43-" + idx, request));
                        successCount.incrementAndGet();
                    } catch (InsufficientBalanceException e) {
                        failureCount.incrementAndGet();
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(completed).isTrue();
            assertThat(successCount.get()).isEqualTo(3);
            assertThat(failureCount.get()).isEqualTo(7);

            Account checkAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
            Account checkClearing = accountRepository.findById(DepositService.SYSTEM_CLEARING_ACCOUNT_ID).orElseThrow();

            assertThat(checkAlice.getBalance()).isEqualByComparingTo(new BigDecimal("150.0000"));
            assertThat(checkClearing.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(checkClearing.getBalance()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("44. Concurrent identical idempotency retries create one financial effect")
        void concurrentIdenticalIdempotencyRetriesCreateOneFinancialEffect() throws Exception {
            int threadCount = 10;
            String sharedKey = "dep-conc-idem-shared";
            BigDecimal depositAmount = new BigDecimal("100.0000");
            DepositRequestDto request = new DepositRequestDto(aliceAccount.getId(), depositAmount, "USD");

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            Set<UUID> returnedTxIds = ConcurrentHashMap.newKeySet();
            AtomicInteger successCount = new AtomicInteger();

            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        TransactionResponseDto response = executeAsUser("alice.deposit@ledger.com",
                                () -> depositService.executeDeposit(sharedKey, request));
                        returnedTxIds.add(response.transactionId());
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(completed).isTrue();
            assertThat(successCount.get()).isEqualTo(threadCount);
            assertThat(returnedTxIds).hasSize(1);

            Account checkAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
            Account checkClearing = accountRepository.findById(DepositService.SYSTEM_CLEARING_ACCOUNT_ID).orElseThrow();

            assertThat(checkAlice.getBalance()).isEqualByComparingTo(depositAmount);
            assertThat(checkClearing.getBalance()).isEqualByComparingTo(INITIAL_CLEARING_BALANCE.subtract(depositAmount));
            assertThat(transactionRepository.count()).isEqualTo(1);
            assertThat(ledgerEntryRepository.count()).isEqualTo(2);
        }

        @Test
        @DisplayName("45. Concurrent deposits reconcile correctly")
        void concurrentDepositsReconcileCorrectly() throws Exception {
            // Seed initial credit for clearing account so its ledger balance matches snapshot balance
            Transaction initTx = transactionRepository.save(new Transaction(
                    "init-clearing-recon-seed",
                    INITIAL_CLEARING_BALANCE,
                    "USD",
                    TransactionStatus.COMPLETED,
                    clearingAccount,
                    bobAccount,
                    TransactionType.DEPOSIT,
                    null,
                    "Initial platform clearing funding"
            ));
            ledgerEntryRepository.save(new LedgerEntry(initTx, clearingAccount, LedgerEntryType.CREDIT, INITIAL_CLEARING_BALANCE, "USD"));

            int threadCount = 8;
            BigDecimal amountPerThread = new BigDecimal("75.0000");
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);

            for (int i = 0; i < threadCount; i++) {
                final int idx = i;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        DepositRequestDto request = new DepositRequestDto(
                                aliceAccount.getId(),
                                amountPerThread,
                                "USD"
                        );
                        executeAsUser("alice.deposit@ledger.com",
                                () -> depositService.executeDeposit("dep-recon-conc-" + idx, request));
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
            executor.shutdown();
            assertThat(completed).isTrue();

            // Reconcile Alice account
            executeAsUser("alice.deposit@ledger.com", () -> {
                ReconciliationResultDto aliceRecon = reconciliationService.reconcileAccount(aliceAccount.getId());
                BigDecimal expectedAliceBalance = amountPerThread.multiply(BigDecimal.valueOf(threadCount));
                assertThat(aliceRecon.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
                assertThat(aliceRecon.difference()).isEqualByComparingTo(BigDecimal.ZERO);
                assertThat(aliceRecon.snapshotBalance()).isEqualByComparingTo(expectedAliceBalance);
                assertThat(aliceRecon.ledgerBalance()).isEqualByComparingTo(expectedAliceBalance);
                return null;
            });

            // Reconcile SYSTEM_CLEARING account directly
            ReconciliationResultDto clearingRecon = reconciliationService.reconcileAccountDirectly(DepositService.SYSTEM_CLEARING_ACCOUNT_ID);
            BigDecimal expectedClearingBalance = INITIAL_CLEARING_BALANCE.subtract(amountPerThread.multiply(BigDecimal.valueOf(threadCount)));
            assertThat(clearingRecon.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
            assertThat(clearingRecon.difference()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(clearingRecon.snapshotBalance()).isEqualByComparingTo(expectedClearingBalance);
            assertThat(clearingRecon.ledgerBalance()).isEqualByComparingTo(expectedClearingBalance);
        }
    }
}
