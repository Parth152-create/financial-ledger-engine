package com.parth.ledger.account;

import com.parth.ledger.BaseIntegrationTest;
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
import tools.jackson.databind.ObjectMapper;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@WithMockUser(username = "alice.v2@ledger.com")
class AccountModelIntegrationTest extends BaseIntegrationTest {

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

        aliceUser = userRepository.save(new User("alice.v2@ledger.com", "Alice V2"));
        bobUser = userRepository.save(new User("bob.v2@ledger.com", "Bob V2"));

        aliceAccount = accountRepository.save(new Account(aliceUser, "USD", new BigDecimal("1000.0000")));
        bobAccount = accountRepository.save(new Account(bobUser, "USD", new BigDecimal("500.0000")));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        ledgerEntryRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();
    }

    // =========================================================================
    // 1. ACCOUNT TYPE TESTS
    // =========================================================================

    @Test
    @DisplayName("Account type: USER_CHECKING account persisted with default type and valid user")
    void persistUserCheckingAccount() {
        Account retrieved = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        assertThat(retrieved.getAccountType()).isEqualTo(AccountType.USER_CHECKING);
        assertThat(retrieved.isUserChecking()).isTrue();
        assertThat(retrieved.isSystemClearing()).isFalse();
        assertThat(retrieved.getUser()).isNotNull();
        assertThat(retrieved.getUser().getId()).isEqualTo(aliceUser.getId());
    }

    @Test
    @DisplayName("Account type: SYSTEM_CLEARING account persisted with null user_id")
    void persistSystemClearingAccountWithNullUser() {
        Account clearing = Account.createSystemClearingAccount("USD", "SYS-CLEAR-001");
        clearing = accountRepository.save(clearing);

        Account retrieved = accountRepository.findById(clearing.getId()).orElseThrow();
        assertThat(retrieved.getAccountType()).isEqualTo(AccountType.SYSTEM_CLEARING);
        assertThat(retrieved.isSystemClearing()).isTrue();
        assertThat(retrieved.isUserChecking()).isFalse();
        assertThat(retrieved.getUser()).isNull();
        assertThat(retrieved.getAccountNumber()).isEqualTo("SYS-CLEAR-001");
    }

    @Test
    @DisplayName("Account type: DB CHECK constraint rejects invalid account_type")
    void databaseConstraintRejectsInvalidAccountType() {
        UUID accountId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO accounts (id, user_id, currency, balance, version, created_at, updated_at, account_type, status, account_number) " +
                        "VALUES (?, ?, 'USD', 0, 0, NOW(), NOW(), 'SAVINGS', 'ACTIVE', ?)",
                accountId, aliceUser.getId(), "ACCT-INVALID-TYPE"
        )).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_accounts_account_type");
    }

    @Test
    @DisplayName("Account type: DB CHECK constraint rejects USER_CHECKING with NULL user_id")
    void databaseConstraintRejectsUserCheckingWithNullUser() {
        UUID accountId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO accounts (id, user_id, currency, balance, version, created_at, updated_at, account_type, status, account_number) " +
                        "VALUES (?, NULL, 'USD', 0, 0, NOW(), NOW(), 'USER_CHECKING', 'ACTIVE', ?)",
                accountId, "ACCT-NULL-USER"
        )).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_accounts_user_requirement");
    }

    // =========================================================================
    // 2. ACCOUNT STATUS TESTS
    // =========================================================================

    @Test
    @DisplayName("Account status: Default status is ACTIVE")
    void defaultStatusIsActive() {
        Account retrieved = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        assertThat(retrieved.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(retrieved.isActive()).isTrue();
        assertThat(retrieved.isFrozen()).isFalse();
        assertThat(retrieved.isClosed()).isFalse();
    }

    @Test
    @DisplayName("Account status: Update status to FROZEN and CLOSED")
    void updateAccountStatusLifecycle() {
        aliceAccount.setStatus(AccountStatus.FROZEN);
        aliceAccount = accountRepository.save(aliceAccount);

        Account frozen = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        assertThat(frozen.getStatus()).isEqualTo(AccountStatus.FROZEN);
        assertThat(frozen.isFrozen()).isTrue();
        assertThat(frozen.isActive()).isFalse();

        frozen.setStatus(AccountStatus.CLOSED);
        frozen = accountRepository.save(frozen);

        Account closed = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        assertThat(closed.getStatus()).isEqualTo(AccountStatus.CLOSED);
        assertThat(closed.isClosed()).isTrue();
        assertThat(closed.isActive()).isFalse();
    }

    @Test
    @DisplayName("Account status: DB CHECK constraint rejects invalid status")
    void databaseConstraintRejectsInvalidStatus() {
        UUID accountId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO accounts (id, user_id, currency, balance, version, created_at, updated_at, account_type, status, account_number) " +
                        "VALUES (?, ?, 'USD', 0, 0, NOW(), NOW(), 'USER_CHECKING', 'SUSPENDED', ?)",
                accountId, aliceUser.getId(), "ACCT-INVALID-STATUS"
        )).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_accounts_status");
    }

    // =========================================================================
    // 3. PUBLIC ACCOUNT NUMBER TESTS
    // =========================================================================

    @Test
    @DisplayName("Account number: Auto-generated public account number starts with ACCT- and is non-empty")
    void autoGeneratedAccountNumber() {
        Account retrieved = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        assertThat(retrieved.getAccountNumber()).isNotNull();
        assertThat(retrieved.getAccountNumber()).startsWith("ACCT-");
        assertThat(retrieved.getAccountNumber().length()).isLessThanOrEqualTo(32);
    }

    @Test
    @DisplayName("Account number: DB UNIQUE constraint rejects duplicate account_number")
    void databaseConstraintRejectsDuplicateAccountNumber() {
        String existingNumber = aliceAccount.getAccountNumber();

        Account duplicate = new Account(bobUser, "USD", BigDecimal.ZERO, AccountType.USER_CHECKING, AccountStatus.ACTIVE, existingNumber);
        assertThatThrownBy(() -> accountRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Account number: DB NOT NULL constraint rejects null account_number")
    void databaseConstraintRejectsNullAccountNumber() {
        UUID accountId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO accounts (id, user_id, currency, balance, version, created_at, updated_at, account_type, status, account_number) " +
                        "VALUES (?, ?, 'USD', 0, 0, NOW(), NOW(), 'USER_CHECKING', 'ACTIVE', NULL)",
                accountId, aliceUser.getId()
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    // =========================================================================
    // 4. DATABASE CURRENCY FORMAT VALIDATION TESTS
    // =========================================================================

    @Test
    @DisplayName("Currency validation: DB CHECK constraint accepts valid ISO 3-letter uppercase currencies")
    void databaseConstraintAcceptsValidCurrencyFormat() {
        Account eurAccount = accountRepository.save(new Account(aliceUser, "EUR", BigDecimal.ZERO));
        Account gbpAccount = accountRepository.save(new Account(aliceUser, "GBP", BigDecimal.ZERO));

        assertThat(accountRepository.findById(eurAccount.getId())).isPresent();
        assertThat(accountRepository.findById(gbpAccount.getId())).isPresent();
    }

    @Test
    @DisplayName("Currency validation: DB CHECK constraint rejects lowercase currency 'usd'")
    void databaseConstraintRejectsLowercaseCurrency() {
        UUID accountId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO accounts (id, user_id, currency, balance, version, created_at, updated_at, account_type, status, account_number) " +
                        "VALUES (?, ?, 'usd', 0, 0, NOW(), NOW(), 'USER_CHECKING', 'ACTIVE', ?)",
                accountId, aliceUser.getId(), "ACCT-LOWER-USD"
        )).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_accounts_currency_format");
    }

    @Test
    @DisplayName("Currency validation: DB CHECK constraint rejects 2-letter currency 'US'")
    void databaseConstraintRejectsTwoLetterCurrency() {
        UUID accountId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO accounts (id, user_id, currency, balance, version, created_at, updated_at, account_type, status, account_number) " +
                        "VALUES (?, ?, 'US', 0, 0, NOW(), NOW(), 'USER_CHECKING', 'ACTIVE', ?)",
                accountId, aliceUser.getId(), "ACCT-TWO-LETTER"
        )).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_accounts_currency_format");
    }

    @Test
    @DisplayName("Currency validation: DB CHECK constraint rejects invalid characters 'U$D'")
    void databaseConstraintRejectsSpecialCharacterCurrency() {
        UUID accountId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO accounts (id, user_id, currency, balance, version, created_at, updated_at, account_type, status, account_number) " +
                        "VALUES (?, ?, 'U$D', 0, 0, NOW(), NOW(), 'USER_CHECKING', 'ACTIVE', ?)",
                accountId, aliceUser.getId(), "ACCT-SPECIAL-CHAR"
        )).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_accounts_currency_format");
    }

    @Test
    @DisplayName("Currency validation: DB CHECK constraint rejects numeric currency '123'")
    void databaseConstraintRejectsNumericCurrency() {
        UUID accountId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO accounts (id, user_id, currency, balance, version, created_at, updated_at, account_type, status, account_number) " +
                        "VALUES (?, ?, '123', 0, 0, NOW(), NOW(), 'USER_CHECKING', 'ACTIVE', ?)",
                accountId, aliceUser.getId(), "ACCT-NUMERIC-CURRENCY"
        )).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_accounts_currency_format");
    }

    // =========================================================================
    // 5. TRANSFER ENFORCEMENT OF ACCOUNT STATUS
    // =========================================================================

    @Test
    @DisplayName("Transfer status: Transfer succeeds between ACTIVE accounts")
    void transferSucceedsBetweenActiveAccounts() throws Exception {
        TransferRequestDto request = new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("100.0000"),
                "USD"
        );

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "tx-v2-active-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("COMPLETED")))
                .andExpect(jsonPath("$.amount", is(100.0)));

        assertThat(accountRepository.findById(aliceAccount.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("900.0000");
        assertThat(accountRepository.findById(bobAccount.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("600.0000");
    }

    @Test
    @DisplayName("Transfer status: Rejects transfer when source account is FROZEN (422)")
    void transferFailsWhenSourceAccountIsFrozen() throws Exception {
        aliceAccount.setStatus(AccountStatus.FROZEN);
        accountRepository.save(aliceAccount);

        TransferRequestDto request = new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("50.0000"),
                "USD"
        );

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "tx-v2-src-frozen")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status", is(422)))
                .andExpect(jsonPath("$.message", containsString("FROZEN")));

        // Balances remain intact
        assertThat(accountRepository.findById(aliceAccount.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("1000.0000");
        assertThat(accountRepository.findById(bobAccount.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("500.0000");
        assertThat(transactionRepository.count()).isZero();
        assertThat(ledgerEntryRepository.count()).isZero();
    }

    @Test
    @DisplayName("Transfer status: Rejects transfer when destination account is FROZEN (422)")
    void transferFailsWhenDestinationAccountIsFrozen() throws Exception {
        bobAccount.setStatus(AccountStatus.FROZEN);
        accountRepository.save(bobAccount);

        TransferRequestDto request = new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("50.0000"),
                "USD"
        );

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "tx-v2-dst-frozen")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status", is(422)))
                .andExpect(jsonPath("$.message", containsString("FROZEN")));

        // Balances remain intact
        assertThat(accountRepository.findById(aliceAccount.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("1000.0000");
        assertThat(accountRepository.findById(bobAccount.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("500.0000");
        assertThat(transactionRepository.count()).isZero();
        assertThat(ledgerEntryRepository.count()).isZero();
    }

    @Test
    @DisplayName("Transfer status: Rejects transfer when source account is CLOSED (422)")
    void transferFailsWhenSourceAccountIsClosed() throws Exception {
        aliceAccount.setStatus(AccountStatus.CLOSED);
        accountRepository.save(aliceAccount);

        TransferRequestDto request = new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("50.0000"),
                "USD"
        );

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "tx-v2-src-closed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status", is(422)))
                .andExpect(jsonPath("$.message", containsString("CLOSED")));

        // Balances remain intact
        assertThat(accountRepository.findById(aliceAccount.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("1000.0000");
        assertThat(accountRepository.findById(bobAccount.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("500.0000");
        assertThat(transactionRepository.count()).isZero();
        assertThat(ledgerEntryRepository.count()).isZero();
    }

    @Test
    @DisplayName("Transfer status: Rejects transfer when destination account is CLOSED (422)")
    void transferFailsWhenDestinationAccountIsClosed() throws Exception {
        bobAccount.setStatus(AccountStatus.CLOSED);
        accountRepository.save(bobAccount);

        TransferRequestDto request = new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("50.0000"),
                "USD"
        );

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "tx-v2-dst-closed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status", is(422)))
                .andExpect(jsonPath("$.message", containsString("CLOSED")));

        // Balances remain intact
        assertThat(accountRepository.findById(aliceAccount.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("1000.0000");
        assertThat(accountRepository.findById(bobAccount.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("500.0000");
        assertThat(transactionRepository.count()).isZero();
        assertThat(ledgerEntryRepository.count()).isZero();
    }

    // =========================================================================
    // 6. TRANSFER ENFORCEMENT OF ACCOUNT TYPE
    // =========================================================================

    @Test
    @DisplayName("Transfer type: Rejects transfer when destination is SYSTEM_CLEARING (400)")
    void transferFailsWhenDestinationIsSystemClearing() throws Exception {
        Account clearingAccount = Account.createSystemClearingAccount("USD", "SYS-CLEAR-DST");
        clearingAccount = accountRepository.save(clearingAccount);

        TransferRequestDto request = new TransferRequestDto(
                aliceAccount.getId(),
                clearingAccount.getId(),
                new BigDecimal("50.0000"),
                "USD"
        );

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "tx-v2-dst-clearing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.message", containsString("USER_CHECKING")));

        // Balances remain intact
        assertThat(accountRepository.findById(aliceAccount.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("1000.0000");
        assertThat(accountRepository.findById(clearingAccount.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("0.0000");
        assertThat(transactionRepository.count()).isZero();
        assertThat(ledgerEntryRepository.count()).isZero();
    }

    @Test
    @DisplayName("Transfer type: Rejects transfer when source is SYSTEM_CLEARING (400)")
    void transferFailsWhenSourceIsSystemClearing() throws Exception {
        Account clearingAccount = Account.createSystemClearingAccount("USD", "SYS-CLEAR-SRC");
        clearingAccount.setBalance(new BigDecimal("1000.0000"));
        clearingAccount = accountRepository.save(clearingAccount);

        TransferRequestDto request = new TransferRequestDto(
                clearingAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("50.0000"),
                "USD"
        );

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "tx-v2-src-clearing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.message", containsString("USER_CHECKING")));

        // Balances remain intact
        assertThat(accountRepository.findById(clearingAccount.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("1000.0000");
        assertThat(accountRepository.findById(bobAccount.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("500.0000");
        assertThat(transactionRepository.count()).isZero();
        assertThat(ledgerEntryRepository.count()).isZero();
    }

    // =========================================================================
    // 7. ACCOUNT REPOSITORY QUERY METHODS
    // =========================================================================

    @Test
    @DisplayName("Repository: Query by account number and account type")
    void testRepositoryQueryMethods() {
        Optional<Account> byNumber = accountRepository.findByAccountNumber(aliceAccount.getAccountNumber());
        assertThat(byNumber).isPresent();
        assertThat(byNumber.get().getId()).isEqualTo(aliceAccount.getId());

        List<Account> userChecking = accountRepository.findByAccountType(AccountType.USER_CHECKING);
        assertThat(userChecking).hasSize(2);

        Account clearing = Account.createSystemClearingAccount("USD", "SYS-CLEAR-QUERY");
        accountRepository.save(clearing);

        List<Account> clearingAccounts = accountRepository.findByAccountType(AccountType.SYSTEM_CLEARING);
        assertThat(clearingAccounts).hasSize(1);
        assertThat(clearingAccounts.get(0).getAccountNumber()).isEqualTo("SYS-CLEAR-QUERY");

        Optional<Account> byTypeAndCurrency = accountRepository.findByAccountTypeAndCurrency(AccountType.SYSTEM_CLEARING, "USD");
        assertThat(byTypeAndCurrency).isPresent();
        assertThat(byTypeAndCurrency.get().getAccountNumber()).isEqualTo("SYS-CLEAR-QUERY");
    }
}
