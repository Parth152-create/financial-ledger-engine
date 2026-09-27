package com.parth.ledger.security.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class ClientIpResolver {

    private final boolean trustForwardedHeaders;
    private final Set<String> trustedProxies;

    public ClientIpResolver(
            @Value("${ledger.rate-limit.trust-forwarded-headers:false}") boolean trustForwardedHeaders,
            @Value("${ledger.rate-limit.trusted-proxies:127.0.0.1,::1,0:0:0:0:0:0:0:1}") String trustedProxiesConfig) {
        this.trustForwardedHeaders = trustForwardedHeaders;
        this.trustedProxies = Arrays.stream(trustedProxiesConfig.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    public String resolveClientIp(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        String fallbackIp = StringUtils.hasText(remoteAddr) ? remoteAddr.trim() : "unknown";

        if (!trustForwardedHeaders) {
            return fallbackIp;
        }

        if (remoteAddr != null && !trustedProxies.contains(remoteAddr.trim())) {
            return fallbackIp;
        }

        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwardedFor)) {
            String[] parts = forwardedFor.split(",");
            for (String part : parts) {
                String ip = part.trim();
                if (StringUtils.hasText(ip) && !trustedProxies.contains(ip)) {
                    return ip;
                }
            }
        }

        String realIp = request.getHeader("X-Real-IP");
        if (StringUtils.hasText(realIp)) {
            return realIp.trim();
        }

        return fallbackIp;
    }
}
