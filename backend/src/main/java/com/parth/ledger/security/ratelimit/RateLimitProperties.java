package com.parth.ledger.security.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ledger.rate-limit")
public record RateLimitProperties(
        boolean enabled,
        boolean trustForwardedHeaders,
        boolean failOpen,
        LimitConfig login,
        LimitConfig signup,
        LimitConfig financial
) {
    public RateLimitProperties {
        if (login == null) login = new LimitConfig(5, 60);
        if (signup == null) signup = new LimitConfig(10, 60);
        if (financial == null) financial = new LimitConfig(100, 60);
    }

    public record LimitConfig(int maxAttempts, int windowSeconds) {
        public LimitConfig {
            if (maxAttempts <= 0) maxAttempts = 5;
            if (windowSeconds <= 0) windowSeconds = 60;
        }
    }
}
