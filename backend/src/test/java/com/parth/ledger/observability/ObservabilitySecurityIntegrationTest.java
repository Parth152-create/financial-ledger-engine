package com.parth.ledger.observability;

import com.parth.ledger.BaseIntegrationTest;
import com.parth.ledger.account.Account;
import com.parth.ledger.account.AccountRepository;
import com.parth.ledger.observability.logging.MaskingUtils;
import com.parth.ledger.system.SystemFundingService;
import com.parth.ledger.transaction.dto.TransferRequestDto;
import com.parth.ledger.transaction.service.TransferService;
import com.parth.ledger.user.User;
import com.parth.ledger.user.UserRepository;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import tools.jackson.databind.ObjectMapper;
import com.parth.ledger.account.AccountService;
import com.parth.ledger.account.dto.CreateAccountRequestDto;
import com.parth.ledger.observability.metrics.LedgerMetrics;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.Counter;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@DisplayName("V2.4 Observability Security & Bounded Cardinality Integration Tests")
class ObservabilitySecurityIntegrationTest extends BaseIntegrationTest {

    private static final Pattern UUID_PATTERN = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private static final Set<String> ALLOWED_LEDGER_TAG_KEYS = Set.of(
            "operation",
            "status",
            "outcome",
            "policy_type",
            "tx_type",
            "category",
            "scope",
            "method",
            "uri",
            "endpoint",
            "status_group",
            "exception",
            "event_type",
            "frequency"
    );

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private TransferService transferService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private SystemFundingService systemFundingService;

    @Autowired
    private AccountService accountService;

    @Autowired
    private ObjectMapper objectMapper;

    private Account sourceAccount;
    private Account destAccount;

    @BeforeEach
    void setUp() {
        clearRedis();
        if (jdbcTemplate != null) {
            jdbcTemplate.execute("TRUNCATE TABLE transactions CASCADE");
            jdbcTemplate.execute("TRUNCATE TABLE accounts CASCADE");
            jdbcTemplate.execute("TRUNCATE TABLE users CASCADE");
        }
        systemFundingService.bootstrapSystemFunding(SystemFundingService.DEFAULT_BOOTSTRAP_AMOUNT);

        User alice = userRepository.save(new User("alice.sec@ledger.com", "Alice"));
        User bob = userRepository.save(new User("bob.sec@ledger.com", "Bob"));

        sourceAccount = accountRepository.save(new Account(alice, "INR", new BigDecimal("10000.0000")));
        destAccount = accountRepository.save(new Account(bob, "INR", new BigDecimal("5000.0000")));
    }

