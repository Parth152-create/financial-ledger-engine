package com.parth.ledger.idempotency;

import com.parth.ledger.transaction.dto.TransferResponseDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Optional;

/**
 * Auxiliary fast-path idempotency cache backed by Redis.
 *
 * Stores serialized TransferResponseDto objects with a configurable TTL.
 * All operations fail open: if Redis is unavailable or errors occur during read/write,
 * a warning is logged and the operation falls back gracefully to authoritative PostgreSQL.
 */
@Service
public class IdempotencyCacheService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCacheService.class);
    private static final String KEY_PREFIX = "ledger:idempotency:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration defaultTtl;

    public IdempotencyCacheService(StringRedisTemplate redisTemplate,
                                   ObjectMapper objectMapper,
                                   @Value("${ledger.idempotency.ttl-seconds:86400}") long ttlSeconds) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.defaultTtl = Duration.ofSeconds(ttlSeconds);
    }

    /**
     * Retrieves a cached transfer response from Redis fast-path.
     * Fails open on any Redis or deserialization exception, returning Optional.empty().
     *
     * @param idempotencyKey Client idempotency key.
     * @return Optional containing the cached TransferResponseDto, or empty if missed or Redis failed.
     */
    public Optional<TransferResponseDto> get(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        String redisKey = buildKey(idempotencyKey);
        try {
            String json = redisTemplate.opsForValue().get(redisKey);
            if (json == null || json.isBlank()) {
                return Optional.empty();
            }
            TransferResponseDto dto = objectMapper.readValue(json, TransferResponseDto.class);
            return Optional.ofNullable(dto);
        } catch (Exception e) {
            log.warn("Redis error while retrieving idempotency key '{}': {}. Falling back to PostgreSQL.",
                    redisKey, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Caches a successfully committed transfer response with the default configured TTL.
     * Fails open so Redis errors never rollback or fail a committed transaction.
     *
     * @param idempotencyKey Client idempotency key.
     * @param responseDto Transfer response to cache.
     */
    public void set(String idempotencyKey, TransferResponseDto responseDto) {
        set(idempotencyKey, responseDto, this.defaultTtl);
    }

    /**
     * Caches a successfully committed transfer response with a specific custom TTL.
     *
     * @param idempotencyKey Client idempotency key.
     * @param responseDto Transfer response to cache.
     * @param ttl Custom time-to-live duration.
     */
    public void set(String idempotencyKey, TransferResponseDto responseDto, Duration ttl) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || responseDto == null) {
            return;
        }
        String redisKey = buildKey(idempotencyKey);
        try {
            String json = objectMapper.writeValueAsString(responseDto);
            redisTemplate.opsForValue().set(redisKey, json, ttl);
            log.debug("Cached transfer response in Redis for key '{}' with TTL {}", redisKey, ttl);
        } catch (Exception e) {
            log.warn("Redis error while caching idempotency key '{}': {}. Transfer remains committed in PostgreSQL.",
                    redisKey, e.getMessage());
        }
    }

    /**
     * Evicts a cached transfer response from Redis.
     *
     * @param idempotencyKey Client idempotency key.
     */
    public void delete(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return;
        }
        String redisKey = buildKey(idempotencyKey);
        try {
            redisTemplate.delete(redisKey);
        } catch (Exception e) {
            log.warn("Redis error while deleting idempotency key '{}': {}", redisKey, e.getMessage());
        }
    }

    private String buildKey(String idempotencyKey) {
        return KEY_PREFIX + idempotencyKey.trim();
    }
}
