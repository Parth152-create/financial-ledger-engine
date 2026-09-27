package com.parth.ledger.config;

import com.parth.ledger.security.ratelimit.FinancialRateLimitingInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final FinancialRateLimitingInterceptor financialRateLimitingInterceptor;

    public WebMvcConfig(FinancialRateLimitingInterceptor financialRateLimitingInterceptor) {
        this.financialRateLimitingInterceptor = financialRateLimitingInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(financialRateLimitingInterceptor)
                .addPathPatterns(
                        "/api/v1/transfers/**",
                        "/api/v1/deposits/**",
                        "/api/v1/withdrawals/**",
                        "/api/v1/accounts/**"
                );
    }
}
