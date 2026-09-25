package com.parth.ledger.security;

import com.parth.ledger.security.dto.SignupRequestDto;
import com.parth.ledger.user.User;
import com.parth.ledger.user.UserCredential;
import com.parth.ledger.user.UserCredentialRepository;
import com.parth.ledger.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service orchestrating user signup, credential storage, and password management.
 * Ensures atomic persistence of user identity and hashed credentials.
 */
@Service
public class UserAuthService {

    private static final Logger log = LoggerFactory.getLogger(UserAuthService.class);

    private final UserRepository userRepository;
    private final UserCredentialRepository userCredentialRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicyValidator passwordPolicyValidator;

    public UserAuthService(UserRepository userRepository,
                           UserCredentialRepository userCredentialRepository,
                           PasswordEncoder passwordEncoder,
                           PasswordPolicyValidator passwordPolicyValidator) {
        this.userRepository = userRepository;
        this.userCredentialRepository = userCredentialRepository;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicyValidator = passwordPolicyValidator;
    }

    /**
     * Registers a new user with email and password credentials atomically.
     *
     * @param request Validated signup request details.
     * @return The newly persisted User entity.
     * @throws DuplicateEmailException if an account already exists with the given email.
     * @throws InvalidPasswordException if the password violates security policy.
     */
    @Transactional
    public User signup(SignupRequestDto request) {
        String normalizedEmail = request.normalizedEmail();
        String trimmedName = request.trimmedName();

        passwordPolicyValidator.validate(request.password());

        if (userRepository.existsByEmail(normalizedEmail)) {
            log.warn("Signup rejected: account already exists for submitted email");
            throw new DuplicateEmailException("An account with this email already exists");
        }

        try {
            User user = userRepository.save(new User(normalizedEmail, trimmedName));
            String passwordHash = passwordEncoder.encode(request.password());
            userCredentialRepository.save(new UserCredential(user.getId(), passwordHash));
            log.info("Registered new user and credential successfully for user ID: {}", user.getId());
            return user;
        } catch (DataIntegrityViolationException e) {
            log.warn("Database conflict during user registration", e);
            throw new DuplicateEmailException("An account with this email already exists");
        }
    }

    /**
     * Links or updates a password credential for an already authenticated user.
     * Used when a user registered via OAuth2 wants to add email/password access.
     *
     * @param user The authenticated application user.
     * @param rawPassword The raw password to validate, hash, and persist.
     */
    @Transactional
    public void linkPassword(User user, String rawPassword) {
        passwordPolicyValidator.validate(rawPassword);
        String passwordHash = passwordEncoder.encode(rawPassword);

        UserCredential credential = userCredentialRepository.findByUserId(user.getId())
                .orElseGet(() -> new UserCredential(user.getId(), passwordHash));

        credential.setPasswordHash(passwordHash);
        userCredentialRepository.save(credential);
        log.info("Linked/updated password credential for user ID: {}", user.getId());
    }

    /**
     * Checks whether an application user has a configured password credential.
     *
     * @param user The application user.
     * @return true if password credentials exist, false otherwise.
     */
    public boolean hasPasswordCredential(User user) {
        return userCredentialRepository.existsByUserId(user.getId());
    }
}
