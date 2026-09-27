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
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.web.support.WebTestUtils;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@ActiveProfiles("load-test")
@DisplayName("Load-Test Profile Rate Limiting Tests")
class LoadTestRateLimitingIntegrationTest extends BaseIntegrationTest {

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
    @DisplayName("1. Load-test profile activates higher threshold (1000/60s) while base config remains 100/60s")
    void loadTestProfileActivatesHigherThreshold() {
        RateLimitProperties props = rateLimiterService.getProperties();

        // Base financial limit remains 100 requests / 60 seconds
        assertThat(props.financial().maxAttempts()).isEqualTo(100);
        assertThat(props.financial().windowSeconds()).isEqualTo(60);

        // Load-test override is explicitly active
        assertThat(props.loadTest().enabled()).isTrue();
        assertThat(props.loadTest().financial().maxAttempts()).isEqualTo(1000);
        assertThat(props.loadTest().financial().windowSeconds()).isEqualTo(60);

        // Effective limit uses the load-test threshold
        RateLimitProperties.LimitConfig effective = rateLimiterService.getEffectiveFinancialLimit();
        assertThat(effective.maxAttempts()).isEqualTo(1000);
        assertThat(effective.windowSeconds()).isEqualTo(60);
    }

    @Test
    @DisplayName("2. Financial mutations succeed beyond the normal 100 limit under load-test profile")
    void financialMutationsSucceedBeyondDefaultLimitUnderLoadTestProfile() {
        String testIdentifier = "user:loadtest-volume-" + UUID.randomUUID();

        // Normal limit of 100 would block request 101.
        // Under load-test profile, 150 requests all succeed.
        for (int i = 1; i <= 150; i++) {
            rateLimiterService.checkAndRecordFinancial(testIdentifier);
        }

        // Verify key exists in Redis with proper counter
        String redisKey = rateLimiterService.financialKey(testIdentifier);
        String val = redisTemplate.opsForValue().get(redisKey);
        assertThat(val).isEqualTo("150");
    }

    @Test
    @DisplayName("3. Rate limiting is not disabled: still returns 429 after exceeding load-test threshold (1000)")
    void rateLimitingEnforcedAfterExceedingLoadTestThreshold() {
        String testIdentifier = "user:loadtest-cap-" + UUID.randomUUID();

        // Send 1000 requests to hit the load-test threshold
        for (int i = 1; i <= 1000; i++) {
            rateLimiterService.checkAndRecordFinancial(testIdentifier);
        }

        // 1001st request must trigger 429 RateLimitExceededException
        org.junit.jupiter.api.Assertions.assertThrows(
                RateLimitExceededException.class,
                () -> rateLimiterService.checkAndRecordFinancial(testIdentifier)
        );
    }

    @Test
    @DisplayName("4. HTTP transfers succeed through full security stack under load-test profile and eventually 429 when cap reached")
    void httpTransfersEnforceLoadTestThresholdThroughMvcStack() throws Exception {
        SignupRequestDto signup = new SignupRequestDto("Load Tester", "loadtester@ledger.com", "LoadPass123!");
        userAuthService.signup(signup);

        User sender = userRepository.findByEmail("loadtester@ledger.com").orElseThrow();
        User receiver = userRepository.save(new User("receiver@ledger.com", "Receiver User"));

        Account senderAccount = accountRepository.save(new Account(sender, "INR", new BigDecimal("100000.0000")));
        Account receiverAccount = accountRepository.save(new Account(receiver, "INR", new BigDecimal("1000.0000")));

        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequestDto("loadtester@ledger.com", "LoadPass123!"))))
                .andExpect(status().isOk())
                .andReturn();

        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);
        assertThat(session).isNotNull();

        // Pre-fill Redis rate limiter counter for this authenticated user to (threshold - 2) = 998
        String userIdentifier = "user:loadtester@ledger.com";
        String redisKey = rateLimiterService.financialKey(userIdentifier);
        redisTemplate.opsForValue().set(redisKey, "998");

        TransferRequestDto transfer = new TransferRequestDto(
                senderAccount.getId(),
                receiverAccount.getId(),
                new BigDecimal("10.0000"),
                "INR"
        );

        // Request 999: succeeds (HTTP 200)
        mockMvc.perform(post("/api/v1/transfers")
                        .session(session)
                        .with(csrf())
                        .header("Idempotency-Key", "load-test-tx-999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(transfer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("COMPLETED")));

        // Request 1000: succeeds (HTTP 200, at threshold)
        mockMvc.perform(post("/api/v1/transfers")
                        .session(session)
                        .with(csrf())
                        .header("Idempotency-Key", "load-test-tx-1000")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(transfer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("COMPLETED")));

        // Request 1001: exceeds threshold (1000) -> returns HTTP 429 Too Many Requests with Retry-After header
        mockMvc.perform(post("/api/v1/transfers")
                        .session(session)
                        .with(csrf())
                        .header("Idempotency-Key", "load-test-tx-1001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(transfer)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.status", is(429)))
                .andExpect(jsonPath("$.message", containsString("Too many requests")));

        // Verify ledger consistency: exactly 2 transfers succeeded
        Account verifiedSender = accountRepository.findById(senderAccount.getId()).orElseThrow();
        assertThat(verifiedSender.getBalance()).isEqualByComparingTo(new BigDecimal("99980.0000"));
        assertThat(ledgerEntryRepository.count()).isEqualTo(4); // 2 transfers * 2 entries
    }
}
