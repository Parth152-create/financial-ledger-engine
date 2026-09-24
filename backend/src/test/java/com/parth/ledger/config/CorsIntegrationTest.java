package com.parth.ledger.config;

import com.parth.ledger.BaseIntegrationTest;
import com.parth.ledger.user.User;
import com.parth.ledger.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@DisplayName("V11 CORS Integration Tests")
class CorsIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        userRepository.findByEmail("alice@ledger.com")
                .orElseGet(() -> userRepository.save(new User("alice@ledger.com", "Alice")));
    }

    @AfterEach
    void tearDown() {
        userRepository.findByEmail("alice@ledger.com")
                .ifPresent(userRepository::delete);
    }

    @Test
    @DisplayName("1. Preflight OPTIONS request from allowed frontend origin succeeds (200 OK)")
    void preflightAllowedOriginSucceeds() throws Exception {
        mockMvc.perform(options("/api/v1/accounts")
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "Content-Type,Idempotency-Key,X-Request-Id,X-Correlation-Id"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"))
                .andExpect(header().string("Access-Control-Allow-Methods", containsString("POST")));
    }

    @Test
    @DisplayName("2. Preflight OPTIONS includes required allowed headers")
    void preflightAllowedHeadersVerified() throws Exception {
        mockMvc.perform(options("/api/v1/transfers")
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "Content-Type,Idempotency-Key,X-Request-Id,X-Correlation-Id"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
                .andExpect(header().string("Access-Control-Allow-Headers", containsString("Idempotency-Key")))
                .andExpect(header().string("Access-Control-Allow-Headers", containsString("X-Request-Id")))
                .andExpect(header().string("Access-Control-Allow-Headers", containsString("X-Correlation-Id")));
    }

    @Test
    @DisplayName("3. Preflight OPTIONS includes exposed headers X-Request-Id and X-Correlation-Id")
    void preflightExposedHeadersVerified() throws Exception {
        mockMvc.perform(options("/api/v1/accounts")
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Expose-Headers", containsString("X-Request-Id")))
                .andExpect(header().string("Access-Control-Expose-Headers", containsString("X-Correlation-Id")));
    }

    @Test
    @DisplayName("4. Request from disallowed origin is rejected (403 Forbidden)")
    void disallowedOriginRejected() throws Exception {
        mockMvc.perform(options("/api/v1/accounts")
                        .header("Origin", "http://malicious-origin.com")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("5. Existing authenticated endpoint security remains intact under CORS")
    void unauthenticatedApiRequestReturns401UnderCors() throws Exception {
        mockMvc.perform(get("/api/v1/accounts")
                        .header("Origin", "http://localhost:3000"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("6. Authenticated request from allowed origin succeeds with CORS headers")
    void authenticatedRequestFromAllowedOriginSucceeds() throws Exception {
        mockMvc.perform(get("/api/v1/accounts")
                        .header("Origin", "http://localhost:3000")
                        .with(user("alice@ledger.com")))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }
}
