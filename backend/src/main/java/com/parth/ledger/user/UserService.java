package com.parth.ledger.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Service managing application user persistence and identity synchronization
 * between external OAuth2 providers and PostgreSQL.
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Resolves an existing application user by email or creates a new one
     * from Google OAuth2 profile details if none exists.
     *
     * Concurrency-safe: catches database unique constraint violations to prevent
     * duplicate user creation races for the same email.
     *
     * @param email The authenticated email from OAuth2.
     * @param name The display name from OAuth2.
     * @return The persistent application User entity.
     */
    @Transactional
    public User syncOAuth2User(String email, String name) {
        if (email == null || email.trim().isEmpty()) {
            throw new IllegalArgumentException("OAuth2 user email must not be blank");
        }
        String cleanEmail = email.trim().toLowerCase();
        String displayName = (name != null && !name.trim().isEmpty()) ? name.trim() : cleanEmail;

        return userRepository.findByEmail(cleanEmail)
                .orElseGet(() -> {
                    try {
                        log.info("Provisioning new application user for OAuth2 email: {}", cleanEmail);
                        return userRepository.save(new User(cleanEmail, displayName));
                    } catch (DataIntegrityViolationException e) {
                        log.info("User with email '{}' was created concurrently, fetching existing record", cleanEmail);
                        return userRepository.findByEmail(cleanEmail)
                                .orElseThrow(() -> e);
                    }
                });
    }

    /**
     * Finds an application user by email address (case-insensitive lookup attempt).
     *
     * @param email Email address to search for.
     * @return Optional containing the User if found.
     */
    public Optional<User> findByEmail(String email) {
        if (email == null || email.trim().isEmpty()) {
            return Optional.empty();
        }
        String trimmed = email.trim();
        Optional<User> directMatch = userRepository.findByEmail(trimmed);
        if (directMatch.isPresent()) {
            return directMatch;
        }
        return userRepository.findByEmail(trimmed.toLowerCase());
    }
}
