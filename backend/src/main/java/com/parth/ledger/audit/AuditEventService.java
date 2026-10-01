package com.parth.ledger.audit;

import com.parth.ledger.audit.dto.AuditEventPageResponseDto;
import com.parth.ledger.audit.dto.AuditEventResponseDto;
import com.parth.ledger.audit.specification.AuditEventSpecifications;
import com.parth.ledger.security.AuthenticatedUserService;
import com.parth.ledger.security.ratelimit.ClientIpResolver;
import com.parth.ledger.user.User;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Service orchestrating the creation and authorized retrieval of operational audit events.
 * Enforces metadata sanitization, request context resolution, and domain-level authorization boundaries.
 */
@Service
public class AuditEventService {

    private static final Logger log = LoggerFactory.getLogger(AuditEventService.class);

    private static final int MAX_METADATA_ENTRIES = 20;
    private static final int MAX_METADATA_STRING_LENGTH = 255;
    private static final int MAX_IP_LENGTH = 45;
    private static final int MAX_USER_AGENT_LENGTH = 255;

    private static final Set<String> SENSITIVE_KEY_SUBSTRINGS = Set.of(
            "password",
            "secret",
            "token",
            "authorization",
            "cookie",
            "session",
            "csrf",
            "xsrf",
            "credential",
            "privatekey"
    );

    private final AuditEventRepository auditEventRepository;
    private final AuthenticatedUserService authenticatedUserService;
    private final ClientIpResolver clientIpResolver;
    private final org.springframework.transaction.PlatformTransactionManager transactionManager;

    @org.springframework.beans.factory.annotation.Autowired
    public AuditEventService(AuditEventRepository auditEventRepository,
                             AuthenticatedUserService authenticatedUserService,
                             ClientIpResolver clientIpResolver,
                             org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.auditEventRepository = auditEventRepository;
        this.authenticatedUserService = authenticatedUserService;
        this.clientIpResolver = clientIpResolver;
        this.transactionManager = transactionManager;
    }

    public AuditEventService(AuditEventRepository auditEventRepository,
                             AuthenticatedUserService authenticatedUserService,
                             ClientIpResolver clientIpResolver) {
        this(auditEventRepository, authenticatedUserService, clientIpResolver, null);
    }

    /**
     * Records an operational audit event within the caller's active database transaction.
     * Automatically extracts client IP address and User-Agent if executed within an HTTP request context.
     *
     * @param actorUserId Optional UUID of the user who initiated the action (null for system actions).
     * @param eventType   Controlled audit event type.
     * @param entityType  Controlled entity classification.
     * @param entityId    Optional UUID of the target entity.
     * @param metadata    Contextual safe metadata map.
     * @return The persisted AuditEvent entity.
     */
    @Transactional
    public AuditEvent recordEvent(UUID actorUserId,
                                  AuditEventType eventType,
                                  AuditEntityType entityType,
                                  UUID entityId,
                                  Map<String, Object> metadata) {
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(entityType, "entityType must not be null");

        Map<String, Object> sanitizedMetadata = sanitizeMetadata(metadata);
        String ipAddress = null;
        String userAgent = null;

        // Resolve request context if present
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            HttpServletRequest request = attributes.getRequest();
            String rawIp = clientIpResolver.resolveClientIp(request);
            if (rawIp != null) {
                ipAddress = rawIp.length() > MAX_IP_LENGTH ? rawIp.substring(0, MAX_IP_LENGTH) : rawIp;
            }
            String rawUserAgent = request.getHeader("User-Agent");
            if (rawUserAgent != null) {
                userAgent = rawUserAgent.length() > MAX_USER_AGENT_LENGTH
                        ? rawUserAgent.substring(0, MAX_USER_AGENT_LENGTH)
                        : rawUserAgent;
            }
        }

        AuditEvent event = new AuditEvent(
                actorUserId,
                eventType,
                entityType,
                entityId,
                sanitizedMetadata,
                ipAddress,
                userAgent
        );