    @Test
    @DisplayName("21. Actuator metrics endpoint requires authentication and ADMIN role")
    void metricsEndpointRequiresAdminRole() throws Exception {
        // Unauthenticated access -> 401 Unauthorized
        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "regular.user@ledger.com", roles = {"USER"})
    @DisplayName("21b. Regular user with ROLE_USER is forbidden from accessing /actuator/metrics")
    void metricsEndpointForbiddenForRegularUser() throws Exception {
        // Authenticated as regular user -> 403 Forbidden
        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "admin.user@ledger.com", roles = {"ADMIN"})
    @DisplayName("21c. Admin user with ROLE_ADMIN is authorized to access /actuator/metrics")
    void metricsEndpointAuthorizedForAdmin() throws Exception {
        // Authenticated as admin -> 200 OK
        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "alice.sec@ledger.com", roles = {"USER"})
    @DisplayName("22 & 23. Zero raw idempotency keys or UUIDs in metric tags; bounded cardinality policy enforced")
    void boundedCardinalityPolicyEnforced() {
        String testIdempotencyKey = "super-secret-idempotency-key-" + UUID.randomUUID();
        TransferRequestDto request = new TransferRequestDto(
                sourceAccount.getId(),
                destAccount.getId(),
                new BigDecimal("150.0000"),
                "INR"
        );

        transferService.executeTransfer(testIdempotencyKey, request);

        // Inspect all registered meters
        for (Meter meter : meterRegistry.getMeters()) {
            String meterName = meter.getId().getName();

            // We only enforce our custom ledger metrics namespace
            if (meterName.startsWith("ledger.")) {
                for (Tag tag : meter.getId().getTags()) {
                    String tagKey = tag.getKey();
                    String tagVal = tag.getValue();

                    // Tag key must belong to bounded schema
                    assertThat(ALLOWED_LEDGER_TAG_KEYS)
                            .as("Disallowed metric tag key: " + tagKey + " on meter " + meterName)
                            .contains(tagKey);

                    // Tag value must NEVER contain the raw idempotency key
                    assertThat(tagVal)
                            .as("Raw idempotency key leaked into metric tag: " + tagVal)
                            .doesNotContain("super-secret-idempotency-key");

                    // Tag value must NEVER contain a raw UUID (e.g. account ID, transaction ID)
                    assertThat(UUID_PATTERN.matcher(tagVal).matches())
                            .as("Raw UUID leaked as metric tag value in " + tagKey + "=" + tagVal)
                            .isFalse();

                    // Tag value must not contain sensitive credentials or tokens
                    if ("endpoint".equals(tagKey) || "uri".equals(tagKey)) {
                        assertThat(tagVal.toLowerCase())
                                .doesNotContain("bearer", "secret", "eyj");
                    } else {
                        assertThat(tagVal.toLowerCase())
                                .doesNotContain("password", "secret", "bearer", "token");
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("MaskingUtils correctly sanitizes accounts, UUIDs, and emails")
    void maskingUtilsVerification() {
        // Account masking
        assertThat(MaskingUtils.maskAccountNumber("123456789012")).isEqualTo("•••• 9012");
        assertThat(MaskingUtils.maskAccountNumber("1234")).isEqualTo("•••• 1234");
        assertThat(MaskingUtils.maskAccountNumber("12")).isEqualTo("•••• 12");
        assertThat(MaskingUtils.maskAccountNumber(null)).isEqualTo("•••• ----");

        // UUID masking
        UUID testUuid = UUID.fromString("71be84e6-d183-43ef-b72e-0a56eb8ffac8");
        assertThat(MaskingUtils.maskAccountId(testUuid)).isEqualTo("71be...fac8");
        assertThat(MaskingUtils.maskAccountId(UUID.fromString("00000000-0000-0000-0000-000000000001"))).isEqualTo("[SYSTEM_CLEARING]");
        assertThat(MaskingUtils.maskAccountId(UUID.fromString("00000000-0000-0000-0000-000000000002"))).isEqualTo("[SYSTEM_TREASURY]");
        assertThat(MaskingUtils.maskAccountId((UUID) null)).isEqualTo("••••");

        // Email masking
        assertThat(MaskingUtils.maskEmail("alice@example.com")).isEqualTo("a***@example.com");
        assertThat(MaskingUtils.maskEmail(null)).isEqualTo("");
    }

    @Test
    @WithMockUser(username = "alice.sec@ledger.com", roles = {"USER"})
    @DisplayName("Finding 1: Unmatched routes map to UNMATCHED; arbitrary methods map to OTHER; label cardinality is strictly bounded")
    void testBoundedRouteAndMethodCardinality() throws Exception {
        // 1. Known route -> expected normalized endpoint
        mockMvc.perform(get("/api/v1/accounts"))
                .andExpect(status().isOk());

        // 2. Unknown route A -> UNMATCHED
        mockMvc.perform(get("/random/abc123"))
                .andExpect(status().isNotFound());

        // 3. Unknown route B with completely different path -> UNMATCHED
        mockMvc.perform(get("/random/xyz987"))
                .andExpect(status().isNotFound());

        // 4. Unknown route C with user UUID -> UNMATCHED
        mockMvc.perform(get("/random/user-983472"))
                .andExpect(status().isNotFound());

        // Verify metrics have aggregated under UNMATCHED without leaking raw paths
        var unmatchedCounters = meterRegistry.find("ledger.http.requests.total")
                .tag("endpoint", "UNMATCHED")
                .tag("method", "GET")
                .counters();
        assertThat(unmatchedCounters).isNotEmpty();
        double totalUnmatched = unmatchedCounters.stream().mapToDouble(Counter::count).sum();
        assertThat(totalUnmatched).isGreaterThanOrEqualTo(3.0);

        // Verify NO metric series exists for raw paths
        assertThat(meterRegistry.find("ledger.http.requests.total").tag("endpoint", "/random/abc123").counters()).isEmpty();
        assertThat(meterRegistry.find("ledger.http.requests.total").tag("endpoint", "/random/xyz987").counters()).isEmpty();
        assertThat(meterRegistry.find("ledger.http.requests.total").tag("endpoint", "/random/user-983472").counters()).isEmpty();

        // 5. Whitelisted HTTP methods map correctly
        assertThat(LedgerMetrics.normalizeMethod("GET")).isEqualTo("GET");
        assertThat(LedgerMetrics.normalizeMethod("POST")).isEqualTo("POST");
        assertThat(LedgerMetrics.normalizeMethod("PUT")).isEqualTo("PUT");
        assertThat(LedgerMetrics.normalizeMethod("PATCH")).isEqualTo("PATCH");
        assertThat(LedgerMetrics.normalizeMethod("DELETE")).isEqualTo("DELETE");
        assertThat(LedgerMetrics.normalizeMethod("HEAD")).isEqualTo("HEAD");
        assertThat(LedgerMetrics.normalizeMethod("OPTIONS")).isEqualTo("OPTIONS");

        // 6. Arbitrary method maps to OTHER
        assertThat(LedgerMetrics.normalizeMethod("PROPFIND")).isEqualTo("OTHER");
        assertThat(LedgerMetrics.normalizeMethod("CUSTOM_METHOD")).isEqualTo("OTHER");
        assertThat(LedgerMetrics.normalizeMethod(null)).isEqualTo("OTHER");
        assertThat(LedgerMetrics.normalizeMethod("")).isEqualTo("OTHER");
    }

    @Test
    @WithMockUser(username = "alice.sec@ledger.com", roles = {"USER"})
    @DisplayName("Finding 2: Raw idempotency key, full account number, and credentials never reach logs")
    void testSensitiveFinancialIdentifiersNeverLeakToLogs() throws Exception {
        Logger rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
        listAppender.start();
        rootLogger.addAppender(listAppender);

        try {
            String rawConflictKey = "super-secret-conflict-key-XYZ12345";
            TransferRequestDto req1 = new TransferRequestDto(
                    sourceAccount.getId(),
                    destAccount.getId(),
                    new BigDecimal("100.0000"),
                    "INR"
            );
            transferService.executeTransfer(rawConflictKey, req1);

            // Trigger conflict via mockMvc so it flows through GlobalExceptionHandler
            TransferRequestDto req2 = new TransferRequestDto(
                    sourceAccount.getId(),
                    destAccount.getId(),
                    new BigDecimal("999.0000"),
                    "INR"
            );
            mockMvc.perform(post("/api/v1/transfers")
                    .with(csrf())
                    .header("Idempotency-Key", rawConflictKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(req2)))
                    .andExpect(status().isConflict());

            // Trigger account creation to verify account number masking
            mockMvc.perform(post("/api/v1/accounts")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"currency\":\"INR\"}"))
                    .andExpect(status().isCreated());

            // Verify logged events
            for (ILoggingEvent event : listAppender.list) {
                String msg = event.getFormattedMessage();

                // 1. Raw idempotency key must NEVER appear in logs
                assertThat(msg)
                        .as("Raw idempotency key leaked to logs in message: " + msg)
                        .doesNotContain(rawConflictKey);

                // 2. Full unmasked ACCT- account number must NEVER appear in logs
                // An unmasked account number has format ACCT-XXXXXXXXXXXX (12 hex/alnum chars)
                assertThat(msg)
                        .as("Full unmasked account number leaked to logs in message: " + msg)
                        .doesNotMatch(".*ACCT-[0-9A-Z]{10,}.*");

                // 3. Sensitive authentication tokens/passwords must remain absent
                assertThat(msg.toLowerCase())
                        .doesNotContain("bearer eyj", "password=");
            }
        } finally {
            rootLogger.detachAppender(listAppender);
        }
    }
}
