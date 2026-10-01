package com.parth.ledger.observability.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

/**
 * Health indicator for Redis, explicitly honoring its auxiliary role in the Financial Ledger Engine.
 *
 * PostgreSQL is authoritative; Redis serves only auxiliary fast-path cache and rate limiting functions.
 * When Redis is unavailable, the ledger engine safely fails open to PostgreSQL.
 * Consequently, Redis connectivity loss does not mark the overall application DOWN.
 *
 * Health details omit connection strings, passwords, and sensitive credentials.
 */
@Component("redis")
public class AuxiliaryRedisHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(AuxiliaryRedisHealthIndicator.class);

    private final RedisConnectionFactory redisConnectionFactory;

    public AuxiliaryRedisHealthIndicator(RedisConnectionFactory redisConnectionFactory) {
        this.redisConnectionFactory = redisConnectionFactory;
    }

    @Override
    public Health health() {
        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            String ping = connection.ping();
            return Health.up()
                    .withDetail("role", "auxiliary")
                    .withDetail("status", "UP")
                    .withDetail("ping", ping != null ? ping : "PONG")
                    .build();
        } catch (Exception e) {
            log.warn("Redis auxiliary connectivity check failed: {}. Engine safely continues with PostgreSQL authoritative operations.",
                    e.getMessage());
            return Health.up()
                    .withDetail("role", "auxiliary")
                    .withDetail("status", "UNAVAILABLE")
                    .withDetail("resilience", "Failing open; PostgreSQL authoritative path active")
                    .build();
        }
    }
}
