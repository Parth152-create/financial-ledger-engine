package com.parth.ledger.security.ratelimit;

import com.parth.ledger.BaseIntegrationTest;
import com.parth.ledger.account.Account;
import com.parth.ledger.account.AccountRepository;
import com.parth.ledger.ledger.LedgerEntryRepository;
import com.parth.ledger.security.UserAuthService;
import com.parth.ledger.security.dto.LoginRequestDto;
import com.parth.ledger.security.dto.SignupRequestDto;
import com.parth.ledger.transaction.TransactionRepository;
import com.parth.ledger.transaction.dto.TransferRequestDto;
import com.parth.ledger.user.User;
import com.parth.ledger.user.UserCredentialRepository;
import com.parth.ledger.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.web.support.WebTestUtils;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesRegex;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@DisplayName("Authentication & Sensitive Operation Rate Limiting Tests")
class RateLimitingIntegrationTest extends BaseIntegrationTest {

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
    private WebApplicationContext wac;

    @Autowired
    private CsrfTokenRepository csrfTokenRepository;

    @Autowired
    private RedisRateLimiterService rateLimiterService;

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
    @DisplayName("1. Requests below login limit reach authentication and return 401 on bad credentials")
    void loginRequestsBelowLimitReachAuthentication() throws Exception {
        LoginRequestDto badRequest = new LoginRequestDto("unknown@ledger.com", "WrongPassword123!");

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .with(req -> { req.setRemoteAddr("192.168.10.1"); return req; })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(badRequest)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.status", is(401)))
                    .andExpect(jsonPath("$.message", containsString("Invalid email or password")));
        }
    }

    @Test
    @DisplayName("2. Repeated failed logins return 429 with formatted response and Retry-After header")
    void repeatedFailedLoginsEventuallyReturn429WithRetryAfter() throws Exception {
        LoginRequestDto badRequest = new LoginRequestDto("target@ledger.com", "WrongPassword123!");

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .with(req -> { req.setRemoteAddr("192.168.10.2"); return req; })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(badRequest)))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(post("/api/v1/auth/login")
                        .with(req -> { req.setRemoteAddr("192.168.10.2"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(badRequest)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, matchesRegex("^[0-9]+$")))
                .andExpect(jsonPath("$.status", is(429)))
                .andExpect(jsonPath("$.error", is("Too Many Requests")))
                .andExpect(jsonPath("$.message", containsString("Too many requests")))
                .andExpect(jsonPath("$.path", is("/api/v1/auth/login")));
    }

    @Test
    @DisplayName("3. Successful login resets failure counter for identity and IP")
    void successfulLoginResetsFailureCounter() throws Exception {
        SignupRequestDto signup = new SignupRequestDto("Legit User", "legit@ledger.com", "ValidPass123!");
        userAuthService.signup(signup);

        LoginRequestDto badLogin = new LoginRequestDto("legit@ledger.com", "WrongPass123!");
        LoginRequestDto goodLogin = new LoginRequestDto("legit@ledger.com", "ValidPass123!");

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .with(req -> { req.setRemoteAddr("192.168.10.3"); return req; })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(badLogin)))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(post("/api/v1/auth/login")
                        .with(req -> { req.setRemoteAddr("192.168.10.3"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(goodLogin)))
                .andExpect(status().isOk());

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .with(req -> { req.setRemoteAddr("192.168.10.3"); return req; })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(badLogin)))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    @DisplayName("4. Rate limit is enforced per client IP across different user identities")
    void rateLimitEnforcedPerIpAcrossDifferentIdentities() throws Exception {
        String clientIp = "192.168.10.4";

        for (int i = 0; i < 5; i++) {
            LoginRequestDto badRequest = new LoginRequestDto("user" + i + "@ledger.com", "WrongPassword123!");
            mockMvc.perform(post("/api/v1/auth/login")
                            .with(req -> { req.setRemoteAddr(clientIp); return req; })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(badRequest)))
                    .andExpect(status().isUnauthorized());
        }

        LoginRequestDto anyOtherUser = new LoginRequestDto("freshuser@ledger.com", "SomePass123!");
        mockMvc.perform(post("/api/v1/auth/login")
                        .with(req -> { req.setRemoteAddr(clientIp); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(anyOtherUser)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
    }

    @Test
    @DisplayName("5. Rate limit is enforced per normalized identity across multiple client IPs")
    void rateLimitEnforcedPerNormalizedIdentityAcrossDifferentIps() throws Exception {
        String targetEmail = "distributed-victim@ledger.com";

        for (int i = 1; i <= 5; i++) {
            String ip = "10.0.1." + i;
            LoginRequestDto badRequest = new LoginRequestDto(targetEmail, "WrongPass123!");
            mockMvc.perform(post("/api/v1/auth/login")
                            .with(req -> { req.setRemoteAddr(ip); return req; })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(badRequest)))
                    .andExpect(status().isUnauthorized());
        }

        LoginRequestDto normalizedAttempt = new LoginRequestDto("DISTRIBUTED-VICTIM@LEDGER.COM", "AnotherPass123!");
        mockMvc.perform(post("/api/v1/auth/login")
                        .with(req -> { req.setRemoteAddr("10.0.1.99"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(normalizedAttempt)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
    }

    @Test
    @DisplayName("6. Changing only IP or email cannot bypass combined protection")
    void changingOnlyOneDimensionCannotTriviallyBypassProtection() throws Exception {
        String blockedIp = "192.168.10.6";

        for (int i = 0; i < 5; i++) {
            LoginRequestDto badRequest = new LoginRequestDto("user" + i + "@ledger.com", "BadPass123!");
            mockMvc.perform(post("/api/v1/auth/login")
                            .with(req -> { req.setRemoteAddr(blockedIp); return req; })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(badRequest)))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(post("/api/v1/auth/login")
                        .with(req -> { req.setRemoteAddr(blockedIp); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequestDto("completelynew@ledger.com", "Pass123!"))))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("7. Spoofed X-Forwarded-For header cannot bypass IP limiter when untrusted")
    void spoofedForwardedForHeaderCannotBypassRateLimiter() throws Exception {
        String socketIp = "192.168.10.7";

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .with(req -> { req.setRemoteAddr(socketIp); return req; })
                            .header("X-Forwarded-For", "spoofed-client-ip-" + i)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new LoginRequestDto("spoof@ledger.com", "WrongPass123!"))))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(post("/api/v1/auth/login")
                        .with(req -> { req.setRemoteAddr(socketIp); return req; })
                        .header("X-Forwarded-For", "spoofed-client-ip-99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequestDto("spoof@ledger.com", "WrongPass123!"))))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("8. Repeated signup requests from same IP return 429 while normal signup below limit succeeds")
    void repeatedSignupsFromSameIpEventuallyReturn429() throws Exception {
        String clientIp = "192.168.10.8";

        for (int i = 0; i < 10; i++) {
            SignupRequestDto signup = new SignupRequestDto("User " + i, "signup" + i + "@ledger.com", "Password123!");
            mockMvc.perform(post("/api/v1/auth/signup")
                            .with(req -> { req.setRemoteAddr(clientIp); return req; })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(signup)))
                    .andExpect(status().isCreated());
        }

        SignupRequestDto rateLimitedSignup = new SignupRequestDto("User 11", "signup11@ledger.com", "Password123!");
        mockMvc.perform(post("/api/v1/auth/signup")
                        .with(req -> { req.setRemoteAddr(clientIp); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rateLimitedSignup)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.status", is(429)))
                .andExpect(jsonPath("$.message", containsString("Too many requests")));

        SignupRequestDto differentIpSignup = new SignupRequestDto("User Other", "otherip@ledger.com", "Password123!");
        mockMvc.perform(post("/api/v1/auth/signup")
                        .with(req -> { req.setRemoteAddr("192.168.10.9"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(differentIpSignup)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("9. Counters increment atomically in Redis with proper TTL")
    void redisCountersIncrementAtomicallyWithTtl() throws Exception {
        String clientIp = "192.168.10.10";
        String email = "atomic@ledger.com";

        mockMvc.perform(post("/api/v1/auth/login")
                        .with(req -> { req.setRemoteAddr(clientIp); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequestDto(email, "WrongPass123!"))))
                .andExpect(status().isUnauthorized());

        String ipKey = rateLimiterService.loginIpKey(clientIp);
        String identityKey = rateLimiterService.loginIdentityKey(email);

        assertThat(redisTemplate.opsForValue().get(ipKey)).isEqualTo("1");
        assertThat(redisTemplate.opsForValue().get(identityKey)).isEqualTo("1");

        Long ipTtl = redisTemplate.getExpire(ipKey);
        Long identityTtl = redisTemplate.getExpire(identityKey);

        assertThat(ipTtl).isGreaterThan(0).isLessThanOrEqualTo(60);
        assertThat(identityTtl).isGreaterThan(0).isLessThanOrEqualTo(60);
    }

    @Test
    @DisplayName("10. Expired counters allow requests to proceed again")
    void expiredCountersAllowRequestsAgain() throws Exception {
        String clientIp = "192.168.10.11";
        String email = "expiry@ledger.com";

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .with(req -> { req.setRemoteAddr(clientIp); return req; })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new LoginRequestDto(email, "WrongPass123!"))))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(post("/api/v1/auth/login")
                        .with(req -> { req.setRemoteAddr(clientIp); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequestDto(email, "WrongPass123!"))))
                .andExpect(status().isTooManyRequests());

        clearRedis();

        mockMvc.perform(post("/api/v1/auth/login")
                        .with(req -> { req.setRemoteAddr(clientIp); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequestDto(email, "WrongPass123!"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("11. Passwords or sensitive credentials are never stored in Redis")
    void noPasswordsOrSensitiveCredentialsStoredInRedis() throws Exception {
        String sensitivePassword = "SuperUniqueSecretPassword987!";
        String clientIp = "192.168.10.12";
        String email = "secretcheck@ledger.com";

        mockMvc.perform(post("/api/v1/auth/login")
                        .with(req -> { req.setRemoteAddr(clientIp); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequestDto(email, sensitivePassword))))
                .andExpect(status().isUnauthorized());

        Set<String> keys = redisTemplate.keys("*");
        assertThat(keys).isNotEmpty();

        for (String key : keys) {
            assertThat(key).doesNotContain(sensitivePassword);
            String value = redisTemplate.opsForValue().get(key);
            if (value != null) {
                assertThat(value).doesNotContain(sensitivePassword);
            }
        }
    }

    @Test
    @DisplayName("12. Financial operations succeed under normal volume and preserve idempotency")
    void financialOperationsSucceedUnderNormalVolumeAndPreserveIdempotency() throws Exception {
        SignupRequestDto signup = new SignupRequestDto("Alice Fin", "alice.fin@ledger.com", "FinPass123!");
        userAuthService.signup(signup);

        User alice = userRepository.findByEmail("alice.fin@ledger.com").orElseThrow();
        User bob = userRepository.save(new User("bob.fin@ledger.com", "Bob Fin"));

        Account aliceAccount = accountRepository.save(new Account(alice, "INR", new BigDecimal("1000.0000")));
        Account bobAccount = accountRepository.save(new Account(bob, "INR", new BigDecimal("500.0000")));

        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequestDto("alice.fin@ledger.com", "FinPass123!"))))
                .andExpect(status().isOk())
                .andReturn();

        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);
        assertThat(session).isNotNull();

        TransferRequestDto transfer = new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("100.0000"),
                "INR"
        );

        String idempotencyKey = "rl-test-transfer-001";

        mockMvc.perform(post("/api/v1/transfers")
                        .session(session)
                        .with(csrf())
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(transfer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("COMPLETED")));

        mockMvc.perform(post("/api/v1/transfers")
                        .session(session)
                        .with(csrf())
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(transfer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("COMPLETED")));

        Account verifiedAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        Account verifiedBob = accountRepository.findById(bobAccount.getId()).orElseThrow();

        assertThat(verifiedAlice.getBalance()).isEqualByComparingTo(new BigDecimal("900.0000"));
        assertThat(verifiedBob.getBalance()).isEqualByComparingTo(new BigDecimal("600.0000"));
        assertThat(ledgerEntryRepository.count()).isEqualTo(2);
    }
}
