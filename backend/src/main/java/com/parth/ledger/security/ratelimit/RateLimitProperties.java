package com.parth.ledger.security.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for Redis-backed distributed rate limiting.
 * Supports configurable thresholds for authentication, registration, and financial mutations,
 * including a dedicated load-test configuration for elevated mutation workloads.
 */
@ConfigurationProperties(prefix = "ledger.rate-limit")
public record RateLimitProperties(
        boolean enabled,
        boolean trustForwardedHeaders,
        boolean failOpen,
        LimitConfig login,
        LimitConfig signup,
        LimitConfig financial,
        LoadTestLimitConfig loadTest
) {
    public RateLimitProperties {
        if (login == null || login.maxAttempts() <= 0) {
            login = new LimitConfig(5, login == null || login.windowSeconds() <= 0 ? 60 : login.windowSeconds());
        }
        if (signup == null || signup.maxAttempts() <= 0) {
            signup = new LimitConfig(10, signup == null || signup.windowSeconds() <= 0 ? 60 : signup.windowSeconds());
        }
        if (financial == null || financial.maxAttempts() <= 0) {
            financial = new LimitConfig(100, financial == null || financial.windowSeconds() <= 0 ? 60 : financial.windowSeconds());
        }
        if (loadTest == null) {
            loadTest = new LoadTestLimitConfig(false, new LimitConfig(1000, 60));
        } else {
            LimitConfig loadTestFin = loadTest.financial();
            if (loadTestFin == null || loadTestFin.maxAttempts() <= 0) {
                loadTestFin = new LimitConfig(1000, loadTestFin == null || loadTestFin.windowSeconds() <= 0 ? 60 : loadTestFin.windowSeconds());
            }
            loadTest = new LoadTestLimitConfig(loadTest.enabled(), loadTestFin);
        }
    }

    /**
     * Resolves the active financial rate limit threshold.
     * When load-test mode is explicitly enabled, returns the load-test threshold.
     * Otherwise, returns the standard production financial limit (default 100 requests / 60 seconds).
     */
    public LimitConfig effectiveFinancial() {
        if (loadTest != null && loadTest.enabled() && loadTest.financial() != null) {
            return loadTest.financial();
        }
        return financial();
    }

    /**
     * Load-test rate limiting configuration.
     *
     * @param enabled   whether the load-test override is active (default false)
     * @param financial custom threshold for financial mutations during load testing
     */
    public record LoadTestLimitConfig(
            boolean enabled,
            LimitConfig financial
    ) {
        public LoadTestLimitConfig {
            if (financial == null || financial.maxAttempts() <= 0) {
                financial = new LimitConfig(1000, financial == null || financial.windowSeconds() <= 0 ? 60 : financial.windowSeconds());
            }
        }
    }

    /**
     * Rate limit configuration specifying maximum attempts within a sliding or fixed time window.
     *
     * @param maxAttempts   maximum allowed attempts before 429 is returned
     * @param windowSeconds time window duration in seconds
     */
    public record LimitConfig(int maxAttempts, int windowSeconds) {
        public LimitConfig {
            if (windowSeconds <= 0) {
                windowSeconds = 60;
            }
        }
    }
}
