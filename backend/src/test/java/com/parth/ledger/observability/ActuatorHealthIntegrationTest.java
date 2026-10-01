package com.parth.ledger.observability;

import com.parth.ledger.BaseIntegrationTest;
import com.parth.ledger.observability.health.AuxiliaryRedisHealthIndicator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@DisplayName("V2.4 Actuator & Auxiliary Health Check Integration Tests")
class ActuatorHealthIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuxiliaryRedisHealthIndicator redisHealthIndicator;

    @Test
    @DisplayName("18 & 19. Health check returns 200 UP with DB and auxiliary Redis status")
    void healthCheckReturnsUpWithComponents() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("UP")))
                .andExpect(jsonPath("$.components.db.status", is("UP")))
                .andExpect(jsonPath("$.components.redis.status", is("UP")))
                .andExpect(jsonPath("$.components.redis.details.role", is("auxiliary")))
                // Ensure no credentials, connection strings, or passwords leak
                .andExpect(jsonPath("$.components.db.details.password").doesNotExist())
                .andExpect(jsonPath("$.components.redis.details.password").doesNotExist())
                .andExpect(jsonPath("$.components.redis.details.uri").doesNotExist());
    }

    @Test
    @DisplayName("20. Auxiliary Redis outage does not mark system DOWN; fails open with status UNAVAILABLE")
    void redisOutageDoesNotMarkSystemDown() {
        RedisConnectionFactory mockFactory = mock(RedisConnectionFactory.class);
        when(mockFactory.getConnection()).thenThrow(new RedisConnectionFailureException("Simulated Redis network partition"));

        AuxiliaryRedisHealthIndicator resilientIndicator = new AuxiliaryRedisHealthIndicator(mockFactory);
        Health health = resilientIndicator.health();

        // The auxiliary health indicator MUST remain UP so that actuator health returns HTTP 200
        assertThat(health.getStatus().getCode()).isEqualTo("UP");
        assertThat(health.getDetails()).containsEntry("role", "auxiliary");
        assertThat(health.getDetails()).containsEntry("status", "UNAVAILABLE");
        assertThat(health.getDetails().get("resilience").toString()).contains("Failing open");
        // Must never leak connection details
        assertThat(health.getDetails().toString()).doesNotContain("password", "secret", "uri");
    }

    @Test
    @DisplayName("Actuator info endpoint is publicly accessible and returns 200 OK")
    void infoEndpointPubliclyAccessible() throws Exception {
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isOk());
    }
}
