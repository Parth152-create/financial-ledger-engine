package com.parth.ledger.security.ratelimit;

import com.parth.ledger.observability.metrics.LedgerMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

@Service
@EnableConfigurationProperties(RateLimitProperties.class)
public class RedisRateLimiterService {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiterService.class);

    private static final String LOGIN_IP_PREFIX = "ledger:ratelimit:login:ip:";
    private static final String LOGIN_IDENTITY_PREFIX = "ledger:ratelimit:login:identity:";
    private static final String SIGNUP_IP_PREFIX = "ledger:ratelimit:signup:ip:";
    private static final String FINANCIAL_PREFIX = "ledger:ratelimit:financial:";

    private static final RedisScript<List> INCR_SCRIPT = RedisScript.of(
            "local current = redis.call('INCR', KEYS[1])\n" +
            "local ttl = redis.call('TTL', KEYS[1])\n" +
            "local expireSeconds = tonumber(ARGV[1])\n" +
            "if ttl == -1 or current == 1 then\n" +
            "    redis.call('EXPIRE', KEYS[1], expireSeconds)\n" +
            "    ttl = expireSeconds\n" +
            "end\n" +
            "return {current, ttl}\n",
            List.class
    );

    private static final RedisScript<List> CHECK_SCRIPT = RedisScript.of(
            "local val = redis.call('GET', KEYS[1])\n" +
            "local ttl = redis.call('TTL', KEYS[1])\n" +
            "local current = val and tonumber(val) or 0\n" +
            "return {current, ttl}\n",
            List.class
    );

    private final StringRedisTemplate redisTemplate;
    private final RateLimitProperties properties;
    private final LedgerMetrics ledgerMetrics;

    @Autowired
    public RedisRateLimiterService(StringRedisTemplate redisTemplate,
                                   RateLimitProperties properties,
                                   @Autowired(required = false) LedgerMetrics ledgerMetrics) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.ledgerMetrics = ledgerMetrics;
        if (properties.enabled()) {
            RateLimitProperties.LimitConfig fin = properties.effectiveFinancial();
            if (properties.loadTest() != null && properties.loadTest().enabled()) {
                log.info("Rate limiting enabled. Load-test override ACTIVE: financial limit = {}/{}s",
                        fin.maxAttempts(), fin.windowSeconds());
            } else {
                log.info("Rate limiting enabled: financial limit = {}/{}s, login limit = {}/{}s, signup limit = {}/{}s",
                        fin.maxAttempts(), fin.windowSeconds(),
                        properties.login().maxAttempts(), properties.login().windowSeconds(),
                        properties.signup().maxAttempts(), properties.signup().windowSeconds());
            }
        }
    }

    public RedisRateLimiterService(StringRedisTemplate redisTemplate, RateLimitProperties properties) {
        this(redisTemplate, properties, null);
    }

    public void checkLoginAllowed(String clientIp, String email) {
        if (!properties.enabled()) {
            return;
        }

        try {
            checkKeyLimit(loginIpKey(clientIp), properties.login().maxAttempts(), properties.login().windowSeconds());
            if (email != null && !email.isBlank()) {
                checkKeyLimit(loginIdentityKey(normalizeEmail(email)), properties.login().maxAttempts(), properties.login().windowSeconds());
            }
        } catch (RateLimitExceededException e) {
            if (ledgerMetrics != null) {
                ledgerMetrics.recordRateLimitRejection("LOGIN");
            }
            throw e;
        } catch (Exception e) {
            handleRedisFailure("checkLoginAllowed", e);
        }
    }

    public void recordFailedLogin(String clientIp, String email) {
        if (!properties.enabled()) {
            return;
        }

        try {
            executeIncr(loginIpKey(clientIp), properties.login().windowSeconds());
            if (email != null && !email.isBlank()) {
                executeIncr(loginIdentityKey(normalizeEmail(email)), properties.login().windowSeconds());
            }
        } catch (Exception e) {
            log.warn("Failed to record failed login in Redis: {}", e.getMessage());
        }
    }

    public void recordSuccessfulLogin(String clientIp, String email) {
        if (!properties.enabled()) {
            return;
        }

        try {
            redisTemplate.delete(loginIpKey(clientIp));
            if (email != null && !email.isBlank()) {
                redisTemplate.delete(loginIdentityKey(normalizeEmail(email)));
            }
        } catch (Exception e) {
            log.warn("Failed to clear login rate limit counters in Redis: {}", e.getMessage());
        }
    }

    public void checkAndRecordSignup(String clientIp) {
        if (!properties.enabled()) {
            return;
        }

        try {
            List<?> result = executeIncr(signupIpKey(clientIp), properties.signup().windowSeconds());
            if (result != null && result.size() >= 2) {
                long current = ((Number) result.get(0)).longValue();
                long ttl = ((Number) result.get(1)).longValue();
                if (current > properties.signup().maxAttempts()) {
                    long retryAfter = ttl > 0 ? ttl : properties.signup().windowSeconds();
                    throw new RateLimitExceededException("Too many signup requests. Please try again later.", retryAfter);
                }
            }
        } catch (RateLimitExceededException e) {
            if (ledgerMetrics != null) {
                ledgerMetrics.recordRateLimitRejection("SIGNUP");
            }
            throw e;
        } catch (Exception e) {
            handleRedisFailure("checkAndRecordSignup", e);
        }
    }

    public void checkAndRecordFinancial(String keyIdentifier) {
        if (!properties.enabled()) {
            return;
        }

        try {
            RateLimitProperties.LimitConfig limit = properties.effectiveFinancial();
            List<?> result = executeIncr(financialKey(keyIdentifier), limit.windowSeconds());
            if (result != null && result.size() >= 2) {
                long current = ((Number) result.get(0)).longValue();
                long ttl = ((Number) result.get(1)).longValue();
                if (current > limit.maxAttempts()) {
                    long retryAfter = ttl > 0 ? ttl : limit.windowSeconds();
                    throw new RateLimitExceededException("Too many financial operations. Please try again later.", retryAfter);
                }
            }
        } catch (RateLimitExceededException e) {
            if (ledgerMetrics != null) {
                ledgerMetrics.recordRateLimitRejection("FINANCIAL");
            }
            throw e;
        } catch (Exception e) {
            log.warn("Redis error during financial rate limiting: {}. Failing open.", e.getMessage());
        }
    }

    private void checkKeyLimit(String key, int maxAttempts, int windowSeconds) {
        List<?> result = redisTemplate.execute(CHECK_SCRIPT, Collections.singletonList(key));
        if (result != null && result.size() >= 2) {
            long current = ((Number) result.get(0)).longValue();
            long ttl = ((Number) result.get(1)).longValue();
            if (current >= maxAttempts) {
                long retryAfter = ttl > 0 ? ttl : windowSeconds;
                throw new RateLimitExceededException("Too many login attempts. Please try again later.", retryAfter);
            }
        }
    }

    private List<?> executeIncr(String key, int windowSeconds) {
        return redisTemplate.execute(INCR_SCRIPT, Collections.singletonList(key), String.valueOf(windowSeconds));
    }

    private void handleRedisFailure(String operation, Exception e) {
        log.warn("Redis error during rate limiting {}: {}. Fail-open: {}", operation, e.getMessage(), properties.failOpen());
        if (!properties.failOpen()) {
            throw new RateLimitExceededException("Rate limiting service temporarily unavailable. Please try again later.", 60);
        }
    }

    public String loginIpKey(String clientIp) {
        return LOGIN_IP_PREFIX + clientIp;
    }

    public String loginIdentityKey(String normalizedEmail) {
        return LOGIN_IDENTITY_PREFIX + normalizedEmail;
    }

    public String signupIpKey(String clientIp) {
        return SIGNUP_IP_PREFIX + clientIp;
    }

    public String financialKey(String keyIdentifier) {
        return FINANCIAL_PREFIX + keyIdentifier;
    }

    public RateLimitProperties.LimitConfig getEffectiveFinancialLimit() {
        return properties.effectiveFinancial();
    }

    public RateLimitProperties getProperties() {
        return properties;
    }

    public static String normalizeEmail(String email) {
        return email != null ? email.trim().toLowerCase(Locale.ROOT) : "";
    }
}
