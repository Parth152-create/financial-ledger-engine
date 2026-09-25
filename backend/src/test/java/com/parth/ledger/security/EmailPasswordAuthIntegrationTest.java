package com.parth.ledger.security;

import com.parth.ledger.BaseIntegrationTest;
import com.parth.ledger.account.AccountRepository;
import com.parth.ledger.account.dto.CreateAccountRequestDto;
import com.parth.ledger.ledger.LedgerEntryRepository;
import com.parth.ledger.security.dto.LinkPasswordRequestDto;
import com.parth.ledger.security.dto.LoginRequestDto;
import com.parth.ledger.security.dto.SignupRequestDto;
import com.parth.ledger.transaction.TransactionRepository;
import com.parth.ledger.user.User;
import com.parth.ledger.user.UserCredential;
import com.parth.ledger.user.UserCredentialRepository;
import com.parth.ledger.user.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class EmailPasswordAuthIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserCredentialRepository userCredentialRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private UserAuthService userAuthService;

    @BeforeEach
    void setUp() {
        clearRedis();
        cleanupDatabase();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        cleanupDatabase();
    }

    private void cleanupDatabase() {
        if (jdbcTemplate != null) {
            jdbcTemplate.execute("TRUNCATE TABLE ledger_entries CASCADE");
        }
        ledgerEntryRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        userCredentialRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("1. Successful signup creates user and credential atomically, establishes session, returns 201")
    void successfulSignupCreatesUserAndCredential() throws Exception {
        SignupRequestDto signupDto = new SignupRequestDto("Alice Smith", "alice.smith@example.com", "SecurePass123");

        MvcResult result = mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(signupDto)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.email", is("alice.smith@example.com")))
                .andExpect(jsonPath("$.name", is("Alice Smith")))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andReturn();

        // Verify database persistence
        Optional<User> userOpt = userRepository.findByEmail("alice.smith@example.com");
        assertThat(userOpt).isPresent();
        User user = userOpt.get();

        Optional<UserCredential> credentialOpt = userCredentialRepository.findByUserId(user.getId());
        assertThat(credentialOpt).isPresent();
        assertThat(passwordEncoder.matches("SecurePass123", credentialOpt.get().getPasswordHash())).isTrue();

        // Verify session was established upon signup
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).isNotNull();

        mockMvc.perform(get("/api/v1/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email", is("alice.smith@example.com")));
    }

    @Test
    @DisplayName("2. Duplicate email signup returns HTTP 409 Conflict safely")
    void duplicateEmailSignupReturnsConflict() throws Exception {
        userRepository.save(new User("existing@example.com", "Existing User"));

        SignupRequestDto signupDto = new SignupRequestDto("Duplicate User", "existing@example.com", "AnotherPass456");

        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(signupDto)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status", is(409)))
                .andExpect(jsonPath("$.message", containsString("already exists")));
    }

    @Test
    @DisplayName("3. Passwords are securely hashed with BCrypt and plaintext is never stored")
    void passwordIsHashedWithBcrypt() throws Exception {
        SignupRequestDto signupDto = new SignupRequestDto("Bob Hash", "bob.hash@example.com", "SecretVault999");

        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(signupDto)))
                .andExpect(status().isCreated());

        User user = userRepository.findByEmail("bob.hash@example.com").orElseThrow();
        UserCredential credential = userCredentialRepository.findByUserId(user.getId()).orElseThrow();

        assertThat(credential.getPasswordHash()).startsWith("$2a$");
        assertThat(credential.getPasswordHash()).isNotEqualTo("SecretVault999");
        assertThat(passwordEncoder.matches("SecretVault999", credential.getPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("4. Sensitive credential data is never returned in signup or login API responses")
    void sensitiveCredentialDataNeverExposedInResponses() throws Exception {
        SignupRequestDto signupDto = new SignupRequestDto("Safe Response", "safe@example.com", "P@ssword123");

        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(signupDto)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.hash").doesNotExist())
                .andExpect(jsonPath("$.credential").doesNotExist());

        LoginRequestDto loginDto = new LoginRequestDto("safe@example.com", "P@ssword123");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.hash").doesNotExist())
                .andExpect(jsonPath("$.credential").doesNotExist());
    }

    @Test
    @DisplayName("5. Successful login establishes authenticated Spring Security session")
    void successfulLoginEstablishesSession() throws Exception {
        SignupRequestDto signupDto = new SignupRequestDto("Login User", "login.user@example.com", "LoginValid123");
        userAuthService.signup(signupDto);

        LoginRequestDto loginDto = new LoginRequestDto("login.user@example.com", "LoginValid123");

        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email", is("login.user@example.com")))
                .andExpect(jsonPath("$.name", is("Login User")))
                .andReturn();

        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);
        assertThat(session).isNotNull();

        // Verify session works on /api/v1/auth/me
        mockMvc.perform(get("/api/v1/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email", is("login.user@example.com")));
    }

    @Test
    @DisplayName("6. Invalid password returns HTTP 401 Unauthorized without leaking info")
    void invalidPasswordReturns401() throws Exception {
        SignupRequestDto signupDto = new SignupRequestDto("User One", "user.one@example.com", "CorrectPassword1");
        userAuthService.signup(signupDto);

        LoginRequestDto loginDto = new LoginRequestDto("user.one@example.com", "WrongPassword2");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginDto)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status", is(401)))
                .andExpect(jsonPath("$.message", is("Invalid email or password")));
    }

    @Test
    @DisplayName("7. Unknown email returns HTTP 401 Unauthorized without leaking user existence")
    void unknownEmailReturns401() throws Exception {
        LoginRequestDto loginDto = new LoginRequestDto("nonexistent@example.com", "AnyPassword123");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginDto)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status", is(401)))
                .andExpect(jsonPath("$.message", is("Invalid email or password")));
    }

    @Test
    @DisplayName("8. Logout invalidates session and subsequent /api/v1/auth/me returns 401")
    void logoutInvalidatesSession() throws Exception {
        SignupRequestDto signupDto = new SignupRequestDto("Logout User", "logout.user@example.com", "LogoutPass123");
        userAuthService.signup(signupDto);

        LoginRequestDto loginDto = new LoginRequestDto("logout.user@example.com", "LogoutPass123");

        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginDto)))
                .andExpect(status().isOk())
                .andReturn();

        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);
        assertThat(session).isNotNull();

        // Calling /logout with the session
        mockMvc.perform(post("/logout").session(session))
                .andExpect(status().isOk());

        // Subsequent authenticated request using the invalidated session returns 401
        mockMvc.perform(get("/api/v1/auth/me").session(session))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("9. Google OAuth regression: OAuth2 user can authenticate and access /api/v1/auth/me")
    void googleOAuthRegression() throws Exception {
        User googleUser = userRepository.save(new User("google.user@example.com", "Google User"));

        mockMvc.perform(get("/api/v1/auth/me")
                        .with(oauth2Login().attributes(attrs -> {
                            attrs.put("email", "google.user@example.com");
                            attrs.put("name", "Google User");
                        })))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(googleUser.getId().toString())))
                .andExpect(jsonPath("$.email", is("google.user@example.com")));
    }

    @Test
    @DisplayName("10. API access regression: Email/password authenticated user can create accounts")
    void authenticatedUserCanCreateAccounts() throws Exception {
        SignupRequestDto signupDto = new SignupRequestDto("Account Op", "account.op@example.com", "AccountPass123");
        userAuthService.signup(signupDto);

        LoginRequestDto loginDto = new LoginRequestDto("account.op@example.com", "AccountPass123");

        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginDto)))
                .andExpect(status().isOk())
                .andReturn();

        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);

        // Perform account creation with this session
        CreateAccountRequestDto accountRequest = new CreateAccountRequestDto("INR");

        mockMvc.perform(post("/api/v1/accounts")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(accountRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currency", is("INR")))
                .andExpect(jsonPath("$.balance", is(0.0000)))
                .andExpect(jsonPath("$.status", is("ACTIVE")));
    }

    @Test
    @DisplayName("11. Conflict Case A: Email/password user later signs in with Google maps to same user without removing password")
    void caseA_emailPasswordUserLaterUsesGoogle() throws Exception {
        SignupRequestDto signupDto = new SignupRequestDto("Dual User", "dual.auth@example.com", "DualPassword123");
        User createdUser = userAuthService.signup(signupDto);

        // Google signin with same email
        mockMvc.perform(get("/api/v1/auth/me")
                        .with(oauth2Login().attributes(attrs -> {
                            attrs.put("email", "dual.auth@example.com");
                            attrs.put("name", "Dual User Google");
                        })))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(createdUser.getId().toString())))
                .andExpect(jsonPath("$.email", is("dual.auth@example.com")));

        // Verify password credential still exists and user can still log in with password
        LoginRequestDto loginDto = new LoginRequestDto("dual.auth@example.com", "DualPassword123");
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginDto)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("12. Conflict Case B: User signs in with Google and later links a password while authenticated")
    void caseB_googleUserLinksPassword() throws Exception {
        User googleUser = userRepository.save(new User("google.only@example.com", "Google Only"));

        // User is authenticated via Google (or mock user), calls link-password
        LinkPasswordRequestDto linkDto = new LinkPasswordRequestDto("NewlyLinkedPass123");

        mockMvc.perform(post("/api/v1/auth/link-password")
                        .with(user("google.only@example.com"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(linkDto)))
                .andExpect(status().isOk());

        // Now user can log in with email and the newly linked password
        LoginRequestDto loginDto = new LoginRequestDto("google.only@example.com", "NewlyLinkedPass123");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(googleUser.getId().toString())));
    }

    @Test
    @DisplayName("13. Conflict Case C: Attacker tries to register email already owned by Google user -> rejected with 409")
    void caseC_attemptSignupWithGoogleEmailIsRejected() throws Exception {
        userRepository.save(new User("victim.google@example.com", "Victim Google User"));

        SignupRequestDto hijackAttempt = new SignupRequestDto("Attacker", "victim.google@example.com", "AttackerPass123");

        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(hijackAttempt)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status", is(409)))
                .andExpect(jsonPath("$.message", containsString("already exists")));
    }

    @Test
    @DisplayName("14. Password policy validation rejects passwords that are weak or malformed")
    void passwordPolicyValidation() throws Exception {
        // Less than 8 chars
        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SignupRequestDto("Weak", "weak1@example.com", "short1"))))
                .andExpect(status().isBadRequest());

        // No digits
        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SignupRequestDto("Weak", "weak2@example.com", "lettersOnlyPass"))))
                .andExpect(status().isBadRequest());

        // No letters
        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SignupRequestDto("Weak", "weak3@example.com", "1234567890"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("15. Session fixation protection: Login rotates session ID when pre-existing session is present")
    void loginRotatesSessionId() throws Exception {
        SignupRequestDto signupDto = new SignupRequestDto("Session User", "session.login@example.com", "SessionPass123");
        userAuthService.signup(signupDto);

        MockHttpSession preAuthSession = new MockHttpSession();
        String originalSessionId = preAuthSession.getId();
        preAuthSession.setAttribute("TRACKING_DATA", "pre-auth-token-xyz");

        LoginRequestDto loginDto = new LoginRequestDto("session.login@example.com", "SessionPass123");

        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .session(preAuthSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email", is("session.login@example.com")))
                .andReturn();

        MockHttpSession postAuthSession = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(postAuthSession).isNotNull();
        assertThat(postAuthSession.getId())
                .as("Session ID must be rotated after successful login to prevent session fixation")
                .isNotEqualTo(originalSessionId);
        assertThat(postAuthSession.getAttribute("TRACKING_DATA"))
                .as("Session attributes should be preserved across session ID change")
                .isEqualTo("pre-auth-token-xyz");

        // Verify the rotated session is authenticated and can access protected endpoints
        mockMvc.perform(get("/api/v1/auth/me").session(postAuthSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email", is("session.login@example.com")));
    }

    @Test
    @DisplayName("16. Session fixation protection: Signup rotates session ID when pre-existing session is present")
    void signupRotatesSessionId() throws Exception {
        MockHttpSession preAuthSession = new MockHttpSession();
        String originalSessionId = preAuthSession.getId();
        preAuthSession.setAttribute("TRACKING_DATA", "pre-signup-token-123");

        SignupRequestDto signupDto = new SignupRequestDto("Signup Rotate", "signup.rotate@example.com", "SignupPass123");

        MvcResult result = mockMvc.perform(post("/api/v1/auth/signup")
                        .session(preAuthSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(signupDto)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email", is("signup.rotate@example.com")))
                .andReturn();

        MockHttpSession postAuthSession = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(postAuthSession).isNotNull();
        assertThat(postAuthSession.getId())
                .as("Session ID must be rotated after successful signup to prevent session fixation")
                .isNotEqualTo(originalSessionId);
        assertThat(postAuthSession.getAttribute("TRACKING_DATA"))
                .as("Session attributes should be preserved across session ID change")
                .isEqualTo("pre-signup-token-123");

        // Verify the rotated session is authenticated and can access protected endpoints
        mockMvc.perform(get("/api/v1/auth/me").session(postAuthSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email", is("signup.rotate@example.com")));
    }
}
