package com.parth.ledger.observability.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Production-grade observability metrics service for the Financial Ledger Engine.
 *
 * Enforces strict failure isolation: metrics failures are caught and logged at WARN level,
 * guaranteeing that no financial transaction, mutation, or ledger invariant is ever
 * affected by metrics collection.
 *
 * All metric dimensions and labels are strictly bounded constants to prevent high-cardinality leaks.
 */
@Component
public class LedgerMetrics {

    private static final Logger log = LoggerFactory.getLogger(LedgerMetrics.class);

    private final MeterRegistry registry;

    public LedgerMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public MeterRegistry getRegistry() {
        return registry;
    }

    // =========================================================================
    // 1. FINANCIAL OPERATION METRICS (TRANSFER, DEPOSIT, WITHDRAWAL, REVERSAL)
    // =========================================================================

    /**
     * Records a financial operation lifecycle event.
     *
     * @param operation One of "TRANSFER", "DEPOSIT", "WITHDRAWAL", "REVERSAL".
     * @param status    One of "ATTEMPTED", "COMPLETED", "REJECTED", "FAILED".
     */
    public void recordOperation(String operation, String status) {
        try {
            Counter.builder("ledger.operation.total")
                    .description("Financial operations partitioned by operation type and status")
                    .tag("operation", operation != null ? operation : "UNKNOWN")
                    .tag("status", status != null ? status : "UNKNOWN")
                    .register(registry)
                    .increment();
        } catch (Throwable t) {
            log.warn("Failed to record operation metric: {}", t.getMessage());
        }
    }

