package com.parth.ledger.security.controller;

import com.parth.ledger.security.AuthenticatedUserService;
import com.parth.ledger.security.CustomUserDetailsService;
import com.parth.ledger.security.UserAuthService;
import com.parth.ledger.security.UserNotFoundException;
import com.parth.ledger.security.dto.LinkPasswordRequestDto;
import com.parth.ledger.security.dto.LoginRequestDto;
import com.parth.ledger.security.dto.SignupRequestDto;
import com.parth.ledger.user.User;
import com.parth.ledger.user.UserService;
import com.parth.ledger.user.dto.UserResponseDto;
import com.parth.ledger.security.ratelimit.ClientIpResolver;
import com.parth.ledger.security.ratelimit.RedisRateLimiterService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthenticatedUserService authenticatedUserService;
    private final UserAuthService userAuthService;
    private final UserService userService;
    private final AuthenticationManager authenticationManager;
    private final CustomUserDetailsService customUserDetailsService;
    private final SecurityContextRepository securityContextRepository;
    private final RedisRateLimiterService rateLimiterService;
    private final ClientIpResolver clientIpResolver;

    public AuthController(AuthenticatedUserService authenticatedUserService,
                          UserAuthService userAuthService,
                          UserService userService,
                          AuthenticationManager authenticationManager,
                          CustomUserDetailsService customUserDetailsService,
                          SecurityContextRepository securityContextRepository,
                          RedisRateLimiterService rateLimiterService,
                          ClientIpResolver clientIpResolver) {
        this.authenticatedUserService = authenticatedUserService;
        this.userAuthService = userAuthService;
        this.userService = userService;
        this.authenticationManager = authenticationManager;
        this.customUserDetailsService = customUserDetailsService;
        this.securityContextRepository = securityContextRepository;
        this.rateLimiterService = rateLimiterService;
        this.clientIpResolver = clientIpResolver;
    }

    /**
     * Retrieves current CSRF token metadata for Single Page Applications and triggers cookie issuance.
     *
     * @param request The HTTP request containing the CSRF token attribute.
     * @return 200 OK with CSRF token information.
     */
    @GetMapping("/csrf")
    public ResponseEntity<Map<String, String>> getCsrfToken(HttpServletRequest request) {
        CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (csrfToken == null) {
            csrfToken = (CsrfToken) request.getAttribute("_csrf");
        }
        if (csrfToken == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(Map.of(
                "headerName", csrfToken.getHeaderName(),
                "parameterName", csrfToken.getParameterName(),
                "token", csrfToken.getToken()
        ));
    }

    /**
     * Returns the safe profile details of the currently authenticated application user.
     *
     * @return 200 OK with UserResponseDto containing id, email, and name.
     */
    @GetMapping("/me")
    public ResponseEntity<UserResponseDto> getCurrentUser() {
        User user = authenticatedUserService.getCurrentUser();
        return ResponseEntity.ok(UserResponseDto.from(user));
    }

    /**
     * Registers a new user with email and password, establishing an authenticated session.
     *
     * @param request Validated signup payload.
     * @param httpRequest The HTTP servlet request.
     * @param httpResponse The HTTP servlet response.
     * @return 201 Created with safe UserResponseDto.
     */
    @PostMapping("/signup")
    public ResponseEntity<UserResponseDto> signup(@Valid @RequestBody SignupRequestDto request,
                                                  HttpServletRequest httpRequest,
                                                  HttpServletResponse httpResponse) {
        String clientIp = clientIpResolver.resolveClientIp(httpRequest);
        rateLimiterService.checkAndRecordSignup(clientIp);

        User user = userAuthService.signup(request);

        UserDetails userDetails = customUserDetailsService.loadUserByUsername(user.getEmail());
        Authentication auth = new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());

        if (httpRequest.getSession(false) != null) {
            httpRequest.changeSessionId();
        } else {
            httpRequest.getSession(true);
        }

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, httpRequest, httpResponse);

        return ResponseEntity.status(HttpStatus.CREATED).body(UserResponseDto.from(user));
    }

    @PostMapping("/login")
    public ResponseEntity<UserResponseDto> login(@Valid @RequestBody LoginRequestDto request,
                                                 HttpServletRequest httpRequest,
                                                 HttpServletResponse httpResponse) {
        String normalizedEmail = request.normalizedEmail();
        String clientIp = clientIpResolver.resolveClientIp(httpRequest);

        rateLimiterService.checkLoginAllowed(clientIp, normalizedEmail);

        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(normalizedEmail, request.password())
            );
        } catch (AuthenticationException ex) {
            rateLimiterService.recordFailedLogin(clientIp, normalizedEmail);
            throw ex;
        }

        rateLimiterService.recordSuccessfulLogin(clientIp, normalizedEmail);

        if (httpRequest.getSession(false) != null) {
            httpRequest.changeSessionId();
        } else {
            httpRequest.getSession(true);
        }

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, httpRequest, httpResponse);

        User user = userService.findByEmail(normalizedEmail)
                .orElseThrow(() -> new UserNotFoundException("User not found for authenticated email: " + normalizedEmail));

        return ResponseEntity.ok(UserResponseDto.from(user));
    }

    /**
     * Links a password credential to the currently authenticated user (e.g. for Google OAuth users).
     *
     * @param request Validated password linking payload.
     * @return 200 OK.
     */
    @PostMapping("/link-password")
    public ResponseEntity<Void> linkPassword(@Valid @RequestBody LinkPasswordRequestDto request) {
        User currentUser = authenticatedUserService.getCurrentUser();
        userAuthService.linkPassword(currentUser, request.password());
        return ResponseEntity.ok().build();
    }
}

