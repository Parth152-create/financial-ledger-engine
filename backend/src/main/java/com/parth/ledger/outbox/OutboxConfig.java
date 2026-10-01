package com.parth.ledger.outbox;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring configuration enabling scheduling and loading outbox configuration properties.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxConfig {
}