        AuditEvent saved = auditEventRepository.save(event);
        log.debug("Recorded audit event: id={}, eventType={}, entityType={}, entityId={}, actorUserId={}",
                saved.getId(), eventType, entityType, entityId, actorUserId);
        return saved;
    }

    /**
     * Records an operational audit event in a guaranteed independent, new database transaction (REQUIRES_NEW).
     * Used for recording failure/rejection events that must persist even if the outer financial transaction rolls back.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public AuditEvent recordEventInNewTransaction(UUID actorUserId,
                                                  AuditEventType eventType,
                                                  AuditEntityType entityType,
                                                  UUID entityId,
                                                  Map<String, Object> metadata) {
        return recordEvent(actorUserId, eventType, entityType, entityId, metadata);
    }

    /**
     * Records an operational policy rejection audit event at most once per logical request fingerprint.
     * Subsequent idempotent retries with the same logical payload are deduplicated, while materially different
     * requests reusing the same idempotency key produce distinct audit events.
     * Derives an internal correlation ID deterministically without exposing raw secrets or idempotency keys.
     * Database-enforced deduplication backed by partial unique index uk_audit_events_policy_rejection_correlation.
     */
    public void recordPolicyRejectionEventOnce(UUID actorUserId,
                                              AuditEventType eventType,
                                              AuditEntityType entityType,
                                              UUID entityId,
                                              String idempotencyKey,
                                              String transactionType,
                                              Map<String, Object> metadata) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            recordEventInNewTransaction(actorUserId, eventType, entityType, entityId, metadata);
            return;
        }

        // Deterministic request fingerprint derived from normalized request payload
        String correlationId = computeRejectionFingerprint(transactionType, idempotencyKey, metadata);

        Map<String, Object> enrichedMetadata = new HashMap<>(metadata != null ? metadata : Collections.emptyMap());
        enrichedMetadata.put("correlationId", correlationId);
        // Explicitly guarantee raw idempotency key or secrets are NEVER leaked into audit metadata
        enrichedMetadata.remove("idempotencyKey");
        enrichedMetadata.remove("cleanIdempotencyKey");
        enrichedMetadata.remove("key");

        if (transactionManager != null) {
            org.springframework.transaction.support.TransactionTemplate txTemplate =
                    new org.springframework.transaction.support.TransactionTemplate(transactionManager);
            txTemplate.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            try {
                txTemplate.execute(status -> {
                    if (auditEventRepository.existsByEventTypeAndCorrelationId(eventType.name(), correlationId)) {
                        log.debug("Skipping duplicate policy rejection audit event for correlationId={}, eventType={}", correlationId, eventType);
                        return null;
                    }
                    try {
                        recordEvent(actorUserId, eventType, entityType, entityId, enrichedMetadata);
                        auditEventRepository.flush();
                    } catch (org.springframework.dao.DataIntegrityViolationException dive) {
                        log.debug("Concurrent duplicate policy rejection audit event suppressed by DB unique index: correlationId={}", correlationId);
                        status.setRollbackOnly();
                    }
                    return null;
                });
            } catch (Exception e) {
                log.debug("Policy rejection audit event transaction completed with rollback or suppression: {}", e.getMessage());
            }
        } else {
            // Fallback for standalone/mock unit test contexts
            if (auditEventRepository.existsByEventTypeAndCorrelationId(eventType.name(), correlationId)) {
                log.debug("Skipping duplicate policy rejection audit event for correlationId={}, eventType={}", correlationId, eventType);
                return;
            }
            recordEvent(actorUserId, eventType, entityType, entityId, enrichedMetadata);
        }
    }

    /**
     * Computes a deterministic request fingerprint from normalized request fields.
     * Ensures identical retries map to the same correlationId, while materially different payloads
     * reusing the same idempotency key map to distinct correlation IDs.
     */
    public static String computeRejectionFingerprint(String transactionType,
                                                    String idempotencyKey,
                                                    Map<String, Object> metadata) {
        String cleanType = transactionType != null ? transactionType.trim().toUpperCase() : "UNKNOWN";
        String cleanKey = idempotencyKey != null ? idempotencyKey.trim() : "";
        StringBuilder sb = new StringBuilder();
        sb.append("policy-rejection:").append(cleanType).append(':').append(cleanKey);

        if (metadata != null) {
            if ("TRANSFER".equals(cleanType)) {
                Object src = metadata.get("sourceAccountId");
                Object dst = metadata.get("destinationAccountId");
                Object amt = metadata.get("amount");
                Object cur = metadata.get("currency");
                sb.append(":src=").append(src != null ? src.toString().trim().toLowerCase() : "");
                sb.append(":dst=").append(dst != null ? dst.toString().trim().toLowerCase() : "");
                sb.append(":amt=").append(normalizeAmount(amt));
                sb.append(":cur=").append(cur != null ? cur.toString().trim().toUpperCase() : "");
            } else if ("DEPOSIT".equals(cleanType)) {
                Object dst = metadata.get("destinationAccountId");
                Object amt = metadata.get("amount");
                Object cur = metadata.get("currency");
                sb.append(":acct=").append(dst != null ? dst.toString().trim().toLowerCase() : "");
                sb.append(":amt=").append(normalizeAmount(amt));
                sb.append(":cur=").append(cur != null ? cur.toString().trim().toUpperCase() : "");
            } else if ("WITHDRAWAL".equals(cleanType)) {
                Object src = metadata.get("sourceAccountId");
                Object amt = metadata.get("amount");
                Object cur = metadata.get("currency");
                sb.append(":acct=").append(src != null ? src.toString().trim().toLowerCase() : "");
                sb.append(":amt=").append(normalizeAmount(amt));
                sb.append(":cur=").append(cur != null ? cur.toString().trim().toUpperCase() : "");
            }
        }

        return UUID.nameUUIDFromBytes(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    private static String normalizeAmount(Object amt) {
        if (amt == null) return "0.0000";
        if (amt instanceof BigDecimal bd) {
            return bd.setScale(4, java.math.RoundingMode.HALF_UP).toPlainString();
        }
        try {
            return new BigDecimal(amt.toString().trim()).setScale(4, java.math.RoundingMode.HALF_UP).toPlainString();
        } catch (Exception e) {
            return amt.toString().trim();
        }
    }

    /**
     * Retrieves a paginated and filtered list of audit events.
     * Enforces strict authorization: regular users can only view events belonging to their domain boundary;
     * administrators can query events across the platform.
     *
     * @param eventTypeStr Optional filter for event type (e.g. TRANSFER_COMPLETED).
     * @param entityTypeStr Optional filter for entity type (e.g. TRANSACTION).
     * @param fromStr      Optional ISO-8601 start timestamp (inclusive).
     * @param toStr        Optional ISO-8601 end timestamp (exclusive).
     * @param page         Zero-based page index.
     * @param size         Page size (1 to 100).
     * @return Paginated response containing safe AuditEventResponseDto items.
     */
    @Transactional(readOnly = true)
    public AuditEventPageResponseDto getAuditEvents(String eventTypeStr,
                                                   String entityTypeStr,
                                                   String fromStr,
                                                   String toStr,
                                                   int page,
                                                   int size) {
        if (page < 0) {
            throw new IllegalArgumentException("Page index must not be negative: " + page);
        }
        if (size < 1 || size > 100) {
            throw new IllegalArgumentException("Page size must be between 1 and 100: " + size);
        }

        AuditEventType eventType = null;
        if (eventTypeStr != null && !eventTypeStr.isBlank()) {
            try {
                eventType = AuditEventType.valueOf(eventTypeStr.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid event type: " + eventTypeStr);
            }
        }

        AuditEntityType entityType = null;
        if (entityTypeStr != null && !entityTypeStr.isBlank()) {
            try {
                entityType = AuditEntityType.valueOf(entityTypeStr.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid entity type: " + entityTypeStr);
            }
        }

        Instant from = parseInstant(fromStr, "from");
        Instant to = parseInstant(toStr, "to");

        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException(
                    "'from' timestamp (" + from + ") must be before or equal to 'to' timestamp (" + to + ")"
            );
        }

        User currentUser = authenticatedUserService.getCurrentUser();
        boolean isAdmin = authenticatedUserService.isAdmin();

        Specification<AuditEvent> spec = AuditEventSpecifications.forUserWithFilters(
                currentUser.getId(),
                isAdmin,
                eventType,
                entityType,
                from,
                to
        );

        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        Page<AuditEvent> eventPage = auditEventRepository.findAll(spec, pageRequest);

        return AuditEventPageResponseDto.from(eventPage.map(AuditEventResponseDto::from));
    }

    /**
     * Sanitizes contextual metadata map, discarding prohibited sensitive keys,
     * truncating oversized strings, and enforcing cardinality bounds.
     */
    private Map<String, Object> sanitizeMetadata(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, Object> sanitized = new HashMap<>();
        int count = 0;

        for (Map.Entry<String, Object> entry : input.entrySet()) {
            if (count >= MAX_METADATA_ENTRIES) {
                break;
            }
            String key = entry.getKey();
            if (key == null || key.isBlank()) {
                continue;
            }
            String lowerKey = key.toLowerCase(Locale.ROOT);
            if (isSensitiveKey(lowerKey)) {
                log.warn("Blocked sensitive key in audit metadata: {}", key);
                continue;
            }

            Object value = entry.getValue();
            if (value == null) {
                sanitized.put(key, null);
                count++;
            } else if (value instanceof String strVal) {
                sanitized.put(key, strVal.length() > MAX_METADATA_STRING_LENGTH
                        ? strVal.substring(0, MAX_METADATA_STRING_LENGTH)
                        : strVal);
                count++;
            } else if (value instanceof Number || value instanceof Boolean || value instanceof UUID) {
                sanitized.put(key, value);
                count++;
            } else {
                // For other safe objects, convert toString bounded
                String str = value.toString();
                sanitized.put(key, str.length() > MAX_METADATA_STRING_LENGTH
                        ? str.substring(0, MAX_METADATA_STRING_LENGTH)
                        : str);
                count++;
            }
        }

        return Collections.unmodifiableMap(sanitized);
    }

    private boolean isSensitiveKey(String lowerKey) {
        for (String forbidden : SENSITIVE_KEY_SUBSTRINGS) {
            if (lowerKey.contains(forbidden)) {
                return true;
            }
        }
        return false;
    }

    private Instant parseInstant(String instantStr, String paramName) {
        if (instantStr == null || instantStr.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(instantStr.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid ISO-8601 '" + paramName + "' timestamp: " + instantStr);
        }
    }
}
