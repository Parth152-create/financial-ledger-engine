package com.parth.ledger.observability.logging;

import com.parth.ledger.common.filter.CorrelationIdFilter;
import com.parth.ledger.observability.metrics.LedgerMetrics;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Filter providing structured HTTP request logging and request-level metrics.
 * Runs immediately following CorrelationIdFilter so MDC correlationId is available.
 *
 * Normalizes URIs to prevent high-cardinality metric labels while logging exact diagnostic paths.
 * Guarantees no sensitive request payloads, authentication tokens, cookies, or headers are logged.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
    );

    private final LedgerMetrics ledgerMetrics;

    public RequestLoggingFilter(LedgerMetrics ledgerMetrics) {
        this.ledgerMetrics = ledgerMetrics;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long startTime = System.currentTimeMillis();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long durationMs = System.currentTimeMillis() - startTime;
            int status = response.getStatus();
            String method = request.getMethod();
            String path = request.getRequestURI();

            String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
            if (correlationId == null || correlationId.isBlank()) {
                correlationId = (String) request.getAttribute(CorrelationIdFilter.MDC_KEY);
            }
            if (correlationId == null || correlationId.isBlank()) {
                correlationId = "UNKNOWN";
            }

            String outcome = classifyOutcome(status);
            String normalizedEndpoint = resolveNormalizedEndpoint(request, path);
            String boundedMethod = LedgerMetrics.normalizeMethod(method);

            // Record request-level metrics with strictly bounded method and normalized endpoint
            ledgerMetrics.recordHttpRequest(boundedMethod, normalizedEndpoint, status, outcome, durationMs);

            // Structured diagnostic operational logging (SLF4J structured event)
            if (shouldLogPath(path)) {
                String logPath = "UNMATCHED".equals(normalizedEndpoint) ? sanitizePathForLogging(path) : normalizedEndpoint;
                log.info("HTTP request completed: correlationId={} method={} path={} status={} durationMs={} outcome={}",
                        correlationId, boundedMethod, logPath, status, durationMs, outcome);
            }
        }
    }

    /**
     * Determines whether an HTTP request path should be logged at INFO level.
     * Diagnostic paths like API mutations and auth are logged; high-frequency internal probes can be filtered.
     */
    private boolean shouldLogPath(String path) {
        if (path == null) {
            return false;
        }
        // Always log API, auth, and operational management paths
        return path.startsWith("/api/") || path.startsWith("/logout") || path.startsWith("/actuator");
    }

    /**
     * Classifies HTTP status code into high-level operational outcome.
     */
    public static String classifyOutcome(int status) {
        if (status >= 200 && status < 300) {
            return "SUCCESS";
        }
        if (status >= 300 && status < 400) {
            return "REDIRECTION";
        }
        if (status == 400) {
            return "BAD_REQUEST";
        }
        if (status == 401) {
            return "UNAUTHORIZED";
        }
        if (status == 403) {
            return "FORBIDDEN";
        }
        if (status == 404) {
            return "NOT_FOUND";
        }
        if (status == 409) {
            return "CONFLICT";
        }
        if (status == 422) {
            return "UNPROCESSABLE_ENTITY";
        }
        if (status == 429) {
            return "RATE_LIMITED";
        }
        if (status >= 400 && status < 500) {
            return "CLIENT_ERROR";
        }
        if (status >= 500) {
            return "SERVER_ERROR";
        }
        return "UNKNOWN";
    }

    /**
     * Resolves a strictly bounded, normalized endpoint URI template.
     * Uses Spring MVC BEST_MATCHING_PATTERN_ATTRIBUTE when matched, or returns 'UNMATCHED'
     * for unmatched/unknown routes to prevent high-cardinality metric label explosions.
     */
    public static String resolveNormalizedEndpoint(HttpServletRequest request, String path) {
        if (request != null) {
            Object bestPattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            if (bestPattern != null && !bestPattern.toString().isBlank()) {
                String patternStr = bestPattern.toString();
                if (!"/**".equals(patternStr)) {
                    return patternStr;
                }
            }
        }
        return "UNMATCHED";
    }

    public static String resolveNormalizedEndpoint(HttpServletRequest request) {
        return resolveNormalizedEndpoint(request, null);
    }

    /**
     * Sanitizes a raw request path for operational logging by replacing UUIDs with {id}.
     */
    public static String sanitizePathForLogging(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        return UUID_PATTERN.matcher(path).replaceAll("{id}");
    }
}
