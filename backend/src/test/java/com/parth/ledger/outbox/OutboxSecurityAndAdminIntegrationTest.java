package com.parth.ledger.outbox;

import com.parth.ledger.BaseIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class OutboxSecurityAndAdminIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @BeforeEach
    void setUp() {
        // Seed a few outbox events
        OutboxEvent pendingEvent = new OutboxEvent(
                UUID.randomUUID(),
                OutboxAggregateType.TRANSACTION,
                UUID.randomUUID(),
                OutboxEventType.TRANSFER_COMPLETED,
                Map.of("amount", "500.0000", "currency", "INR", "secretField", "super-secret-token"),
                OutboxStatus.PENDING,
                0,
                Instant.now(),
                null,
                null
        );

        OutboxEvent processedEvent = new OutboxEvent(
                UUID.randomUUID(),
                OutboxAggregateType.ACCOUNT,
                UUID.randomUUID(),
                OutboxEventType.ACCOUNT_CREATED,
                Map.of("accountNumber", "ACCT-12345"),
                OutboxStatus.PROCESSED,
                1,
                Instant.now(),
                Instant.now(),
                null
        );

        outboxEventRepository.save(pendingEvent);
        outboxEventRepository.save(processedEvent);
    }

    @Test
    @DisplayName("Unauthenticated request to /api/v1/admin/outbox returns 401 Unauthorized")
    void unauthenticatedRequestReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/outbox")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Non-admin user request to /api/v1/admin/outbox returns 403 Forbidden")
    void normalUserRequestReturns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/outbox")
                        .with(user("user@ledger.com").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Admin user request to /api/v1/admin/outbox returns 200 OK with paginated list")
    void adminUserRequestReturns200() throws Exception {
        mockMvc.perform(get("/api/v1/admin/outbox")
                        .with(user("admin@ledger.com").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.page", is(0)))
                .andExpect(jsonPath("$.totalElements", is(2)));
    }

    @Test
    @DisplayName("Admin query filters correctly by status")
    void adminQueryFiltersByStatus() throws Exception {
        mockMvc.perform(get("/api/v1/admin/outbox")
                        .param("status", "PROCESSED")
                        .with(user("admin@ledger.com").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].status", is("PROCESSED")))
                .andExpect(jsonPath("$.content[0].eventType", is("ACCOUNT_CREATED")));
    }

    @Test
    @DisplayName("Admin query filters correctly by eventType")
    void adminQueryFiltersByEventType() throws Exception {
        mockMvc.perform(get("/api/v1/admin/outbox")
                        .param("eventType", "TRANSFER_COMPLETED")
                        .with(user("admin@ledger.com").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].eventType", is("TRANSFER_COMPLETED")))
                .andExpect(jsonPath("$.content[0].status", is("PENDING")));
    }

    @Test
    @DisplayName("Admin response does NOT expose raw payloads, secrets, or internal metadata")
    void adminResponseDoesNotExposeRawPayloadsOrSecrets() throws Exception {
        mockMvc.perform(get("/api/v1/admin/outbox")
                        .param("status", "PENDING")
                        .with(user("admin@ledger.com").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].payload").doesNotExist())
                .andExpect(jsonPath("$.content[0].secretField").doesNotExist())
                .andExpect(jsonPath("$.content[0].lastError", nullValue()));
    }
}
