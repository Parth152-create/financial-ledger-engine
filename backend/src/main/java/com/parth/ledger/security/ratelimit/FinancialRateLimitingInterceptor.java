package com.parth.ledger.security.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

@Component
public class FinancialRateLimitingInterceptor implements HandlerInterceptor {

    private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final RedisRateLimiterService rateLimiterService;
    private final ClientIpResolver clientIpResolver;

    public FinancialRateLimitingInterceptor(RedisRateLimiterService rateLimiterService,
                                           ClientIpResolver clientIpResolver) {
        this.rateLimiterService = rateLimiterService;
        this.clientIpResolver = clientIpResolver;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!MUTATING_METHODS.contains(request.getMethod().toUpperCase())) {
            return true;
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String identifier;
        if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
            identifier = "user:" + auth.getName();
        } else {
            identifier = "ip:" + clientIpResolver.resolveClientIp(request);
        }

        rateLimiterService.checkAndRecordFinancial(identifier);
        return true;
    }
}
