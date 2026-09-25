package com.parth.ledger.security;

import com.parth.ledger.user.User;
import com.parth.ledger.user.UserCredential;
import com.parth.ledger.user.UserCredentialRepository;
import com.parth.ledger.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Standard Spring Security UserDetailsService implementation for email/password authentication.
 * Loads user by normalized email and resolves the password hash from user_credentials.
 */
@Service
public class CustomUserDetailsService implements UserDetailsService {

    private static final Logger log = LoggerFactory.getLogger(CustomUserDetailsService.class);

    private final UserRepository userRepository;
    private final UserCredentialRepository userCredentialRepository;
    private final List<String> adminEmails;

    public CustomUserDetailsService(
            UserRepository userRepository,
            UserCredentialRepository userCredentialRepository,
            @Value("${ledger.security.admin-emails:admin@ledger.com}") String adminEmailsConfig) {
        this.userRepository = userRepository;
        this.userCredentialRepository = userCredentialRepository;
        this.adminEmails = adminEmailsConfig != null
                ? Arrays.stream(adminEmailsConfig.split(",")).map(String::trim).map(String::toLowerCase).toList()
                : List.of("admin@ledger.com");
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        if (username == null || username.isBlank()) {
            throw new UsernameNotFoundException("Invalid email or password");
        }

        String normalizedEmail = username.trim().toLowerCase();
        User user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> {
                    log.debug("Login failed: no user record found for submitted principal");
                    return new UsernameNotFoundException("Invalid email or password");
                });

        UserCredential credential = userCredentialRepository.findByUserId(user.getId())
                .orElseThrow(() -> {
                    log.debug("Login failed: user {} has no password credential configured", user.getId());
                    return new UsernameNotFoundException("Invalid email or password");
                });

        Set<GrantedAuthority> authorities = new HashSet<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
        if (adminEmails.contains(normalizedEmail)) {
            authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        }

        return org.springframework.security.core.userdetails.User.builder()
                .username(user.getEmail())
                .password(credential.getPasswordHash())
                .authorities(authorities)
                .build();
    }
}
