package com.parth.ledger.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Standard configuration providing the authoritative application Clock.
 * Ensures consistent temporal semantics across financial ledger operations,
 * specifically guaranteeing that daily policy limits reset authoritatively at 00:00 UTC.
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
