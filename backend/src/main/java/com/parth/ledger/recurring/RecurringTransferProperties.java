package com.parth.ledger.recurring;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the background recurring transfer processor.
 */
@ConfigurationProperties(prefix = "ledger.recurring-transfers")
public record RecurringTransferProperties(
        boolean enabled,
        long pollIntervalMs,
        int batchSize
) {
    public RecurringTransferProperties {
        if (pollIntervalMs <= 0) {
            pollIntervalMs = 5000L;
        }
        if (batchSize <= 0) {
            batchSize = 20;
        }
    }

    public static RecurringTransferProperties defaults() {
        return new RecurringTransferProperties(true, 5000L, 20);
    }
}