    /**
     * Records the execution latency of a financial operation.
     *
     * @param operation      One of "TRANSFER", "DEPOSIT", "WITHDRAWAL", "REVERSAL".
     * @param status         One of "COMPLETED", "REJECTED", "FAILED".
     * @param durationMillis Latency in milliseconds.
     */
    public void recordOperationDuration(String operation, String status, long durationMillis) {
        try {
            Timer.builder("ledger.operation.duration")
                    .description("Duration of financial operations in milliseconds")
                    .tag("operation", operation != null ? operation : "UNKNOWN")
                    .tag("status", status != null ? status : "UNKNOWN")
                    .register(registry)
                    .record(durationMillis, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            log.warn("Failed to record operation duration metric: {}", t.getMessage());
        }
    }

    // =========================================================================
    // 2. POLICY EVALUATION & REJECTION METRICS
    // =========================================================================

    /**
     * Records an evaluation of a financial policy rule.
     *
     * @param policyType Policy type (e.g., MAX_TRANSACTION_AMOUNT, DAILY_TRANSACTION_AMOUNT, etc.).
     * @param txType     Transaction type (e.g., TRANSFER, DEPOSIT, WITHDRAWAL).
     */
    public void recordPolicyEvaluation(String policyType, String txType) {
        try {
            Counter.builder("ledger.policy.evaluations.total")
                    .description("Total financial policy evaluations")
                    .tag("policy_type", policyType != null ? policyType : "UNKNOWN")
                    .tag("tx_type", txType != null ? txType : "UNKNOWN")
                    .register(registry)
                    .increment();
        } catch (Throwable t) {
            log.warn("Failed to record policy evaluation metric: {}", t.getMessage());
        }
    }

    /**
     * Records a financial policy rejection/violation.
     *
     * @param policyType Policy type violated.
     * @param txType     Transaction type being evaluated.
     */
    public void recordPolicyRejection(String policyType, String txType) {
        try {
            Counter.builder("ledger.policy.rejections.total")
                    .description("Total financial policy rejections")
                    .tag("policy_type", policyType != null ? policyType : "UNKNOWN")
                    .tag("tx_type", txType != null ? txType : "UNKNOWN")
                    .register(registry)
                    .increment();
        } catch (Throwable t) {
            log.warn("Failed to record policy rejection metric: {}", t.getMessage());
        }
    }

    // =========================================================================
    // 3. IDEMPOTENCY OBSERVABILITY METRICS
    // =========================================================================

    /**
     * Records an idempotency resolution outcome.
     *
     * @param operation One of "TRANSFER", "DEPOSIT", "WITHDRAWAL", "REVERSAL".
     * @param outcome   One of "FIRST_EXECUTION", "REPLAY", "CONFLICT".
     */
    public void recordIdempotencyOutcome(String operation, String outcome) {
        try {
            Counter.builder("ledger.idempotency.total")
                    .description("Idempotency outcomes partitioned by operation and outcome")
                    .tag("operation", operation != null ? operation : "UNKNOWN")
                    .tag("outcome", outcome != null ? outcome : "UNKNOWN")
                    .register(registry)
                    .increment();
        } catch (Throwable t) {
            log.warn("Failed to record idempotency outcome metric: {}", t.getMessage());
        }
    }

    /**
     * Records the duration of an idempotency resolution (e.g. REPLAY or CONFLICT).
     * Keeps replay latency separate from first-execution transaction duration.
     *
     * @param operation      One of "TRANSFER", "DEPOSIT", "WITHDRAWAL", "REVERSAL".
     * @param outcome        One of "REPLAY", "CONFLICT".
     * @param durationMillis Latency in milliseconds.
     */
    public void recordIdempotencyDuration(String operation, String outcome, long durationMillis) {
        try {
            Timer.builder("ledger.idempotency.duration")
                    .description("Idempotency resolution duration in milliseconds")
                    .tag("operation", operation != null ? operation : "UNKNOWN")
                    .tag("outcome", outcome != null ? outcome : "UNKNOWN")
                    .register(registry)
                    .record(durationMillis, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            log.warn("Failed to record idempotency duration metric: {}", t.getMessage());
        }
    }

    // =========================================================================
    // 4. RATE LIMIT OBSERVABILITY METRICS
    // =========================================================================

    /**
     * Records a rate-limit rejection.
     *
     * @param category One of "LOGIN", "SIGNUP", "FINANCIAL".
     */
    public void recordRateLimitRejection(String category) {
        try {
            Counter.builder("ledger.ratelimit.rejections.total")
                    .description("Rate limit rejections partitioned by category")
                    .tag("category", category != null ? category : "UNKNOWN")
                    .register(registry)
                    .increment();
        } catch (Throwable t) {
            log.warn("Failed to record rate limit rejection metric: {}", t.getMessage());
        }
    }

    // =========================================================================
    // 5. RECONCILIATION OBSERVABILITY METRICS
    // =========================================================================

    /**
     * Records a reconciliation run.
     *
     * @param scope One of "SINGLE_ACCOUNT", "USER_ACCOUNTS", "SYSTEM".
     */
    public void recordReconciliationRun(String scope) {
        try {
            Counter.builder("ledger.reconciliation.runs.total")
                    .description("Total reconciliation runs executed")
                    .tag("scope", scope != null ? scope : "UNKNOWN")
                    .register(registry)
                    .increment();
        } catch (Throwable t) {
            log.warn("Failed to record reconciliation run metric: {}", t.getMessage());
        }
    }

    /**
     * Records the number of accounts checked during a reconciliation run.
     *
     * @param scope One of "SINGLE_ACCOUNT", "USER_ACCOUNTS", "SYSTEM".
     * @param count Number of accounts checked.
     */
    public void recordReconciliationAccountsChecked(String scope, long count) {
        try {
            Counter.builder("ledger.reconciliation.accounts.checked.total")
                    .description("Total accounts checked during reconciliation")
                    .tag("scope", scope != null ? scope : "UNKNOWN")
                    .register(registry)
                    .increment(count);
        } catch (Throwable t) {
            log.warn("Failed to record reconciliation accounts checked metric: {}", t.getMessage());
        }
    }

    /**
     * Records consistent accounts confirmed during a reconciliation run.
     *
     * @param scope One of "SINGLE_ACCOUNT", "USER_ACCOUNTS", "SYSTEM".
     * @param count Number of consistent accounts.
     */
    public void recordReconciliationConsistent(String scope, long count) {
        try {
            Counter.builder("ledger.reconciliation.accounts.consistent.total")
                    .description("Total consistent accounts confirmed during reconciliation")
                    .tag("scope", scope != null ? scope : "UNKNOWN")
                    .register(registry)
                    .increment(count);
        } catch (Throwable t) {
            log.warn("Failed to record reconciliation consistent accounts metric: {}", t.getMessage());
        }
    }

    /**
     * Records discrepancies detected during a reconciliation run.
     *
     * @param scope One of "SINGLE_ACCOUNT", "USER_ACCOUNTS", "SYSTEM".
     * @param count Number of discrepancies detected.
     */
    public void recordReconciliationDiscrepancy(String scope, long count) {
        try {
            Counter.builder("ledger.reconciliation.discrepancies.total")
                    .description("Total reconciliation discrepancies identified")
                    .tag("scope", scope != null ? scope : "UNKNOWN")
                    .register(registry)
                    .increment(count);
        } catch (Throwable t) {
            log.warn("Failed to record reconciliation discrepancy metric: {}", t.getMessage());
        }
    }

    /**
     * Records an unexpected failure or exception during reconciliation.
     *
     * @param scope One of "SINGLE_ACCOUNT", "USER_ACCOUNTS", "SYSTEM".
     */
    public void recordReconciliationError(String scope) {
        try {
            Counter.builder("ledger.reconciliation.errors.total")
                    .description("Total reconciliation execution errors")
                    .tag("scope", scope != null ? scope : "UNKNOWN")
                    .register(registry)
                    .increment();
        } catch (Throwable t) {
            log.warn("Failed to record reconciliation error metric: {}", t.getMessage());
        }
    }

    // =========================================================================
    // 6. HTTP REQUEST OBSERVABILITY METRICS
    // =========================================================================

    private static final Set<String> ALLOWED_METHODS = Set.of(
            "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"
    );

    /**
     * Normalizes an HTTP method against the allowed whitelist.
     * Non-whitelisted or unexpected methods map to 'OTHER' to preserve bounded cardinality.
     */
    public static String normalizeMethod(String method) {
        if (method == null) {
            return "OTHER";
        }
        String upper = method.trim().toUpperCase(Locale.ROOT);
        return ALLOWED_METHODS.contains(upper) ? upper : "OTHER";
    }

    /**
     * Records an HTTP request execution with normalized path template.
     *
     * @param method         HTTP method (GET, POST, etc.).
     * @param endpoint       Normalized endpoint URI template (e.g. /api/v1/accounts/{accountId}, or UNMATCHED).
     * @param status         HTTP response status code.
     * @param outcome        Outcome category (SUCCESS, CLIENT_ERROR, SERVER_ERROR, etc.).
     * @param durationMillis Request duration in milliseconds.
     */
    public void recordHttpRequest(String method, String endpoint, int status, String outcome, long durationMillis) {
        try {
            String safeMethod = normalizeMethod(method);
            String safeEndpoint = (endpoint != null && !endpoint.isBlank()) ? endpoint : "UNMATCHED";
            String statusGroup = (status / 100) + "xx";

            Counter.builder("ledger.http.requests.total")
                    .description("HTTP requests partitioned by method, normalized endpoint, status group, and outcome")
                    .tag("method", safeMethod)
                    .tag("endpoint", safeEndpoint)
                    .tag("status_group", statusGroup)
                    .tag("outcome", outcome != null ? outcome : "UNKNOWN")
                    .register(registry)
                    .increment();

            Timer.builder("ledger.http.request.duration")
                    .description("HTTP request duration in milliseconds")
                    .tag("method", safeMethod)
                    .tag("endpoint", safeEndpoint)
                    .tag("status", String.valueOf(status))
                    .register(registry)
                    .record(durationMillis, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            log.warn("Failed to record HTTP request metric: {}", t.getMessage());
        }
    }

    // =========================================================================
    // 7. OUTBOX OBSERVABILITY METRICS
    // =========================================================================

    private static final Set<String> ALLOWED_OUTBOX_EVENT_TYPES = Set.of(
            "TRANSFER_COMPLETED",
            "DEPOSIT_COMPLETED",
            "WITHDRAWAL_COMPLETED",
            "TRANSACTION_REVERSED",
            "ACCOUNT_CREATED",
            "ACCOUNT_FROZEN",
            "ACCOUNT_UNFROZEN",
            "ACCOUNT_CLOSED"
    );

    public static String normalizeOutboxEventType(String eventType) {
        if (eventType == null) {
            return "UNKNOWN";
        }
        String upper = eventType.trim().toUpperCase(Locale.ROOT);
        return ALLOWED_OUTBOX_EVENT_TYPES.contains(upper) ? upper : "UNKNOWN";
    }

    /**
     * Records the creation of an outbox event.
     * Guaranteed to only be called upon transaction commit.
     *
     * @param eventType Outbox event type.
     */
    public void recordOutboxEventCreated(String eventType) {
        try {
            Counter.builder("ledger.outbox.events.created.total")
                    .description("Total outbox events created")
                    .tag("event_type", normalizeOutboxEventType(eventType))
                    .register(registry)
                    .increment();
        } catch (Throwable t) {
            log.warn("Failed to record outbox event created metric: {}", t.getMessage());
        }
    }

    /**
     * Records the processing outcome of an outbox event.
     *
     * @param eventType Outbox event type.
     * @param outcome   One of "SUCCESS", "RETRY", "FAILED".
     */
    public void recordOutboxEventProcessed(String eventType, String outcome) {
        try {
            String safeOutcome = outcome != null ? outcome.trim().toUpperCase(Locale.ROOT) : "UNKNOWN";
            Counter.builder("ledger.outbox.events.processed.total")
                    .description("Total outbox events processed partitioned by outcome")
                    .tag("event_type", normalizeOutboxEventType(eventType))
                    .tag("outcome", safeOutcome)
                    .register(registry)
                    .increment();
        } catch (Throwable t) {
            log.warn("Failed to record outbox event processed metric: {}", t.getMessage());
        }
    }

    /**
     * Records an outbox event retry attempt.
     *
     * @param eventType Outbox event type.
     */
    public void recordOutboxEventRetried(String eventType) {
        try {
            Counter.builder("ledger.outbox.events.retried.total")
                    .description("Total outbox events retried")
                    .tag("event_type", normalizeOutboxEventType(eventType))
                    .register(registry)
                    .increment();
        } catch (Throwable t) {
            log.warn("Failed to record outbox event retried metric: {}", t.getMessage());
        }
    }

    /**
     * Records an outbox event terminal failure.
     *
     * @param eventType Outbox event type.
     */
    public void recordOutboxEventFailed(String eventType) {
        try {
            Counter.builder("ledger.outbox.events.failed.total")
                    .description("Total outbox events permanently failed")
                    .tag("event_type", normalizeOutboxEventType(eventType))
                    .register(registry)
                    .increment();
        } catch (Throwable t) {
            log.warn("Failed to record outbox event failed metric: {}", t.getMessage());
        }
    }

    /**
     * Records the execution latency of outbox event processing.
     *
     * @param eventType      Outbox event type.
     * @param status         One of "SUCCESS", "FAILED".
     * @param durationMillis Latency in milliseconds.
     */
    public void recordOutboxProcessingDuration(String eventType, String status, long durationMillis) {
        try {
            String safeStatus = status != null ? status.trim().toUpperCase(Locale.ROOT) : "UNKNOWN";
            Timer.builder("ledger.outbox.processing.duration")
                    .description("Duration of outbox event processing in milliseconds")
                    .tag("event_type", normalizeOutboxEventType(eventType))
                    .tag("status", safeStatus)
                    .register(registry)
                    .record(durationMillis, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            log.warn("Failed to record outbox processing duration metric: {}", t.getMessage());
        }
    }
}
