package com.parth.ledger.account;

import com.parth.ledger.BaseIntegrationTest;
import com.parth.ledger.account.dto.CreateAccountRequestDto;
import com.parth.ledger.ledger.LedgerEntryRepository;
import com.parth.ledger.transaction.TransactionRepository;
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
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@DisplayName("V5 Account Management APIs Integration Tests")
class AccountControllerIntegrationTest extends BaseIntegrationTest {

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

    private User aliceUser;
    private User bobUser;

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

        aliceUser = userRepository.save(new User("alice.v5@ledger.com", "Alice V5"));
        bobUser = userRepository.save(new User("bob.v5@ledger.com", "Bob V5"));
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
    // 1. ACCOUNT CREATION TESTS
    // =========================================================================

    @Test
    @DisplayName("1. Authenticated user can create USER_CHECKING account")
    void authenticatedUserCanCreateAccount() throws Exception {
        CreateAccountRequestDto request = new CreateAccountRequestDto("INR");

        mockMvc.perform(post("/api/v1/accounts")
                        .with(user("alice.v5@ledger.com"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountId", notNullValue()))
                .andExpect(jsonPath("$.accountNumber", startsWith("ACCT-")))
                .andExpect(jsonPath("$.accountType", is("USER_CHECKING")))
                .andExpect(jsonPath("$.status", is("ACTIVE")))
                .andExpect(jsonPath("$.currency", is("INR")))
                .andExpect(jsonPath("$.balance", is(0.0)))
                .andExpect(jsonPath("$.createdAt", notNullValue()))
                .andExpect(jsonPath("$.updatedAt", notNullValue()));
    }

    @Test
    @DisplayName("2. Created account balance starts strictly at zero")
    void createdBalanceIsZero() throws Exception {
        CreateAccountRequestDto request = new CreateAccountRequestDto("INR");

        String responseBody = mockMvc.perform(post("/api/v1/accounts")
                        .with(user("alice.v5@ledger.com"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.balance", is(0.0)))
                .andReturn()
                .getResponse()
                .getContentAsString();

        UUID accountId = UUID.fromString(objectMapper.readTree(responseBody).get("accountId").asText());
        Account account = accountRepository.findById(accountId).orElseThrow();
        assertThat(account.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(account.getBalance().scale()).isEqualTo(4);
    }

    @Test
    @DisplayName("3. Created account status starts strictly as ACTIVE")
    void createdAccountIsActive() throws Exception {
        CreateAccountRequestDto request = new CreateAccountRequestDto("INR");

        String responseBody = mockMvc.perform(post("/api/v1/accounts")
                        .with(user("alice.v5@ledger.com"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("ACTIVE")))
                .andReturn()
                .getResponse()
                .getContentAsString();

        UUID accountId = UUID.fromString(objectMapper.readTree(responseBody).get("accountId").asText());
        Account account = accountRepository.findById(accountId).orElseThrow();
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    @DisplayName("4. Created account has USER_CHECKING type")
    void createdAccountHasUserCheckingType() throws Exception {
        CreateAccountRequestDto request = new CreateAccountRequestDto("INR");

        String responseBody = mockMvc.perform(post("/api/v1/accounts")
                        .with(user("alice.v5@ledger.com"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountType", is("USER_CHECKING")))
                .andReturn()
                .getResponse()
                .getContentAsString();

        UUID accountId = UUID.fromString(objectMapper.readTree(responseBody).get("accountId").asText());
        Account account = accountRepository.findById(accountId).orElseThrow();
        assertThat(account.getAccountType()).isEqualTo(AccountType.USER_CHECKING);
        assertThat(account.getUser().getId()).isEqualTo(aliceUser.getId());
    }

    @Test
    @DisplayName("5. Account number is generated server-side according to model")
    void accountNumberIsGenerated() throws Exception {
        CreateAccountRequestDto request = new CreateAccountRequestDto("INR");

        mockMvc.perform(post("/api/v1/accounts")
                        .with(user("alice.v5@ledger.com"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountNumber", startsWith("ACCT-")))
                .andExpect(jsonPath("$.accountNumber", notNullValue()));
    }

    @Test
    @DisplayName("6. Generated account numbers are unique across multiple creations")
    void accountNumberIsUnique() throws Exception {
        CreateAccountRequestDto request1 = new CreateAccountRequestDto("INR");
        CreateAccountRequestDto request2 = new CreateAccountRequestDto("INR");

        String body1 = mockMvc.perform(post("/api/v1/accounts")
                        .with(user("alice.v5@ledger.com"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request1)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String body2 = mockMvc.perform(post("/api/v1/accounts")
                        .with(user("alice.v5@ledger.com"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request2)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String acctNum1 = objectMapper.readTree(body1).get("accountNumber").asText();
        String acctNum2 = objectMapper.readTree(body2).get("accountNumber").asText();

        assertThat(acctNum1).isNotEqualTo(acctNum2);
    }

    @Test
    @DisplayName("7. Non-INR currencies (e.g. USD, EUR, GBP) are rejected with HTTP 400")
    void currencyIsPersistedCorrectly() throws Exception {
        for (String curr : new String[]{"USD", "EUR", "GBP"}) {
            CreateAccountRequestDto request = new CreateAccountRequestDto(curr);

            mockMvc.perform(post("/api/v1/accounts")
                            .with(user("alice.v5@ledger.com"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", containsString("Only INR currency is supported: " + curr)));
        }
    }

    @Test
    @DisplayName("8. Invalid currency format is rejected with HTTP 400")
    void invalidCurrencyIsRejected() throws Exception {
        // Lowercase
        mockMvc.perform(post("/api/v1/accounts")
                        .with(user("alice.v5@ledger.com"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\": \"usd\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)));

        // Too short (2 characters)
        mockMvc.perform(post("/api/v1/accounts")
                        .with(user("alice.v5@ledger.com"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\": \"US\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)));

        // Too long (4 characters)
        mockMvc.perform(post("/api/v1/accounts")
                        .with(user("alice.v5@ledger.com"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\": \"USDT\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)));

        // Non-alphabetic
        mockMvc.perform(post("/api/v1/accounts")
                        .with(user("alice.v5@ledger.com"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\": \"123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)));
    }

    @Test
    @DisplayName("9. Missing or empty currency is rejected with HTTP 400")
    void missingCurrencyIsRejected() throws Exception {
        // null currency
        mockMvc.perform(post("/api/v1/accounts")
                        .with(user("alice.v5@ledger.com"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\": null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)));

        // blank currency
        mockMvc.perform(post("/api/v1/accounts")
                        .with(user("alice.v5@ledger.com"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\": \"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)));

        // empty JSON object
        mockMvc.perform(post("/api/v1/accounts")
                        .with(user("alice.v5@ledger.com"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)));
    }

    @Test
    @DisplayName("10. Client cannot choose account type (always USER_CHECKING)")
    void clientCannotChooseAccountType() throws Exception {
        String payload = "{\"currency\": \"INR\", \"accountType\": \"SYSTEM_CLEARING\"}";

        mockMvc.perform(post("/api/v1/accounts")
                        .with(user("alice.v5@ledger.com"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountType", is("USER_CHECKING")));
    }

    @Test
    @DisplayName("11. Client cannot choose initial balance (always zero)")
    void clientCannotChooseBalance() throws Exception {
        String payload = "{\"currency\": \"INR\", \"balance\": 99999.0000}";

        mockMvc.perform(post("/api/v1/accounts")
                        .with(user("alice.v5@ledger.com"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.balance", is(0.0)));
    }

    @Test
    @DisplayName("12. Client cannot choose status (always ACTIVE)")
    void clientCannotChooseStatus() throws Exception {
        String payload = "{\"currency\": \"INR\", \"status\": \"CLOSED\"}";

        mockMvc.perform(post("/api/v1/accounts")
                        .with(user("alice.v5@ledger.com"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("ACTIVE")));
    }

    @Test
    @DisplayName("13. Unauthenticated account creation returns HTTP 401 Unauthorized")
    void unauthenticatedCreationReturns401() throws Exception {
        CreateAccountRequestDto request = new CreateAccountRequestDto("INR");

        mockMvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // 2. LIST USER ACCOUNTS TESTS
    // =========================================================================

    @Test
    @DisplayName("14. Authenticated user sees all their own accounts")
    void authenticatedUserSeesTheirOwnAccounts() throws Exception {
        accountRepository.save(new Account(aliceUser, "INR", BigDecimal.ZERO));
        accountRepository.save(new Account(aliceUser, "INR", BigDecimal.ZERO));

        mockMvc.perform(get("/api/v1/accounts")
                        .with(user("alice.v5@ledger.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].currency", is("INR")))
                .andExpect(jsonPath("$[1].currency", is("INR")));
    }

    @Test
    @DisplayName("15. Authenticated user cannot see another user's accounts")
    void authenticatedUserCannotSeeAnotherUsersAccounts() throws Exception {
        Account aliceAcc = accountRepository.save(new Account(aliceUser, "INR", BigDecimal.ZERO));
        Account bobAcc = accountRepository.save(new Account(bobUser, "INR", BigDecimal.ZERO));

        // Alice's perspective
        mockMvc.perform(get("/api/v1/accounts")
                        .with(user("alice.v5@ledger.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].accountId", is(aliceAcc.getId().toString())));

        // Bob's perspective
        mockMvc.perform(get("/api/v1/accounts")
                        .with(user("bob.v5@ledger.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].accountId", is(bobAcc.getId().toString())));
    }

    @Test
    @DisplayName("16. SYSTEM_CLEARING accounts are strictly excluded from list endpoint")
    void systemClearingAccountIsExcludedFromList() throws Exception {
        accountRepository.save(new Account(aliceUser, "INR", BigDecimal.ZERO));
        accountRepository.save(Account.createSystemClearingAccount("INR", "ACCT-SYS-CLEAR-01"));

        mockMvc.perform(get("/api/v1/accounts")
                        .with(user("alice.v5@ledger.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].accountType", is("USER_CHECKING")));
    }

    @Test
    @DisplayName("17. Empty account list works correctly (returns empty JSON array)")
    void emptyAccountListWorksCorrectly() throws Exception {
        // Bob has zero accounts
        mockMvc.perform(get("/api/v1/accounts")
                        .with(user("bob.v5@ledger.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    // =========================================================================
    // 3. GET SINGLE ACCOUNT TESTS
    // =========================================================================

    @Test
    @DisplayName("18. User can retrieve their own account by ID")
    void userCanRetrieveOwnAccount() throws Exception {
        Account account = accountRepository.save(new Account(aliceUser, "INR", BigDecimal.ZERO));

        mockMvc.perform(get("/api/v1/accounts/" + account.getId())
                        .with(user("alice.v5@ledger.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId", is(account.getId().toString())))
                .andExpect(jsonPath("$.accountNumber", is(account.getAccountNumber())))
                .andExpect(jsonPath("$.accountType", is("USER_CHECKING")))
                .andExpect(jsonPath("$.status", is("ACTIVE")))
                .andExpect(jsonPath("$.currency", is("INR")))
                .andExpect(jsonPath("$.balance", is(0.0)));
    }

    @Test
    @DisplayName("19. User cannot retrieve another user's account (returns HTTP 404 Not Found)")
    void userCannotRetrieveAnotherUsersAccount() throws Exception {
        Account bobAccount = accountRepository.save(new Account(bobUser, "INR", BigDecimal.ZERO));

        // Alice attempts to access Bob's account
        mockMvc.perform(get("/api/v1/accounts/" + bobAccount.getId())
                        .with(user("alice.v5@ledger.com")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status", is(404)));
    }

    @Test
    @DisplayName("20. SYSTEM_CLEARING accounts are not exposed through single account endpoint")
    void systemClearingAccountIsNotExposed() throws Exception {
        Account systemAccount = accountRepository.save(Account.createSystemClearingAccount("INR", "ACCT-SYS-CLEAR-02"));

        mockMvc.perform(get("/api/v1/accounts/" + systemAccount.getId())
                        .with(user("alice.v5@ledger.com")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status", is(404)));
    }

    @Test
    @DisplayName("21. Nonexistent account returns HTTP 404 Not Found")
    void nonexistentAccountReturns404() throws Exception {
        UUID nonexistentId = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/accounts/" + nonexistentId)
                        .with(user("alice.v5@ledger.com")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status", is(404)))
                .andExpect(jsonPath("$.message", containsString(nonexistentId.toString())));
    }

    @Test
    @DisplayName("22. Unauthenticated GET requests return HTTP 401 Unauthorized")
    void unauthenticatedGetReturns401() throws Exception {
        UUID randomId = UUID.randomUUID();

        // List endpoint
        mockMvc.perform(get("/api/v1/accounts"))
                .andExpect(status().isUnauthorized());

        // Single account endpoint
        mockMvc.perform(get("/api/v1/accounts/" + randomId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("23. Malformed UUID parameter in GET request returns HTTP 400 Bad Request")
    void malformedUuidReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/not-a-valid-uuid")
                        .with(user("alice.v5@ledger.com")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)));
    }
}
