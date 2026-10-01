package com.parth.ledger.recurring;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RecurringTransferProperties.class)
public class RecurringTransferConfig {
}
