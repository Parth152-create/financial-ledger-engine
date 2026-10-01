package com.parth.ledger.config;

import com.parth.ledger.common.exception.ErrorResponse;
import com.parth.ledger.security.CsrfCookieFilter;
import com.parth.ledger.security.CustomOAuth2UserService;
import com.parth.ledger.security.CustomOidcUserService;
import com.parth.ledger.security.SpaCsrfTokenRequestHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.SessionManagementConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.List;

/**
 * Security configuration for the Financial Ledger Engine.
 *
 * Configures Google OAuth2 and email/password authentication, session-based identity management,
 * CSRF protection for browser-based session security, CORS for frontend SPA integration,
 * and endpoint authorization.
 * Unauthenticated API requests receive HTTP 401 Unauthorized.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SecurityConfig.class);

    private final CustomOAuth2UserService customOAuth2UserService;
    private final CustomOidcUserService customOidcUserService;
    private final ObjectMapper objectMapper;
    private final String allowedOriginsConfig;
    private final String frontendUrl;
    private final org.springframework.beans.factory.ObjectProvider<com.parth.ledger.audit.AuditEventService> auditEventServiceProvider;
    private final org.springframework.beans.factory.ObjectProvider<com.parth.ledger.security.AuthenticatedUserService> authenticatedUserServiceProvider;

    public SecurityConfig(CustomOAuth2UserService customOAuth2UserService,
                          CustomOidcUserService customOidcUserService,
                          ObjectMapper objectMapper,
                          @Value("${ledger.security.cors.allowed-origins:http://localhost:3000,http://localhost:3001}") String allowedOriginsConfig,
                          @Value("${ledger.frontend-url:http://localhost:3001}") String frontendUrl,
                          org.springframework.beans.factory.ObjectProvider<com.parth.ledger.audit.AuditEventService> auditEventServiceProvider,
                          org.springframework.beans.factory.ObjectProvider<com.parth.ledger.security.AuthenticatedUserService> authenticatedUserServiceProvider) {
        this.customOAuth2UserService = customOAuth2UserService;
        this.customOidcUserService = customOidcUserService;
        this.objectMapper = objectMapper;
        this.allowedOriginsConfig = allowedOriginsConfig;
        this.frontendUrl = frontendUrl.endsWith("/") ? frontendUrl.substring(0, frontendUrl.length() - 1) : frontendUrl;
        this.auditEventServiceProvider = auditEventServiceProvider;
        this.authenticatedUserServiceProvider = authenticatedUserServiceProvider;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authenticationConfiguration) throws Exception {
        return authenticationConfiguration.getAuthenticationManager();
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public CsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookiePath("/");
        repository.setCookieCustomizer(builder -> builder
                .sameSite("Lax")
                .path("/")
        );
        return repository;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, CsrfTokenRepository csrfTokenRepository) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
                        .ignoringRequestMatchers("/api/v1/auth/signup", "/api/v1/auth/login")
                )
                .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .sessionFixation(SessionManagementConfigurer.SessionFixationConfigurer::changeSessionId)
                )
                .securityContext(securityContext -> securityContext
                        .securityContextRepository(securityContextRepository())
                )
                .exceptionHandling(exceptions -> exceptions
                        .defaultAuthenticationEntryPointFor(
                                (request, response, authException) -> {
                                    response.setStatus(HttpStatus.UNAUTHORIZED.value());
                                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                                    ErrorResponse errorResponse = new ErrorResponse(
                                            HttpStatus.UNAUTHORIZED.value(),
                                            HttpStatus.UNAUTHORIZED.getReasonPhrase(),
                                            "Authentication required",
                                            request.getRequestURI()
                                    );
                                    objectMapper.writeValue(response.getOutputStream(), errorResponse);
                                },
                                PathPatternRequestMatcher.pathPattern("/api/**")
                        )
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            response.setStatus(HttpStatus.FORBIDDEN.value());
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            ErrorResponse errorResponse = new ErrorResponse(
                                    HttpStatus.FORBIDDEN.value(),
                                    HttpStatus.FORBIDDEN.getReasonPhrase(),
                                    "Access denied",
                                    request.getRequestURI()
                            );
                            objectMapper.writeValue(response.getOutputStream(), errorResponse);
                        })
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/metrics", "/actuator/metrics/**").hasRole("ADMIN")
                        .requestMatchers("/oauth2/**", "/login/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/signup").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .requestMatchers(HttpMethod.POST, "/logout").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/accounts/*/freeze").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/accounts/*/unfreeze").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/accounts/*/close").authenticated()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().authenticated()
                )
                .oauth2Login(oauth2 -> oauth2
                        .defaultSuccessUrl(frontendUrl + "/app", true)
                        .failureUrl(frontendUrl + "/login?error=oauth2")
                        .userInfoEndpoint(userInfo -> userInfo
                                .userService(customOAuth2UserService)
                                .oidcUserService(customOidcUserService)
                        )
                )
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .addLogoutHandler((request, response, authentication) -> {
                            if (authentication != null && authentication.isAuthenticated()
                                    && !(authentication instanceof org.springframework.security.authentication.AnonymousAuthenticationToken)) {
                                try {
                                    com.parth.ledger.audit.AuditEventService auditService = auditEventServiceProvider.getIfAvailable();
                                    com.parth.ledger.security.AuthenticatedUserService authUserService = authenticatedUserServiceProvider.getIfAvailable();
                                    if (auditService != null && authUserService != null) {
                                        com.parth.ledger.user.User user = authUserService.getUserFromAuthentication(authentication);
                                        if (user != null) {
                                            auditService.recordEvent(
                                                    user.getId(),
                                                    com.parth.ledger.audit.AuditEventType.AUTH_LOGOUT,
                                                    com.parth.ledger.audit.AuditEntityType.USER,
                                                    user.getId(),
                                                    java.util.Map.of("email", user.getEmail())
                                            );
                                        }
                                    }
                                } catch (Exception e) {
                                    log.warn("Failed to record logout audit event: {}", e.getMessage());
                                }
                            }
                        })
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.OK))
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies("JSESSIONID", "XSRF-TOKEN")
                );
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        List<String> origins = Arrays.stream(allowedOriginsConfig.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .filter(s -> !s.equals("*")) // Wildcard origins forbidden when credentials are true
                .toList();
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(
                "Content-Type",
                "Idempotency-Key",
                "X-Request-Id",
                "X-Correlation-Id",
                "Authorization",
                "Accept",
                "Origin",
                "X-XSRF-TOKEN",
                "X-CSRF-TOKEN"
        ));
        configuration.setExposedHeaders(List.of("X-Request-Id", "X-Correlation-Id"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}

