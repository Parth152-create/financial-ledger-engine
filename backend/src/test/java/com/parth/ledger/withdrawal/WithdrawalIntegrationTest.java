package com.parth.ledger.withdrawal;

import com.parth.ledger.BaseIntegrationTest;
import com.parth.ledger.account.Account;
import com.parth.ledger.account.AccountClosedException;
import com.parth.ledger.account.AccountFrozenException;
import com.parth.ledger.account.AccountNotFoundException;
import com.parth.ledger.account.AccountRepository;
import com.parth.ledger.account.AccountStatus;
import com.parth.ledger.account.AccountType;
import com.parth.ledger.deposit.dto.DepositRequestDto;
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
import com.parth.ledger.transaction.dto.TransferRequestDto;
import com.parth.ledger.transaction.exception.CurrencyMismatchException;
import com.parth.ledger.transaction.exception.IdempotencyConflictException;
import com.parth.ledger.transaction.exception.InsufficientBalanceException;
import com.parth.ledger.transaction.exception.InvalidAmountException;
import com.parth.ledger.transaction.service.TransferService;
import com.parth.ledger.user.User;
import com.parth.ledger.user.UserRepository;
import com.parth.ledger.withdrawal.dto.WithdrawalRequestDto;
import com.parth.ledger.withdrawal.dto.WithdrawalResult;
import com.parth.ledger.withdrawal.service.WithdrawalService;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class WithdrawalIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @MockitoSpyBean
    private TransactionRepository transactionRepository;

    @MockitoSpyBean
    private LedgerEntryRepository ledgerEntryRepository;

    @MockitoSpyBean
    private IdempotencyCacheService idempotencyCacheService;

    @Autowired
    private DepositService depositService;

    @Autowired
    private WithdrawalService withdrawalService;

    @Autowired
    private TransferService transferService;

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

        reset(transactionRepository);
        reset(ledgerEntryRepository);
        reset(idempotencyCacheService);

        aliceUser = userRepository.save(new User("alice.withdrawal@ledger.com", "Alice"));
        bobUser = userRepository.save(new User("bob.withdrawal@ledger.com", "Bob"));

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
        return accountRepository.findById(WithdrawalService.SYSTEM_CLEARING_ACCOUNT_ID).orElseThrow();
    }

    private void fundAccount(User user, Account account, BigDecimal amount) {
        executeAsUser(user.getEmail(), () -> {
            depositService.executeDeposit(
                    "fund-" + UUID.randomUUID(),
                    new DepositRequestDto(account.getId(), amount, account.getCurrency(), "Test funding")
            );
            return null;
        });
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
    // 1. BASIC & HAPPY PATH TESTS (1, 15, 18–22)
    // =========================================================================
    @Nested
    @DisplayName("1. Basic Withdrawal Operation Tests")
    class BasicTests {

        @Test
        @DisplayName("1. Valid withdrawal via API returns 201 Created")
        void validWithdrawalReturnsCreated() throws Exception {
            fundAccount(aliceUser, aliceAccount, new BigDecimal("500.0000"));

            WithdrawalRequestDto request = new WithdrawalRequestDto(
                    aliceAccount.getId(),
                    new BigDecimal("100.0000"),
                    "USD",
                    "ATM withdrawal"
            );

            mockMvc.perform(post("/api/v1/withdrawals")
                            .with(user("alice.withdrawal@ledger.com"))
                            .header("Idempotency-Key", "wdr-basic-001")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.transactionId", notNullValue()))
                    .andExpect(jsonPath("$.transactionType", is("WITHDRAWAL")))
                    .andExpect(jsonPath("$.status", is("COMPLETED")))
                    .andExpect(jsonPath("$.sourceAccountId", is(aliceAccount.getId().toString())))
                    .andExpect(jsonPath("$.destinationAccountId", is(WithdrawalService.SYSTEM_CLEARING_ACCOUNT_ID.toString())))
                    .andExpect(jsonPath("$.amount", is(100.0000)))
                    .andExpect(jsonPath("$.currency", is("USD")))
                    .andExpect(jsonPath("$.description", is("ATM withdrawal")));
        }

        @Test
        @DisplayName("2. Transaction entity and balance effect are correct")
        void transactionEntityAndBalancesCorrect() {
            fundAccount(aliceUser, aliceAccount, new BigDecimal("500.0000"));
            BigDecimal clearingBalanceBefore = accountRepository.findById(WithdrawalService.SYSTEM_CLEARING_ACCOUNT_ID).orElseThrow().getBalance();

            WithdrawalRequestDto request = new WithdrawalRequestDto(
                    aliceAccount.getId(),
                    new BigDecimal("200.0000"),
                    "USD",
                    "Payout"
            );

            TransactionResponseDto response = executeAsUser("alice.withdrawal@ledger.com",
                    () -> withdrawalService.executeWithdrawal("wdr-tx-002", request));

            assertThat(response.transactionType()).isEqualTo(TransactionType.WITHDRAWAL);
            assertThat(response.status()).isEqualTo(TransactionStatus.COMPLETED);
            assertThat(response.sourceAccountId()).isEqualTo(aliceAccount.getId());
            assertThat(response.destinationAccountId()).isEqualTo(WithdrawalService.SYSTEM_CLEARING_ACCOUNT_ID);
            assertThat(response.amount()).isEqualByComparingTo(new BigDecimal("200.0000"));

            Account updatedAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
            Account updatedClearing = accountRepository.findById(WithdrawalService.SYSTEM_CLEARING_ACCOUNT_ID).orElseThrow();

            assertThat(updatedAlice.getBalance()).isEqualByComparingTo(new BigDecimal("300.0000"));
            assertThat(updatedClearing.getBalance()).isEqualByComparingTo(clearingBalanceBefore.add(new BigDecimal("200.0000")));
        }

        @Test
        @DisplayName("3. Exact balance withdrawal succeeds and leaves zero balance")
        void exactBalanceWithdrawalSucceeds() {
            fundAccount(aliceUser, aliceAccount, new BigDecimal("100.0000"));

            WithdrawalRequestDto request = new WithdrawalRequestDto(
                    aliceAccount.getId(),
                    new BigDecimal("100.0000"),
                    "USD"
            );

            TransactionResponseDto response = executeAsUser("alice.withdrawal@ledger.com",
                    () -> withdrawalService.executeWithdrawal("wdr-exact-003", request));

            assertThat(response.status()).isEqualTo(TransactionStatus.COMPLETED);

            Account updatedAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
            assertThat(updatedAlice.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("4. Creates exactly two immutable ledger entries: DEBIT on user, CREDIT on clearing")
        void createsTwoLedgerEntriesDebitUserCreditClearing() {
            fundAccount(aliceUser, aliceAccount, new BigDecimal("250.0000"));

            WithdrawalRequestDto request = new WithdrawalRequestDto(
                    aliceAccount.getId(),
                    new BigDecimal("150.0000"),
                    "USD"
            );

            TransactionResponseDto response = executeAsUser("alice.withdrawal@ledger.com",
                    () -> withdrawalService.executeWithdrawal("wdr-ledger-004", request));

            List<LedgerEntry> entries = ledgerEntryRepository.findByTransactionId(response.transactionId());
            assertThat(entries).hasSize(2);

            LedgerEntry userEntry = entries.stream()
                    .filter(e -> e.getAccount().getId().equals(aliceAccount.getId()))
                    .findFirst().orElseThrow();
            assertThat(userEntry.getEntryType()).isEqualTo(LedgerEntryType.DEBIT);
            assertThat(userEntry.getAmount()).isEqualByComparingTo(new BigDecimal("150.0000"));
            assertThat(userEntry.getCurrency()).isEqualTo("USD");

            LedgerEntry clearingEntry = entries.stream()
                    .filter(e -> e.getAccount().getId().equals(WithdrawalService.SYSTEM_CLEARING_ACCOUNT_ID))
                    .findFirst().orElseThrow();
            assertThat(clearingEntry.getEntryType()).isEqualTo(LedgerEntryType.CREDIT);
            assertThat(clearingEntry.getAmount()).isEqualByComparingTo(new BigDecimal("150.0000"));
            assertThat(clearingEntry.getCurrency()).isEqualTo("USD");

            assertThat(userEntry.getAmount()).isEqualByComparingTo(clearingEntry.getAmount());
        }

        @Test
        @DisplayName("5. Double-entry ledger invariant: total debits == total credits")
        void doubleEntryLedgerIsBalanced() {
            fundAccount(aliceUser, aliceAccount, new BigDecimal("300.0000"));

            WithdrawalRequestDto request = new WithdrawalRequestDto(
                    aliceAccount.getId(),
                    new BigDecimal("120.0000"),
                    "USD"
            );

            TransactionResponseDto response = executeAsUser("alice.withdrawal@ledger.com",
                    () -> withdrawalService.executeWithdrawal("wdr-bal-005", request));

            List<LedgerEntry> entries = ledgerEntryRepository.findByTransactionId(response.transactionId());
            BigDecimal totalDebits = entries.stream()
                    .filter(e -> e.getEntryType() == LedgerEntryType.DEBIT)
                    .map(LedgerEntry::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal totalCredits = entries.stream()
                    .filter(e -> e.getEntryType() == LedgerEntryType.CREDIT)
                    .map(LedgerEntry::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            assertThat(totalDebits).isEqualByComparingTo(totalCredits);
            assertThat(totalDebits).isEqualByComparingTo(new BigDecimal("120.0000"));
        }

        @Test
        @DisplayName("6. Reconciliation remains consistent after withdrawal")
        void reconciliationRemainsConsistentAfterWithdrawal() {
            fundAccount(aliceUser, aliceAccount, new BigDecimal("400.0000"));

            WithdrawalRequestDto request = new WithdrawalRequestDto(
                    aliceAccount.getId(),
                    new BigDecimal("150.0000"),
                    "USD"
            );

            executeAsUser("alice.withdrawal@ledger.com",
                    () -> withdrawalService.executeWithdrawal("wdr-recon-006", request));

            executeAsUser("alice.withdrawal@ledger.com", () -> {
                ReconciliationResultDto result = reconciliationService.reconcileAccount(aliceAccount.getId());
                assertThat(result.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
                assertThat(result.difference()).isEqualByComparingTo(BigDecimal.ZERO);
                assertThat(result.snapshotBalance()).isEqualByComparingTo(new BigDecimal("250.0000"));
                assertThat(result.ledgerBalance()).isEqualByComparingTo(new BigDecimal("250.0000"));
                return null;
            });
        }
    }

    // =========================================================================
    // 2. VALIDATION TESTS (2–9)
    // =========================================================================
    @Nested
    @DisplayName("2. Validation Tests")
    class ValidationTests {

        @Test
        @DisplayName("7. Zero amount is rejected with 400 Bad Request")
        void zeroAmountIsRejected() throws Exception {
            WithdrawalRequestDto request = new WithdrawalRequestDto(
                    aliceAccount.getId(),
                    BigDecimal.ZERO,
                    "USD"
            );

            mockMvc.perform(post("/api/v1/withdrawals")
                            .with(user("alice.withdrawal@ledger.com"))
                            .header("Idempotency-Key", "wdr-val-007")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status", is(400)));
        }

        @Test
        @DisplayName("8. Negative amount is rejected with 400 Bad Request")
        void negativeAmountIsRejected() throws Exception {
            WithdrawalRequestDto request = new WithdrawalRequestDto(
                    aliceAccount.getId(),
                    new BigDecimal("-50.0000"),
                    "USD"
            );

            mockMvc.perform(post("/api/v1/withdrawals")
                            .with(user("alice.withdrawal@ledger.com"))
                            .header("Idempotency-Key", "wdr-val-008")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status", is(400)));
        }

        @Test
        @DisplayName("9. Excessive decimal scale (>4) is rejected with 400 Bad Request")
        void excessiveDecimalScaleIsRejected() throws Exception {
            WithdrawalRequestDto request = new WithdrawalRequestDto(
                    aliceAccount.getId(),
                    new BigDecimal("10.12345"),
                    "USD"
            );

            mockMvc.perform(post("/api/v1/withdrawals")
                            .with(user("alice.withdrawal@ledger.com"))
                            .header("Idempotency-Key", "wdr-val-009")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status", is(400)));
        }

        @Test
        @DisplayName("10. Malformed UUID is rejected with 400 Bad Request")
        void malformedUUIDIsRejected() throws Exception {
            String malformedJson = """
                {
                    "accountId": "not-a-valid-uuid",
                    "amount": 100.00,
                    "currency": "USD"
                }
            """;

            mockMvc.perform(post("/api/v1/withdrawals")
                            .with(user("alice.withdrawal@ledger.com"))
                            .header("Idempotency-Key", "wdr-val-010")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(malformedJson))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("11. Invalid currency format is rejected with 400 Bad Request")
        void invalidCurrencyFormatIsRejected() throws Exception {
            WithdrawalRequestDto request = new WithdrawalRequestDto(
                    aliceAccount.getId(),
                    new BigDecimal("100.0000"),
                    "US"
            );

            mockMvc.perform(post("/api/v1/withdrawals")
                            .with(user("alice.withdrawal@ledger.com"))
                            .header("Idempotency-Key", "wdr-val-011")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("12. Currency mismatch with account currency is rejected with 400 Bad Request")
        void currencyMismatchIsRejected() {
            fundAccount(aliceUser, aliceAccount, new BigDecimal("500.0000"));

            WithdrawalRequestDto request = new WithdrawalRequestDto(
                    aliceAccount.getId(),
                    new BigDecimal("100.0000"),
                    "EUR"
            );

            assertThatThrownBy(() -> executeAsUser("alice.withdrawal@ledger.com",
                    () -> withdrawalService.executeWithdrawal("wdr-val-012", request)))
                    .isInstanceOf(CurrencyMismatchException.class)
                    .hasMessageContaining("does not match");
        }

        @Test
        @DisplayName("13. Missing Idempotency-Key header is rejected with 400 Bad Request")
        void missingIdempotencyKeyIsRejected() throws Exception {
            WithdrawalRequestDto request = new WithdrawalRequestDto(
                    aliceAccount.getId(),
                    new BigDecimal("100.0000"),
                    "USD"
            );

            mockMvc.perform(post("/api/v1/withdrawals")
                            .with(user("alice.withdrawal@ledger.com"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("14. Blank Idempotency-Key header is rejected with 400 Bad Request")
        void blankIdempotencyKeyIsRejected() throws Exception {
            WithdrawalRequestDto request = new WithdrawalRequestDto(
                    aliceAccount.getId(),
                    new BigDecimal("100.0000"),
                    "USD"
            );

            mockMvc.perform(post("/api/v1/withdrawals")
                            .with(user("alice.withdrawal@ledger.com"))
                            .header("Idempotency-Key", "   ")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("15. Malformed JSON is rejected with 400 Bad Request")
        void malformedJsonIsRejected() throws Exception {
            mockMvc.perform(post("/api/v1/withdrawals")
                            .with(user("alice.withdrawal@ledger.com"))
                            .header("Idempotency-Key", "wdr-val-015")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{ invalid json }"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("16. Null accountId is rejected with 400 Bad Request")
        void nullAccountIdIsRejected() throws Exception {
            String json = """
                {
                    "amount": 100.00,
                    "currency": "USD"
                }
            """;

            mockMvc.perform(post("/api/v1/withdrawals")
                            .with(user("alice.withdrawal@ledger.com"))
                            .header("Idempotency-Key", "wdr-val-016")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json))
                    .andExpect(status().isBadRequest());
        }
    }

    // =========================================================================
    // 3. AUTHORIZATION & OWNERSHIP TESTS (10–12)
    // =========================================================================
    @Nested
    @DisplayName("3. Authorization & Ownership Tests")
    class AuthorizationTests {

        @Test
        @DisplayName("17. Unauthenticated request returns 401 Unauthorized")
        void unauthenticatedRequestReturnsUnauthorized() throws Exception {
            WithdrawalRequestDto request = new WithdrawalRequestDto(
                    aliceAccount.getId(),
                    new BigDecimal("100.0000"),
                    "USD"
            );

            mockMvc.perform(post("/api/v1/withdrawals")
                            .header("Idempotency-Key", "wdr-auth-017")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("18. Withdrawal from another user's account returns 404 Not Found")
        void withdrawalFromAnotherUserAccountReturns404() throws Exception {
            fundAccount(bobUser, bobAccount, new BigDecimal("500.0000"));

            WithdrawalRequestDto request = new WithdrawalRequestDto(
                    bobAccount.getId(),
                    new BigDecimal("100.0000"),
                    "USD"
            );

            mockMvc.perform(post("/api/v1/withdrawals")
                            .with(user("alice.withdrawal@ledger.com"))
                            .header("Idempotency-Key", "wdr-auth-018")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status", is(404)));
        }

        @Test
        @DisplayName("19. SYSTEM_CLEARING as source returns 404 Not Found")
        void systemClearingAsSourceReturns404() throws Exception {
            WithdrawalRequestDto request = new WithdrawalRequestDto(
                    WithdrawalService.SYSTEM_CLEARING_ACCOUNT_ID,
                    new BigDecimal("100.0000"),
                    "USD"
            );

            mockMvc.perform(post("/api/v1/withdrawals")
                            .with(user("alice.withdrawal@ledger.com"))
                            .header("Idempotency-Key", "wdr-auth-019")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status", is(404)));
        }

        @Test
        @DisplayName("20. Non-existent account returns 404 Not Found")
        void nonExistentAccountReturns404() throws Exception {
            WithdrawalRequestDto request = new WithdrawalRequestDto(
                    UUID.randomUUID(),
                    new BigDecimal("100.0000"),
                    "USD"
            );

            mockMvc.perform(post("/api/v1/withdrawals")
                            .with(user("alice.withdrawal@ledger.com"))
                            .header("Idempotency-Key", "wdr-auth-020")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound());
        }
    }

    // =========================================================================
    // 4. ACCOUNT LIFECYCLE TESTS (13–14)
    // =========================================================================
    @Nested
    @DisplayName("4. Account Lifecycle Tests")
    class LifecycleTests {

        @Test
        @DisplayName("21. Frozen account rejects withdrawal with 422 Unprocessable Entity")
        void frozenAccountRejectsWithdrawal() throws Exception {
            fundAccount(aliceUser, aliceAccount, new BigDecimal("500.0000"));
            Account reloaded = accountRepository.findById(aliceAccount.getId()).orElseThrow();
            reloaded.setStatus(AccountStatus.FROZEN);
            accountRepository.save(reloaded);

            WithdrawalRequestDto request = new WithdrawalRequestDto(
                    aliceAccount.getId(),
                    new BigDecimal("100.0000"),
                    "USD"
            );

            mockMvc.perform(post("/api/v1/withdrawals")
                            .with(user("alice.withdrawal@ledger.com"))
                            .header("Idempotency-Key", "wdr-life-021")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.status", is(422)))
                    .andExpect(jsonPath("$.message", containsString("FROZEN")));
        }

        @Test
        @DisplayName("22. Closed account rejects withdrawal with 422 Unprocessable Entity")
        void closedAccountRejectsWithdrawal() throws Exception {
            fundAccount(aliceUser, aliceAccount, new BigDecimal("500.0000"));
            Account reloaded = accountRepository.findById(aliceAccount.getId()).orElseThrow();
            reloaded.setStatus(AccountStatus.CLOSED);
            accountRepository.save(reloaded);

            WithdrawalRequestDto request = new WithdrawalRequestDto(
                    aliceAccount.getId(),
                    new BigDecimal("100.0000"),
                    "USD"
            );

            mockMvc.perform(post("/api/v1/withdrawals")
                            .with(user("alice.withdrawal@ledger.com"))
                            .header("Idempotency-Key", "wdr-life-022")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.status", is(422)))
                    .andExpect(jsonPath("$.message", containsString("CLOSED")));
        }
    }

    // =========================================================================
    // 5. BALANCE SUFFICIENCY TESTS (16–17)
    // =========================================================================
    @Nested
    @DisplayName("5. Balance Sufficiency Tests")
    class BalanceSufficiencyTests {

        @Test
        @DisplayName("23. Insufficient balance rejects withdrawal with 422")
        void insufficientBalanceRejectsWithdrawal() throws Exception {
            fundAccount(aliceUser, aliceAccount, new BigDecimal("50.0000"));

            WithdrawalRequestDto request = new WithdrawalRequestDto(
                    aliceAccount.getId(),
                    new BigDecimal("100.0000"),
                    "USD"
            );

            mockMvc.perform(post("/api/v1/withdrawals")
                            .with(user("alice.withdrawal@ledger.com"))
                            .header("Idempotency-Key", "wdr-bal-023")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.status", is(422)))
                    .andExpect(jsonPath("$.message", containsString("Insufficient balance")));
        }

        @Test
        @DisplayName("24. Insufficient balance causes zero balance mutation and no transaction persisted")
        void insufficientBalanceLeavesNoMutationOrTransaction() {
            fundAccount(aliceUser, aliceAccount, new BigDecimal("50.0000"));
            long txCountBefore = transactionRepository.count();
            long entryCountBefore = ledgerEntryRepository.count();

            WithdrawalRequestDto request = new WithdrawalRequestDto(
                    aliceAccount.getId(),
                    new BigDecimal("100.0000"),
                    "USD"
            );

            assertThatThrownBy(() -> executeAsUser("alice.withdrawal@ledger.com",
                    () -> withdrawalService.executeWithdrawal("wdr-bal-024", request)))
                    .isInstanceOf(InsufficientBalanceException.class);

            Account aliceCheck = accountRepository.findById(aliceAccount.getId()).orElseThrow();
            assertThat(aliceCheck.getBalance()).isEqualByComparingTo(new BigDecimal("50.0000"));
            assertThat(transactionRepository.count()).isEqualTo(txCountBefore);
            assertThat(ledgerEntryRepository.count()).isEqualTo(entryCountBefore);
        }

        @Test
        @DisplayName("25. Account balance never becomes negative")
        void balanceNeverBecomesNegative() {
            WithdrawalRequestDto request = new WithdrawalRequestDto(
                    aliceAccount.getId(),
                    new BigDecimal("0.0100"),
                    "USD"
            );

            assertThatThrownBy(() -> executeAsUser("alice.withdrawal@ledger.com",
                    () -> withdrawalService.executeWithdrawal("wdr-bal-025", request)))
                    .isInstanceOf(InsufficientBalanceException.class);

            Account aliceCheck = accountRepository.findById(aliceAccount.getId()).orElseThrow();
            assertThat(aliceCheck.getBalance()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
            assertThat(aliceCheck.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    // =========================================================================
    // 6. LEDGER IMMUTABILITY (23)
    // =========================================================================
    @Nested
    @DisplayName("6. Ledger Immutability Tests")
    class LedgerImmutabilityTests {

        @Test
        @DisplayName("26. Ledger entries are PostgreSQL-enforced immutable")
        void ledgerEntriesArePostgresImmutable() {
            fundAccount(aliceUser, aliceAccount, new BigDecimal("200.0000"));

            WithdrawalRequestDto request = new WithdrawalRequestDto(
                    aliceAccount.getId(),
                    new BigDecimal("100.0000"),
                    "USD"
            );

            TransactionResponseDto response = executeAsUser("alice.withdrawal@ledger.com",
                    () -> withdrawalService.executeWithdrawal("wdr-immut-026", request));

            List<LedgerEntry> entries = ledgerEntryRepository.findByTransactionId(response.transactionId());
            assertThat(entries).hasSize(2);
            UUID entryId = entries.get(0).getId();

            assertThatThrownBy(() -> jdbcTemplate.update("UPDATE ledger_entries SET amount = 999.0000 WHERE id = ?", entryId))
                    .hasMessageContaining("immutable");

            assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM ledger_entries WHERE id = ?", entryId))
                    .hasMessageContaining("immutable");
        }
    }

    // =========================================================================
    // 7. IDEMPOTENCY TESTS (24–31)
    // =========================================================================
    @Nested
    @DisplayName("7. Idempotency Tests")
    class IdempotencyTests {

        @Test
        @DisplayName("27. Identical retry returns HTTP 200 OK and same transaction")
        void identicalRetryReturns200AndSameTransaction() throws Exception {
            fundAccount(aliceUser, aliceAccount, new BigDecimal("500.0000"));

            WithdrawalRequestDto request = new WithdrawalRequestDto(
                    aliceAccount.getId(),
                    new BigDecimal("100.0000"),
                    "USD",
                    "ATM cash"
            );

            mockMvc.perform(post("/api/v1/withdrawals")
                            .with(user("alice.withdrawal@ledger.com"))
                            .header("Idempotency-Key", "wdr-idem-027")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.transactionId", notNullValue()));

            mockMvc.perform(post("/api/v1/withdrawals")
                            .with(user("alice.withdrawal@ledger.com"))
                            .header("Idempotency-Key", "wdr-idem-027")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.transactionId", notNullValue()));

            Account aliceCheck = accountRepository.findById(aliceAccount.getId()).orElseThrow();
            assertThat(aliceCheck.getBalance()).isEqualByComparingTo(new BigDecimal("400.0000"));
        }

        @Test
        @DisplayName("28. Same key with different amount returns 409 Conflict")
        void sameKeyDifferentAmountReturns409() throws Exception {
            fundAccount(aliceUser, aliceAccount, new BigDecimal("500.0000"));

            WithdrawalRequestDto req1 = new WithdrawalRequestDto(aliceAccount.getId(), new BigDecimal("100.0000"), "USD");
            WithdrawalRequestDto req2 = new WithdrawalRequestDto(aliceAccount.getId(), new BigDecimal("150.0000"), "USD");

            mockMvc.perform(post("/api/v1/withdrawals")
                            .with(user("alice.withdrawal@ledger.com"))
                            .header("Idempotency-Key", "wdr-idem-028")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req1)))
                    .andExpect(status().isCreated());

            mockMvc.perform(post("/api/v1/withdrawals")
                            .with(user("alice.withdrawal@ledger.com"))
                            .header("Idempotency-Key", "wdr-idem-028")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req2)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status", is(409)));
        }

        @Test
        @DisplayName("29. Same key with different account returns 409 Conflict")
        void sameKeyDifferentAccountReturns409() throws Exception {
            fundAccount(aliceUser, aliceAccount, new BigDecimal("500.0000"));
            Account aliceSecondAccount = accountRepository.save(new Account(aliceUser, "USD", BigDecimal.ZERO.setScale(4)));
            fundAccount(aliceUser, aliceSecondAccount, new BigDecimal("500.0000"));

            WithdrawalRequestDto req1 = new WithdrawalRequestDto(aliceAccount.getId(), new BigDecimal("100.0000"), "USD");
            WithdrawalRequestDto req2 = new WithdrawalRequestDto(aliceSecondAccount.getId(), new BigDecimal("100.0000"), "USD");

            mockMvc.perform(post("/api/v1/withdrawals")
                            .with(user("alice.withdrawal@ledger.com"))
                            .header("Idempotency-Key", "wdr-idem-029")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req1)))
                    .andExpect(status().isCreated());

            mockMvc.perform(post("/api/v1/withdrawals")
                            .with(user("alice.withdrawal@ledger.com"))
                            .header("Idempotency-Key", "wdr-idem-029")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req2)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status", is(409)));
        }

        @Test
        @DisplayName("30. Same key with different currency returns 409 Conflict")
        void sameKeyDifferentCurrencyReturns409() throws Exception {
            fundAccount(aliceUser, aliceAccount, new BigDecimal("500.0000"));

            WithdrawalRequestDto req1 = new WithdrawalRequestDto(aliceAccount.getId(), new BigDecimal("100.0000"), "USD");
            WithdrawalRequestDto req2 = new WithdrawalRequestDto(aliceAccount.getId(), new BigDecimal("100.0000"), "EUR");

            mockMvc.perform(post("/api/v1/withdrawals")
                            .with(user("alice.withdrawal@ledger.com"))
                            .header("Idempotency-Key", "wdr-idem-030")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req1)))
                    .andExpect(status().isCreated());

            mockMvc.perform(post("/api/v1/withdrawals")
                            .with(user("alice.withdrawal@ledger.com"))
                            .header("Idempotency-Key", "wdr-idem-030")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req2)))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("31. Same key with different description returns 409 Conflict")
        void sameKeyDifferentDescriptionReturns409() throws Exception {
            fundAccount(aliceUser, aliceAccount, new BigDecimal("500.0000"));

            WithdrawalRequestDto req1 = new WithdrawalRequestDto(aliceAccount.getId(), new BigDecimal("100.0000"), "USD", "Desc A");
            WithdrawalRequestDto req2 = new WithdrawalRequestDto(aliceAccount.getId(), new BigDecimal("100.0000"), "USD", "Desc B");

            mockMvc.perform(post("/api/v1/withdrawals")
                            .with(user("alice.withdrawal@ledger.com"))
                            .header("Idempotency-Key", "wdr-idem-031")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req1)))
                    .andExpect(status().isCreated());

            mockMvc.perform(post("/api/v1/withdrawals")
                            .with(user("alice.withdrawal@ledger.com"))
                            .header("Idempotency-Key", "wdr-idem-031")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req2)))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("32. Redis unavailable before transaction falls back to PostgreSQL")
        void redisUnavailableFallsBackToPostgres() {
            fundAccount(aliceUser, aliceAccount, new BigDecimal("500.0000"));

            doThrow(new RedisConnectionFailureException("Simulated Redis outage"))
                    .when(idempotencyCacheService).get(any(), any());

            WithdrawalRequestDto request = new WithdrawalRequestDto(
                    aliceAccount.getId(),
                    new BigDecimal("100.0000"),
                    "USD"
            );

            TransactionResponseDto response = executeAsUser("alice.withdrawal@ledger.com",
                    () -> withdrawalService.executeWithdrawal("wdr-idem-032", request));

            assertThat(response).isNotNull();
            assertThat(response.status()).isEqualTo(TransactionStatus.COMPLETED);

            Account checkAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
            assertThat(checkAlice.getBalance()).isEqualByComparingTo(new BigDecimal("400.0000"));
        }

        @Test
        @DisplayName("33. Redis failure after commit leaves withdrawal committed in PostgreSQL")
        void redisFailureAfterCommitLeavesWithdrawalCommitted() {
            fundAccount(aliceUser, aliceAccount, new BigDecimal("500.0000"));

            doThrow(new RedisConnectionFailureException("Simulated Redis write outage"))
                    .when(idempotencyCacheService).set(any(String.class), any(TransactionResponseDto.class));

            WithdrawalRequestDto request = new WithdrawalRequestDto(
                    aliceAccount.getId(),
                    new BigDecimal("100.0000"),
                    "USD"
            );

            TransactionResponseDto response = executeAsUser("alice.withdrawal@ledger.com",
                    () -> withdrawalService.executeWithdrawal("wdr-idem-033", request));

            assertThat(response).isNotNull();
            assertThat(response.status()).isEqualTo(TransactionStatus.COMPLETED);

            Account checkAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
            assertThat(checkAlice.getBalance()).isEqualByComparingTo(new BigDecimal("400.0000"));
            assertThat(transactionRepository.findByIdempotencyKey("wdr-idem-033")).isPresent();
        }

        @Test
        @DisplayName("34. Redis TTL expiry resolves safely via PostgreSQL")
        void redisTtlExpiryResolvesViaPostgres() {
            fundAccount(aliceUser, aliceAccount, new BigDecimal("500.0000"));

            WithdrawalRequestDto request = new WithdrawalRequestDto(
                    aliceAccount.getId(),
                    new BigDecimal("100.0000"),
                    "USD"
            );

            TransactionResponseDto initialResponse = executeAsUser("alice.withdrawal@ledger.com",
                    () -> withdrawalService.executeWithdrawal("wdr-idem-034", request));

            // Simulate Redis key expiration
            clearRedis();

            WithdrawalResult replayResult = executeAsUser("alice.withdrawal@ledger.com",
                    () -> withdrawalService.processWithdrawal("wdr-idem-034", request));

            assertThat(replayResult.replayed()).isTrue();
            assertThat(replayResult.response().transactionId()).isEqualTo(initialResponse.transactionId());

            Account checkAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
            assertThat(checkAlice.getBalance()).isEqualByComparingTo(new BigDecimal("400.0000"));
        }
    }

    // =========================================================================
    // 8. FAILURE & ROLLBACK TESTS (32–36)
    // =========================================================================
    @Nested
    @DisplayName("8. Failure & Rollback Tests")
    class RollbackTests {

        @Test
        @DisplayName("35. Transaction persistence failure rolls back balance changes")
        void transactionPersistenceFailureRollsBack() {
            fundAccount(aliceUser, aliceAccount, new BigDecimal("500.0000"));
            BigDecimal clearingBefore = accountRepository.findById(WithdrawalService.SYSTEM_CLEARING_ACCOUNT_ID).orElseThrow().getBalance();

            doThrow(new RuntimeException("Simulated database failure on transaction insert"))
                    .when(transactionRepository).save(any(Transaction.class));

            WithdrawalRequestDto request = new WithdrawalRequestDto(
                    aliceAccount.getId(),
                    new BigDecimal("100.0000"),
                    "USD"
            );

            assertThatThrownBy(() -> executeAsUser("alice.withdrawal@ledger.com",
                    () -> withdrawalService.executeWithdrawal("wdr-fail-035", request)))
                    .isInstanceOf(RuntimeException.class);

            Account checkAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
            Account checkClearing = accountRepository.findById(WithdrawalService.SYSTEM_CLEARING_ACCOUNT_ID).orElseThrow();

            assertThat(checkAlice.getBalance()).isEqualByComparingTo(new BigDecimal("500.0000"));
            assertThat(checkClearing.getBalance()).isEqualByComparingTo(clearingBefore);
            assertThat(transactionRepository.findByIdempotencyKey("wdr-fail-035")).isEmpty();
        }

        @Test
        @DisplayName("36. First ledger-entry failure rolls back entire transaction")
        void firstLedgerEntryFailureRollsBack() {
            fundAccount(aliceUser, aliceAccount, new BigDecimal("500.0000"));
            BigDecimal clearingBefore = accountRepository.findById(WithdrawalService.SYSTEM_CLEARING_ACCOUNT_ID).orElseThrow().getBalance();

            doThrow(new RuntimeException("Simulated failure on first ledger entry save"))
                    .when(ledgerEntryRepository).save(any(LedgerEntry.class));

            WithdrawalRequestDto request = new WithdrawalRequestDto(
                    aliceAccount.getId(),
                    new BigDecimal("100.0000"),
                    "USD"
            );

            assertThatThrownBy(() -> executeAsUser("alice.withdrawal@ledger.com",
                    () -> withdrawalService.executeWithdrawal("wdr-fail-036", request)))
                    .isInstanceOf(RuntimeException.class);

            Account checkAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
            Account checkClearing = accountRepository.findById(WithdrawalService.SYSTEM_CLEARING_ACCOUNT_ID).orElseThrow();

            assertThat(checkAlice.getBalance()).isEqualByComparingTo(new BigDecimal("500.0000"));
            assertThat(checkClearing.getBalance()).isEqualByComparingTo(clearingBefore);
            assertThat(transactionRepository.findByIdempotencyKey("wdr-fail-036")).isEmpty();
        }

        @Test
        @DisplayName("37. Second ledger-entry failure rolls back entire transaction")
        void secondLedgerEntryFailureRollsBack() {
            fundAccount(aliceUser, aliceAccount, new BigDecimal("500.0000"));
            BigDecimal clearingBefore = accountRepository.findById(WithdrawalService.SYSTEM_CLEARING_ACCOUNT_ID).orElseThrow().getBalance();

            AtomicInteger callCount = new AtomicInteger();
            doAnswer(invocation -> {
                if (callCount.incrementAndGet() >= 2) {
                    throw new RuntimeException("Simulated failure on second ledger entry save");
                }
                return invocation.callRealMethod();
            }).when(ledgerEntryRepository).save(any(LedgerEntry.class));

            WithdrawalRequestDto request = new WithdrawalRequestDto(
                    aliceAccount.getId(),
                    new BigDecimal("100.0000"),
                    "USD"
            );

            assertThatThrownBy(() -> executeAsUser("alice.withdrawal@ledger.com",
                    () -> withdrawalService.executeWithdrawal("wdr-fail-037", request)))
                    .isInstanceOf(RuntimeException.class);

            Account checkAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
            Account checkClearing = accountRepository.findById(WithdrawalService.SYSTEM_CLEARING_ACCOUNT_ID).orElseThrow();

            assertThat(checkAlice.getBalance()).isEqualByComparingTo(new BigDecimal("500.0000"));
            assertThat(checkClearing.getBalance()).isEqualByComparingTo(clearingBefore);
            assertThat(transactionRepository.findByIdempotencyKey("wdr-fail-037")).isEmpty();
        }

        @Test
        @DisplayName("38. Retry after rollback succeeds cleanly")
        void retryAfterRollbackSucceeds() {
            fundAccount(aliceUser, aliceAccount, new BigDecimal("500.0000"));

            doThrow(new RuntimeException("Transient failure"))
                    .when(ledgerEntryRepository).save(any(LedgerEntry.class));

            WithdrawalRequestDto request = new WithdrawalRequestDto(
                    aliceAccount.getId(),
                    new BigDecimal("100.0000"),
                    "USD"
            );

            assertThatThrownBy(() -> executeAsUser("alice.withdrawal@ledger.com",
                    () -> withdrawalService.executeWithdrawal("wdr-fail-038", request)))
                    .isInstanceOf(RuntimeException.class);

            reset(ledgerEntryRepository);

            TransactionResponseDto response = executeAsUser("alice.withdrawal@ledger.com",
                    () -> withdrawalService.executeWithdrawal("wdr-fail-038", request));

            assertThat(response).isNotNull();
            assertThat(response.status()).isEqualTo(TransactionStatus.COMPLETED);

            Account checkAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
            assertThat(checkAlice.getBalance()).isEqualByComparingTo(new BigDecimal("400.0000"));
        }

        @Test
        @DisplayName("38b. Failure after balance mutation rolls back all entity changes")
        void failureAfterBalanceMutationRollsBack() {
            fundAccount(aliceUser, aliceAccount, new BigDecimal("500.0000"));
            BigDecimal clearingBefore = accountRepository.findById(WithdrawalService.SYSTEM_CLEARING_ACCOUNT_ID).orElseThrow().getBalance();

            AtomicInteger txSaveCount = new AtomicInteger();
            doAnswer(invocation -> {
                if (txSaveCount.incrementAndGet() >= 2) {
                    throw new RuntimeException("Simulated failure during final transaction completion save");
                }
                return invocation.callRealMethod();
            }).when(transactionRepository).save(any(Transaction.class));

            WithdrawalRequestDto request = new WithdrawalRequestDto(
                    aliceAccount.getId(),
                    new BigDecimal("100.0000"),
                    "USD"
            );

            assertThatThrownBy(() -> executeAsUser("alice.withdrawal@ledger.com",
                    () -> withdrawalService.executeWithdrawal("wdr-fail-038b", request)))
                    .isInstanceOf(RuntimeException.class);

            Account checkAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
            Account checkClearing = accountRepository.findById(WithdrawalService.SYSTEM_CLEARING_ACCOUNT_ID).orElseThrow();

            assertThat(checkAlice.getBalance()).isEqualByComparingTo(new BigDecimal("500.0000"));
            assertThat(checkClearing.getBalance()).isEqualByComparingTo(clearingBefore);
            assertThat(transactionRepository.findByIdempotencyKey("wdr-fail-038b")).isEmpty();
        }
    }

    // =========================================================================
    // 9. CONCURRENCY TESTS (37–45)
    // =========================================================================
    @Nested
    @DisplayName("9. Concurrency Tests")
    class ConcurrencyTests {

        @Test
        @DisplayName("39. Concurrent withdrawals cannot overdraw account")
        void concurrentWithdrawalsCannotOverdrawAccount() throws Exception {
            fundAccount(aliceUser, aliceAccount, new BigDecimal("150.0000"));

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
                        WithdrawalRequestDto request = new WithdrawalRequestDto(
                                aliceAccount.getId(),
                                amountPerThread,
                                "USD"
                        );
                        executeAsUser("alice.withdrawal@ledger.com",
                                () -> withdrawalService.executeWithdrawal("wdr-conc-39-" + idx, request));
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
            assertThat(checkAlice.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(checkAlice.getBalance()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("40. Concurrent withdrawals from same account remain balanced")
        void concurrentWithdrawalsFromSameAccountRemainBalanced() throws Exception {
            fundAccount(aliceUser, aliceAccount, new BigDecimal("1000.0000"));
            BigDecimal clearingBefore = accountRepository.findById(WithdrawalService.SYSTEM_CLEARING_ACCOUNT_ID).orElseThrow().getBalance();

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
                        WithdrawalRequestDto request = new WithdrawalRequestDto(
                                aliceAccount.getId(),
                                amountPerThread,
                                "USD"
                        );
                        executeAsUser("alice.withdrawal@ledger.com",
                                () -> withdrawalService.executeWithdrawal("wdr-conc-40-" + idx, request));
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

            BigDecimal totalWithdrawn = amountPerThread.multiply(BigDecimal.valueOf(threadCount));
            Account checkAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
            Account checkClearing = accountRepository.findById(WithdrawalService.SYSTEM_CLEARING_ACCOUNT_ID).orElseThrow();

            assertThat(checkAlice.getBalance()).isEqualByComparingTo(new BigDecimal("1000.0000").subtract(totalWithdrawn));
            assertThat(checkClearing.getBalance()).isEqualByComparingTo(clearingBefore.add(totalWithdrawn));
        }

        @Test
        @DisplayName("41. Concurrent withdrawal and deposit involving same account never deadlock")
        void concurrentWithdrawalAndDepositNeverDeadlock() throws Exception {
            fundAccount(aliceUser, aliceAccount, new BigDecimal("500.0000"));
            BigDecimal clearingBefore = accountRepository.findById(WithdrawalService.SYSTEM_CLEARING_ACCOUNT_ID).orElseThrow().getBalance();

            int pairs = 5;
            int threadCount = pairs * 2;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicInteger depositSuccesses = new AtomicInteger();
            AtomicInteger withdrawalSuccesses = new AtomicInteger();

            for (int i = 0; i < pairs; i++) {
                final int idx = i;
                // Deposit thread
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        executeAsUser("alice.withdrawal@ledger.com", () ->
                                depositService.executeDeposit(
                                        "conc-dep-" + idx,
                                        new DepositRequestDto(aliceAccount.getId(), new BigDecimal("100.0000"), "USD")
                                )
                        );
                        depositSuccesses.incrementAndGet();
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        doneLatch.countDown();
                    }
                });

                // Withdrawal thread
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        executeAsUser("alice.withdrawal@ledger.com", () ->
                                withdrawalService.executeWithdrawal(
                                        "conc-wdr-" + idx,
                                        new WithdrawalRequestDto(aliceAccount.getId(), new BigDecimal("100.0000"), "USD")
                                )
                        );
                        withdrawalSuccesses.incrementAndGet();
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
            assertThat(depositSuccesses.get()).isEqualTo(pairs);
            assertThat(withdrawalSuccesses.get()).isEqualTo(pairs);

            Account checkAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
            Account checkClearing = accountRepository.findById(WithdrawalService.SYSTEM_CLEARING_ACCOUNT_ID).orElseThrow();

            // 5 deposits of 100 (+500) and 5 withdrawals of 100 (-500) = net zero change
            assertThat(checkAlice.getBalance()).isEqualByComparingTo(new BigDecimal("500.0000"));
            assertThat(checkClearing.getBalance()).isEqualByComparingTo(clearingBefore);
        }

        @Test
        @DisplayName("42. Concurrent withdrawal and transfer involving same account never deadlock")
        void concurrentWithdrawalAndTransferNeverDeadlock() throws Exception {
            fundAccount(aliceUser, aliceAccount, new BigDecimal("1000.0000"));

            int count = 5;
            int threadCount = count * 2;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicInteger transferSuccesses = new AtomicInteger();
            AtomicInteger withdrawalSuccesses = new AtomicInteger();

            for (int i = 0; i < count; i++) {
                final int idx = i;
                // Transfer thread (Alice -> Bob)
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        executeAsUser("alice.withdrawal@ledger.com", () ->
                                transferService.executeTransfer(
                                        "conc-tf-" + idx,
                                        new TransferRequestDto(aliceAccount.getId(), bobAccount.getId(), new BigDecimal("50.0000"), "USD")
                                )
                        );
                        transferSuccesses.incrementAndGet();
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        doneLatch.countDown();
                    }
                });

                // Withdrawal thread (Alice -> Clearing)
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        executeAsUser("alice.withdrawal@ledger.com", () ->
                                withdrawalService.executeWithdrawal(
                                        "conc-wdr-tf-" + idx,
                                        new WithdrawalRequestDto(aliceAccount.getId(), new BigDecimal("50.0000"), "USD")
                                )
                        );
                        withdrawalSuccesses.incrementAndGet();
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
            assertThat(transferSuccesses.get()).isEqualTo(count);
            assertThat(withdrawalSuccesses.get()).isEqualTo(count);

            Account checkAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
            Account checkBob = accountRepository.findById(bobAccount.getId()).orElseThrow();

            // 1000 - 5*50 - 5*50 = 500
            assertThat(checkAlice.getBalance()).isEqualByComparingTo(new BigDecimal("500.0000"));
            assertThat(checkBob.getBalance()).isEqualByComparingTo(new BigDecimal("250.0000"));
        }

        @Test
        @DisplayName("43. Concurrent duplicate withdrawal requests create exactly one financial effect")
        void concurrentDuplicateRequestsCreateOneFinancialEffect() throws Exception {
            fundAccount(aliceUser, aliceAccount, new BigDecimal("500.0000"));

            int threadCount = 10;
            String sharedKey = "wdr-conc-idem-shared";
            BigDecimal amount = new BigDecimal("100.0000");
            WithdrawalRequestDto request = new WithdrawalRequestDto(aliceAccount.getId(), amount, "USD");

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            Set<UUID> returnedTxIds = ConcurrentHashMap.newKeySet();
            AtomicInteger successCount = new AtomicInteger();

            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        TransactionResponseDto response = executeAsUser("alice.withdrawal@ledger.com",
                                () -> withdrawalService.executeWithdrawal(sharedKey, request));
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
            assertThat(checkAlice.getBalance()).isEqualByComparingTo(new BigDecimal("400.0000"));
        }

        @Test
        @DisplayName("44. Concurrent withdrawals and deposits reconcile correctly")
        void concurrentWithdrawalsReconcileCorrectly() throws Exception {
            fundAccount(aliceUser, aliceAccount, new BigDecimal("1000.0000"));

            int threadCount = 8;
            BigDecimal amountPerThread = new BigDecimal("50.0000");
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);

            for (int i = 0; i < threadCount; i++) {
                final int idx = i;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        WithdrawalRequestDto request = new WithdrawalRequestDto(
                                aliceAccount.getId(),
                                amountPerThread,
                                "USD"
                        );
                        executeAsUser("alice.withdrawal@ledger.com",
                                () -> withdrawalService.executeWithdrawal("wdr-recon-conc-" + idx, request));
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

            executeAsUser("alice.withdrawal@ledger.com", () -> {
                ReconciliationResultDto aliceRecon = reconciliationService.reconcileAccount(aliceAccount.getId());
                BigDecimal expectedAliceBalance = new BigDecimal("1000.0000").subtract(amountPerThread.multiply(BigDecimal.valueOf(threadCount)));
                assertThat(aliceRecon.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
                assertThat(aliceRecon.difference()).isEqualByComparingTo(BigDecimal.ZERO);
                assertThat(aliceRecon.snapshotBalance()).isEqualByComparingTo(expectedAliceBalance);
                assertThat(aliceRecon.ledgerBalance()).isEqualByComparingTo(expectedAliceBalance);
                return null;
            });
        }
    }
}
