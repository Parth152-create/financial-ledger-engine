package com.parth.ledger.security.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Rate Limit Properties Unit Tests")
class RateLimitPropertiesTest {

    @Test
    @DisplayName("Default configuration sets financial limit to 100 requests / 60 seconds with load-test disabled")
    void defaultConfigurationMaintains100Per60Seconds() {
        RateLimitProperties props = new RateLimitProperties(
                true,
                false,
                true,
                null,
                null,
                null,
                null
        );

        assertThat(props.login().maxAttempts()).isEqualTo(5);
        assertThat(props.login().windowSeconds()).isEqualTo(60);

        assertThat(props.signup().maxAttempts()).isEqualTo(10);
        assertThat(props.signup().windowSeconds()).isEqualTo(60);

        assertThat(props.financial().maxAttempts()).isEqualTo(100);
        assertThat(props.financial().windowSeconds()).isEqualTo(60);

        assertThat(props.loadTest().enabled()).isFalse();
        assertThat(props.loadTest().financial().maxAttempts()).isEqualTo(1000);
        assertThat(props.loadTest().financial().windowSeconds()).isEqualTo(60);

        // Effective limit must be the default 100/60s when load-test is disabled
        assertThat(props.effectiveFinancial().maxAttempts()).isEqualTo(100);
        assertThat(props.effectiveFinancial().windowSeconds()).isEqualTo(60);
    }

    @Test
    @DisplayName("Explicitly enabled load-test configuration uses higher financial threshold")
    void loadTestConfigurationUsesHigherThresholdWhenEnabled() {
        RateLimitProperties props = new RateLimitProperties(
                true,
                false,
                true,
                new RateLimitProperties.LimitConfig(5, 60),
                new RateLimitProperties.LimitConfig(10, 60),
                new RateLimitProperties.LimitConfig(100, 60),
                new RateLimitProperties.LoadTestLimitConfig(
                        true,
                        new RateLimitProperties.LimitConfig(2500, 120)
                )
        );

        assertThat(props.financial().maxAttempts()).isEqualTo(100);
        assertThat(props.financial().windowSeconds()).isEqualTo(60);

        assertThat(props.loadTest().enabled()).isTrue();
        assertThat(props.loadTest().financial().maxAttempts()).isEqualTo(2500);
        assertThat(props.loadTest().financial().windowSeconds()).isEqualTo(120);

        // Effective limit must resolve to load-test threshold
        assertThat(props.effectiveFinancial().maxAttempts()).isEqualTo(2500);
        assertThat(props.effectiveFinancial().windowSeconds()).isEqualTo(120);
    }

    @Test
    @DisplayName("Disabled load-test configuration keeps default limit even if load-test threshold is specified")
    void disabledLoadTestConfigurationDoesNotOverrideDefault() {
        RateLimitProperties props = new RateLimitProperties(
                true,
                false,
                true,
                new RateLimitProperties.LimitConfig(5, 60),
                new RateLimitProperties.LimitConfig(10, 60),
                new RateLimitProperties.LimitConfig(100, 60),
                new RateLimitProperties.LoadTestLimitConfig(
                        false,
                        new RateLimitProperties.LimitConfig(5000, 60)
                )
        );

        assertThat(props.loadTest().enabled()).isFalse();
        assertThat(props.effectiveFinancial().maxAttempts()).isEqualTo(100);
        assertThat(props.effectiveFinancial().windowSeconds()).isEqualTo(60);
    }

    @Test
    @DisplayName("Missing or non-positive maxAttempts safely defaults without weakening security")
    void nonPositiveMaxAttemptsDefaultsSafely() {
        RateLimitProperties props = new RateLimitProperties(
                true,
                false,
                true,
                new RateLimitProperties.LimitConfig(0, 0),
                new RateLimitProperties.LimitConfig(-1, -1),
                new RateLimitProperties.LimitConfig(0, 0),
                new RateLimitProperties.LoadTestLimitConfig(
                        true,
                        new RateLimitProperties.LimitConfig(0, 0)
                )
        );

        assertThat(props.login().maxAttempts()).isEqualTo(5);
        assertThat(props.login().windowSeconds()).isEqualTo(60);

        assertThat(props.signup().maxAttempts()).isEqualTo(10);
        assertThat(props.signup().windowSeconds()).isEqualTo(60);

        assertThat(props.financial().maxAttempts()).isEqualTo(100);
        assertThat(props.financial().windowSeconds()).isEqualTo(60);

        assertThat(props.effectiveFinancial().maxAttempts()).isEqualTo(1000);
        assertThat(props.effectiveFinancial().windowSeconds()).isEqualTo(60);
    }
}
