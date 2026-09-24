package com.parth.ledger.common.filter;

import com.parth.ledger.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@DisplayName("V11 Correlation ID & MDC Filter Integration Tests")
class CorrelationIdFilterIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("1. Supplied X-Request-Id is preserved and returned in response")
    void suppliedRequestIdPreserved() throws Exception {
        mockMvc.perform(get("/actuator/health")
                        .header("X-Request-Id", "req-client-supplied-001"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", "req-client-supplied-001"))
                .andExpect(header().string("X-Correlation-Id", "req-client-supplied-001"));
    }

    @Test
    @DisplayName("2. Supplied X-Correlation-Id is used when X-Request-Id is absent")
    void suppliedCorrelationIdUsedWhenRequestIdAbsent() throws Exception {
        mockMvc.perform(get("/actuator/health")
                        .header("X-Correlation-Id", "corr-client-supplied-002"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", "corr-client-supplied-002"))
                .andExpect(header().string("X-Correlation-Id", "corr-client-supplied-002"));
    }

    @Test
    @DisplayName("3. UUID is generated when neither header is supplied")
    void generatedRequestIdWhenNeitherSupplied() throws Exception {
        MvcResult result = mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Request-Id"))
                .andReturn();

        String generatedId = result.getResponse().getHeader("X-Request-Id");
        assertThat(generatedId).isNotBlank();
        // Verify valid UUID
        UUID parsed = UUID.fromString(generatedId);
        assertThat(parsed).isNotNull();
    }

    @Test
    @DisplayName("4. MDC is always cleaned up after request execution")
    void mdcCleanedUpAfterRequest() throws Exception {
        mockMvc.perform(get("/actuator/health")
                        .header("X-Request-Id", "req-for-mdc-cleanup"))
                .andExpect(status().isOk());

        // On the caller thread, MDC must not retain any lingering correlationId
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("5. Concurrent requests maintain correlation ID isolation without crosstalk")
    void concurrentRequestsIsolated() throws Exception {
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(threadCount);
        ConcurrentHashMap<String, String> resultMap = new ConcurrentHashMap<>();
        AtomicBoolean allSucceeded = new AtomicBoolean(true);

        for (int i = 0; i < threadCount; i++) {
            final String expectedId = "req-concurrent-" + i + "-" + UUID.randomUUID();
            executor.submit(() -> {
                try {
                    startLatch.await();
                    MvcResult res = mockMvc.perform(get("/actuator/health")
                                    .header("X-Request-Id", expectedId))
                            .andExpect(status().isOk())
                            .andReturn();
                    String actualId = res.getResponse().getHeader("X-Request-Id");
                    resultMap.put(expectedId, actualId);
                } catch (Exception e) {
                    allSucceeded.set(false);
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = finishLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(allSucceeded.get()).isTrue();
        assertThat(resultMap).hasSize(threadCount);
        resultMap.forEach((expected, actual) -> assertThat(actual).isEqualTo(expected));
    }
}
