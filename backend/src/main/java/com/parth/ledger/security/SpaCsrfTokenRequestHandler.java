package com.parth.ledger.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;

import java.util.function.Supplier;

/**
 * Custom CSRF token request handler designed for Single Page Applications (SPAs).
 * Resolves CSRF token values from HTTP headers (e.g. X-XSRF-TOKEN or X-CSRF-TOKEN)
 * without requiring XOR decryption (since SPAs read the raw token from the cookie).
 * Also supports XOR-encoded parameters for HTML form submissions and raw parameters for testing.
 */
public final class SpaCsrfTokenRequestHandler extends CsrfTokenRequestAttributeHandler {

    private final CsrfTokenRequestHandler delegate = new XorCsrfTokenRequestAttributeHandler();

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> csrfToken) {
        this.delegate.handle(request, response, csrfToken);
    }

    @Override
    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
        String headerValue = request.getHeader(csrfToken.getHeaderName());
        if (StringUtils.hasText(headerValue)) {
            return super.resolveCsrfTokenValue(request, csrfToken);
        }

        String xsrfHeader = request.getHeader("X-XSRF-TOKEN");
        if (StringUtils.hasText(xsrfHeader)) {
            return xsrfHeader;
        }

        String csrfHeader = request.getHeader("X-CSRF-TOKEN");
        if (StringUtils.hasText(csrfHeader)) {
            return csrfHeader;
        }

        String paramValue = request.getParameter(csrfToken.getParameterName());
        if (StringUtils.hasText(paramValue)) {
            String resolved = this.delegate.resolveCsrfTokenValue(request, csrfToken);
            if (resolved != null) {
                return resolved;
            }
            return paramValue;
        }

        return null;
    }
}
