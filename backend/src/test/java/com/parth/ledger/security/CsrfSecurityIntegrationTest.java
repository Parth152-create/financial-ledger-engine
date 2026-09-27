package com.parth.ledger.security;

import com.parth.ledger.BaseIntegrationTest;
import com.parth.ledger.account.Account;
import com.parth.ledger.account.AccountRepository;
import com.parth.ledger.account.dto.CreateAccountRequestDto;
import com.parth.ledger.ledger.LedgerEntryRepository;
import com.parth.ledger.security.dto.LoginRequestDto;
import com.parth.ledger.security.dto.SignupRequestDto;
import com.parth.ledger.security.UserAuthService;
import com.parth.ledger.transaction.TransactionRepository;
import com.parth.ledger.transaction.dto.TransferRequestDto;
import com.parth.ledger.user.User;
import com.parth.ledger.user.UserCredentialRepository;
import com.parth.ledger.user.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.web.support.WebTestUtils;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@DisplayName("CSRF & Session Security Integration Tests")
class CsrfSecurityIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserCredentialRepository userCredentialRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private UserAuthService userAuthService;

    @Autowired
    private CsrfTokenRepository csrfTokenRepository;

    @Autowired
    private WebApplicationContext wac;

    private User aliceUser;
    private User bobUser;
    private Account aliceAccount;
    private Account bobAccount;

    private void resetCsrfFilterRepository() {
        if (wac != null && wac.getServletContext() != null) {
            MockHttpServletRequest dummy = new MockHttpServletRequest(wac.getServletContext());
            WebTestUtils.setCsrfTokenRepository(dummy, csrfTokenRepository);
        }
    }

    @BeforeEach
    void setUp() {
        resetCsrfFilterRepository();
        clearRedis();
        cleanupDatabase();

        aliceUser = userRepository.save(new User("alice.csrf@ledger.com", "Alice Csrf"));
        bobUser = userRepository.save(new User("bob.csrf@ledger.com", "Bob Csrf"));

        aliceAccount = accountRepository.save(new Account(aliceUser, "INR", new BigDecimal("1000.0000")));
        bobAccount = accountRepository.save(new Account(bobUser, "INR", new BigDecimal("500.0000")));
    }

    @AfterEach
    void tearDown() {
        resetCsrfFilterRepository();
        SecurityContextHolder.clearContext();
        cleanupDatabase();
    }

    private void cleanupDatabase() {
        if (jdbcTemplate != null) {
            jdbcTemplate.execute("TRUNCATE TABLE ledger_entries CASCADE");
        }
        ledgerEntryRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        userCredentialRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("1. Authenticated GET works without CSRF token (safe HTTP method)")
    void authenticatedGetWorksWithoutCsrfToken() throws Exception {
        mockMvc.perform(get("/api/v1/accounts")
                        .with(user("alice.csrf@ledger.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }


    @Test
    @DisplayName("2. Authenticated POST without CSRF token is rejected with HTTP 403 Forbidden")
    void authenticatedPostWithoutCsrfTokenIsRejected() throws Exception {
        CreateAccountRequestDto request = new CreateAccountRequestDto("INR");

        mockMvc.perform(post("/api/v1/accounts")
                        .with(user("alice.csrf@ledger.com"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status", is(403)))
                .andExpect(jsonPath("$.error", is("Forbidden")))
                .andExpect(jsonPath("$.message", containsString("Access denied")));
    }

    @Test
    @DisplayName("3. Authenticated POST with valid CSRF token succeeds")
    void authenticatedPostWithValidCsrfTokenSucceeds() throws Exception {
        CreateAccountRequestDto request = new CreateAccountRequestDto("INR");

        mockMvc.perform(post("/api/v1/accounts")
                        .with(user("alice.csrf@ledger.com"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currency", is("INR")))
                .andExpect(jsonPath("$.status", is("ACTIVE")));
    }

    @Test
    @DisplayName("4. Unauthenticated state-changing request remains rejected")
    void unauthenticatedStateChangingRequestRemainsRejected() throws Exception {
        CreateAccountRequestDto request = new CreateAccountRequestDto("INR");

        // Without CSRF -> rejected (403 from CSRF filter)
        mockMvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        // With CSRF -> rejected (401 from AuthenticationEntryPoint)
        mockMvc.perform(post("/api/v1/accounts")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status", is(401)))
                .andExpect(jsonPath("$.message", containsString("Authentication required")));
    }

    @Test
    @DisplayName("5. Authenticated user cannot perform another user's financial operation even with valid CSRF")
    void authenticatedUserCannotPerformAnotherUsersFinancialOperation() throws Exception {
        TransferRequestDto request = new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("100.0000"),
                "INR"
        );

        // Bob attempts to transfer out of Alice's account with valid CSRF
        mockMvc.perform(post("/api/v1/transfers")
                        .with(user("bob.csrf@ledger.com"))
                        .with(csrf())
                        .header("Idempotency-Key", "sec-csrf-owner-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status", is(403)))
                .andExpect(jsonPath("$.message", containsString("not authorized to operate on this account")));

        // Verify balances unchanged
        assertThat(accountRepository.findById(aliceAccount.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo(new BigDecimal("1000.0000"));
        assertThat(transactionRepository.count()).isZero();
    }

    @Test
    @DisplayName("6. Concrete regression: Cross-site forged transfer using victim's session is blocked without financial impact")
    void crossSiteForgedTransferUsingVictimSessionIsBlocked() throws Exception {
        // Step 1: Alice logs in legitimately and receives an authenticated session
        SignupRequestDto signupDto = new SignupRequestDto("Alice Csrf", "alice.victim@ledger.com", "VictimPass123!");
        userAuthService.signup(signupDto);

        User victim = userRepository.findByEmail("alice.victim@ledger.com").orElseThrow();
        Account victimAccount = accountRepository.save(new Account(victim, "INR", new BigDecimal("1000.0000")));

        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequestDto("alice.victim@ledger.com", "VictimPass123!"))))
                .andExpect(status().isOk())
                .andReturn();

        MockHttpSession victimSession = (MockHttpSession) loginResult.getRequest().getSession(false);
        assertThat(victimSession).isNotNull();

        // Step 2: Attacker site submits a forged POST request to /api/v1/transfers carrying Alice's session cookie
        // but lacking the secret CSRF token (since attacker cannot read cross-origin cookies)
        TransferRequestDto maliciousTransfer = new TransferRequestDto(
                victimAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("500.0000"),
                "INR"
        );

        mockMvc.perform(post("/api/v1/transfers")
                        .session(victimSession)
                        .header("Idempotency-Key", "attacker-csrf-forge-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(maliciousTransfer)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status", is(403)))
                .andExpect(jsonPath("$.error", is("Forbidden")))
                .andExpect(jsonPath("$.message", containsString("Access denied")));

        // Step 3: Verify zero ledger impact
        Account verifiedAccount = accountRepository.findById(victimAccount.getId()).orElseThrow();
        assertThat(verifiedAccount.getBalance()).isEqualByComparingTo(new BigDecimal("1000.0000"));
        assertThat(transactionRepository.count()).isZero();
        assertThat(ledgerEntryRepository.count()).isZero();
    }

    @Test
    @DisplayName("7. Legitimate SPA flow: Fetch CSRF token endpoint, pass X-XSRF-TOKEN header with session, request succeeds")
    void legitimateSpaFlowWithCsrfHeaderSucceeds() throws Exception {
        // Step 1: User logs in
        SignupRequestDto signupDto = new SignupRequestDto("Alice Spa", "alice.spa@ledger.com", "SpaPass123!");
        userAuthService.signup(signupDto);

        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequestDto("alice.spa@ledger.com", "SpaPass123!"))))
                .andExpect(status().isOk())
                .andReturn();

        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);
        assertThat(session).isNotNull();

        // Step 2: SPA client fetches CSRF token from /api/v1/auth/csrf
        MvcResult csrfResult = mockMvc.perform(get("/api/v1/auth/csrf")
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headerName", is("X-XSRF-TOKEN")))
                .andExpect(jsonPath("$.parameterName", is("_csrf")))
                .andExpect(jsonPath("$.token").isString())
                .andReturn();

        Cookie xsrfCookie = csrfResult.getResponse().getCookie("XSRF-TOKEN");
        assertThat(xsrfCookie).isNotNull();
        assertThat(xsrfCookie.getAttribute("SameSite")).isEqualToIgnoringCase("Lax");
        assertThat(xsrfCookie.isHttpOnly()).isFalse();
        assertThat(xsrfCookie.getPath()).isEqualTo("/");

        String cookieToken = xsrfCookie.getValue();
        assertThat(cookieToken).isNotBlank();

        // Step 3: SPA client performs state-changing operation with session and X-XSRF-TOKEN header
        // (SPA reads document.cookie 'XSRF-TOKEN' and sends it in header 'X-XSRF-TOKEN')
        CreateAccountRequestDto request = new CreateAccountRequestDto("INR");

        mockMvc.perform(post("/api/v1/accounts")
                        .session(session)
                        .cookie(new Cookie("XSRF-TOKEN", cookieToken))
                        .header("X-XSRF-TOKEN", cookieToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currency", is("INR")))
                .andExpect(jsonPath("$.status", is("ACTIVE")));
    }

    @Test
    @DisplayName("8. State-changing request with invalid CSRF token header is rejected with 403")
    void requestWithInvalidCsrfTokenIsRejected() throws Exception {
        SignupRequestDto signupDto = new SignupRequestDto("Alice Tamper", "alice.tamper@ledger.com", "TamperPass123!");
        userAuthService.signup(signupDto);

        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequestDto("alice.tamper@ledger.com", "TamperPass123!"))))
                .andExpect(status().isOk())
                .andReturn();

        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);

        // Fetch valid cookie
        MvcResult csrfResult = mockMvc.perform(get("/api/v1/auth/csrf").session(session))
                .andExpect(status().isOk())
                .andReturn();

        Cookie xsrfCookie = csrfResult.getResponse().getCookie("XSRF-TOKEN");
        assertThat(xsrfCookie).isNotNull();
        String cookieToken = xsrfCookie.getValue();

        CreateAccountRequestDto request = new CreateAccountRequestDto("INR");

        // Send request with forged/invalid header token
        mockMvc.perform(post("/api/v1/accounts")
                        .session(session)
                        .cookie(new Cookie("XSRF-TOKEN", cookieToken))
                        .header("X-XSRF-TOKEN", "invalid-tampered-token-value")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status", is(403)));
    }

    @Test
    @DisplayName("9. Logout requires CSRF protection and invalidates the session upon success")
    void logoutRequiresCsrfAndInvalidatesSession() throws Exception {
        SignupRequestDto signupDto = new SignupRequestDto("Alice Logout", "alice.logout@ledger.com", "LogoutPass123!");
        userAuthService.signup(signupDto);

        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequestDto("alice.logout@ledger.com", "LogoutPass123!"))))
                .andExpect(status().isOk())
                .andReturn();

        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);

        // 1. Logout attempt without CSRF token is rejected with 403
        mockMvc.perform(post("/logout")
                        .session(session))
                .andExpect(status().isForbidden());

        // 2. Logout attempt with valid CSRF succeeds with 200 OK
        mockMvc.perform(post("/logout")
                        .session(session)
                        .with(csrf()))
                .andExpect(status().isOk());

        // 3. Subsequent request with the invalidated session is rejected with 401
        mockMvc.perform(get("/api/v1/auth/me")
                        .session(session))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("10. Session fixation protection rotates session ID upon authentication")
    void sessionFixationProtectionRotatesSessionId() throws Exception {
        SignupRequestDto signupDto = new SignupRequestDto("Alice Fixation", "alice.fixation@ledger.com", "FixationPass123!");
        userAuthService.signup(signupDto);

        // Pre-establish anonymous session
        MvcResult preResult = mockMvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn();

        MockHttpSession preSession = (MockHttpSession) preResult.getRequest().getSession(true);
        String preSessionId = preSession.getId();

        // Authenticate using the pre-established session
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .session(preSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequestDto("alice.fixation@ledger.com", "FixationPass123!"))))
                .andExpect(status().isOk())
                .andReturn();

        MockHttpSession postSession = (MockHttpSession) loginResult.getRequest().getSession(false);
        assertThat(postSession).isNotNull();
        assertThat(postSession.getId()).isNotEqualTo(preSessionId);
    }

    @Test
    @DisplayName("11. OAuth2 authorization endpoint is public and does not require CSRF token")
    void oauth2AuthorizationEndpointAccessibleWithoutCsrf() throws Exception {
        mockMvc.perform(get("/oauth2/authorization/google"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("accounts.google.com/o/oauth2/v2/auth")));
    }
}
