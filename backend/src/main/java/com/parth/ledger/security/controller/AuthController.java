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
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller handling authentication and identity verification endpoints.
 * Supports session retrieval, email/password signup, email/password login, and credential linking.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthenticatedUserService authenticatedUserService;
    private final UserAuthService userAuthService;
    private final UserService userService;
    private final AuthenticationManager authenticationManager;
    private final CustomUserDetailsService customUserDetailsService;
    private final SecurityContextRepository securityContextRepository;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;

    public AuthController(AuthenticatedUserService authenticatedUserService,
                          UserAuthService userAuthService,
                          UserService userService,
                          AuthenticationManager authenticationManager,
                          CustomUserDetailsService customUserDetailsService,
                          SecurityContextRepository securityContextRepository,
                          SessionAuthenticationStrategy sessionAuthenticationStrategy) {
        this.authenticatedUserService = authenticatedUserService;
        this.userAuthService = userAuthService;
        this.userService = userService;
        this.authenticationManager = authenticationManager;
        this.customUserDetailsService = customUserDetailsService;
        this.securityContextRepository = securityContextRepository;
        this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
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
        User user = userAuthService.signup(request);

        // Establish authenticated session immediately upon successful signup
        UserDetails userDetails = customUserDetailsService.loadUserByUsername(user.getEmail());
        Authentication auth = new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());

        // Rotate session ID to protect against session fixation attacks before establishing authenticated session
        sessionAuthenticationStrategy.onAuthentication(auth, httpRequest, httpResponse);

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, httpRequest, httpResponse);

        return ResponseEntity.status(HttpStatus.CREATED).body(UserResponseDto.from(user));
    }

    /**
     * Authenticates a user with email and password, creating a Spring Security session.
     *
     * @param request Validated login payload.
     * @param httpRequest The HTTP servlet request.
     * @param httpResponse The HTTP servlet response.
     * @return 200 OK with safe UserResponseDto.
     */
    @PostMapping("/login")
    public ResponseEntity<UserResponseDto> login(@Valid @RequestBody LoginRequestDto request,
                                                 HttpServletRequest httpRequest,
                                                 HttpServletResponse httpResponse) {
        String normalizedEmail = request.normalizedEmail();

        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(normalizedEmail, request.password())
        );

        // Rotate session ID to protect against session fixation attacks before establishing authenticated session
        sessionAuthenticationStrategy.onAuthentication(authentication, httpRequest, httpResponse);

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

