package com.parth.ledger.config;

import com.parth.ledger.BaseIntegrationTest;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("V11 HikariCP Configuration Integration Tests")
class HikariConfigurationIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("1. HikariCP baseline pool configuration properties are applied correctly")
    void hikariBaselinePoolConfigurationApplied() {
        assertThat(dataSource).isInstanceOf(HikariDataSource.class);
        HikariDataSource hikariDataSource = (HikariDataSource) dataSource;

        assertThat(hikariDataSource.getMaximumPoolSize()).isEqualTo(30);
        assertThat(hikariDataSource.getConnectionTimeout()).isEqualTo(30000);
    }
}
