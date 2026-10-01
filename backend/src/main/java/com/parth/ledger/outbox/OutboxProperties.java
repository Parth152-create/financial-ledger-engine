package com.parth.ledger.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the Transactional Outbox processor and retry policies.
 */
@ConfigurationProperties(prefix = "ledger.outbox")
public record OutboxProperties(
        boolean enabled,
        long pollIntervalMs,
        int batchSize,
        int maxAttempts,
        long baseDelaySeconds,
        long maxDelaySeconds
) {
    public OutboxProperties {
        if (pollIntervalMs <= 0) {
            pollIntervalMs = 1000L;
        }
        if (batchSize <= 0) {
            batchSize = 20;
        }
        if (maxAttempts <= 0) {
            maxAttempts = 5;
        }
        if (baseDelaySeconds <= 0) {
            baseDelaySeconds = 2L;
        }
        if (maxDelaySeconds <= 0) {
            maxDelaySeconds = 300L;
        }
    }

    public static OutboxProperties defaults() {
        return new OutboxProperties(true, 1000L, 20, 5, 2L, 300L);
    }
}
