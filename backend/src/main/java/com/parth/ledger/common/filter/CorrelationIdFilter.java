package com.parth.ledger.common.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Servlet filter establishing a deterministic correlation/request ID for every HTTP request.
 *
 * Checks inbound request headers for X-Request-Id, then X-Correlation-Id, validates the format
 * to prevent header or log injection, and generates a random UUID if neither is valid or present.
 * Stores the value in SLF4J MDC under 'correlationId' for the duration of the request,
 * stores it as a request attribute, emits X-Request-Id and X-Correlation-Id on the response,
 * and guarantees MDC cleanup in a finally block to prevent thread-pool context leakage.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String MDC_KEY = "correlationId";
    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    private static final Pattern SAFE_CORRELATION_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String correlationId = resolveCorrelationId(request);
        MDC.put(MDC_KEY, correlationId);
        request.setAttribute(MDC_KEY, correlationId);
        response.setHeader(REQUEST_ID_HEADER, correlationId);
        response.setHeader(CORRELATION_ID_HEADER, correlationId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private String resolveCorrelationId(HttpServletRequest request) {
        String requestId = request.getHeader(REQUEST_ID_HEADER);
        if (isValidCorrelationId(requestId)) {
            return requestId.trim();
        }
        String correlationId = request.getHeader(CORRELATION_ID_HEADER);
        if (isValidCorrelationId(correlationId)) {
            return correlationId.trim();
        }
        return UUID.randomUUID().toString();
    }

    public static boolean isValidCorrelationId(String id) {
        return id != null && SAFE_CORRELATION_ID_PATTERN.matcher(id.trim()).matches();
    }

    public static String getCurrentCorrelationId() {
        String id = MDC.get(MDC_KEY);
        return (id != null && !id.isBlank()) ? id : "UNKNOWN";
    }
}
