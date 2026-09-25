package com.parth.ledger.security;

import com.parth.ledger.BaseIntegrationTest;
import com.parth.ledger.account.AccountRepository;
import com.parth.ledger.ledger.LedgerEntryRepository;
import com.parth.ledger.transaction.TransactionRepository;
import com.parth.ledger.user.User;
import com.parth.ledger.user.UserRepository;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.web.OAuth2LoginAuthenticationFilter;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AbstractAuthenticationProcessingFilter;
import org.springframework.security.web.authentication.AbstractAuthenticationTargetUrlRequestHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class AuthMeIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private SecurityFilterChain securityFilterChain;

    private User aliceUser;

    @BeforeEach
    void setUp() {
        clearRedis();
        ledgerEntryRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();
        aliceUser = userRepository.save(new User("alice.auth@ledger.com", "Alice Auth"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        if (jdbcTemplate != null) {
            jdbcTemplate.execute("TRUNCATE TABLE ledger_entries CASCADE");
        }
        ledgerEntryRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("1. Unauthenticated GET /api/v1/auth/me returns HTTP 401 Unauthorized")
    void unauthenticatedGetAuthMeReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("2. Authenticated GET /api/v1/auth/me returns the authenticated application user")
    void authenticatedGetAuthMeReturnsApplicationUser() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me")
                        .with(user("alice.auth@ledger.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(aliceUser.getId().toString())))
                .andExpect(jsonPath("$.email", is("alice.auth@ledger.com")))
                .andExpect(jsonPath("$.name", is("Alice Auth")));
    }

    @Test
    @DisplayName("3. GET /api/v1/auth/me exposes only safe identity fields and no sensitive details")
    void authenticatedGetAuthMeDoesNotExposeSensitiveFields() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me")
                        .with(user("alice.auth@ledger.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.email").exists())
                .andExpect(jsonPath("$.name").exists())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(jsonPath("$.authorities").doesNotExist())
                .andExpect(jsonPath("$.roles").doesNotExist())
                .andExpect(jsonPath("$.attributes").doesNotExist())
                .andExpect(jsonPath("$.principal").doesNotExist())
                .andExpect(jsonPath("$.createdAt").doesNotExist())
                .andExpect(jsonPath("$.updatedAt").doesNotExist());
    }

    @Test
    @DisplayName("4. Authenticated with OAuth2User principal returns the persistent application user")
    void authenticatedWithOAuth2UserReturnsApplicationUser() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me")
                        .with(oauth2Login().attributes(attrs -> attrs.put("email", "alice.auth@ledger.com"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(aliceUser.getId().toString())))
                .andExpect(jsonPath("$.email", is("alice.auth@ledger.com")))
                .andExpect(jsonPath("$.name", is("Alice Auth")));
    }

    @Test
    @DisplayName("5. Authenticated with CustomOAuth2User principal returns application user")
    void authenticatedWithCustomOAuth2UserPrincipalReturnsApplicationUser() throws Exception {
        CustomOAuth2User customPrincipal = new CustomOAuth2User(
                List.of(new SimpleGrantedAuthority("ROLE_USER")),
                Map.of("email", "alice.auth@ledger.com", "name", "Alice Auth"),
                "email",
                aliceUser
        );
        OAuth2AuthenticationToken authToken = new OAuth2AuthenticationToken(
                customPrincipal, customPrincipal.getAuthorities(), "google"
        );

        mockMvc.perform(get("/api/v1/auth/me")
                        .with(authentication(authToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(aliceUser.getId().toString())))
                .andExpect(jsonPath("$.email", is("alice.auth@ledger.com")))
                .andExpect(jsonPath("$.name", is("Alice Auth")));
    }

    @Test
    @DisplayName("6. Authenticated with CustomOidcUser principal returns application user")
    void authenticatedWithCustomOidcUserPrincipalReturnsApplicationUser() throws Exception {
        OidcIdToken idToken = new OidcIdToken(
                "token-value",
                Instant.now(),
                Instant.now().plusSeconds(3600),
                Map.of("sub", "google-sub-12345", "email", "alice.auth@ledger.com", "name", "Alice Auth")
        );
        CustomOidcUser customOidcUser = new CustomOidcUser(
                List.of(new SimpleGrantedAuthority("ROLE_USER")),
                idToken,
                "email",
                aliceUser
        );
        OAuth2AuthenticationToken authToken = new OAuth2AuthenticationToken(
                customOidcUser, customOidcUser.getAuthorities(), "google"
        );

        mockMvc.perform(get("/api/v1/auth/me")
                        .with(authentication(authToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(aliceUser.getId().toString())))
                .andExpect(jsonPath("$.email", is("alice.auth@ledger.com")))
                .andExpect(jsonPath("$.name", is("Alice Auth")));
    }

    @Test
    @DisplayName("7. Non-existent authenticated user returns HTTP 403 Forbidden")
    void authenticatedNonexistentUserReturns403() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me")
                        .with(user("ghost.user@ledger.com")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status", is(403)))
                .andExpect(jsonPath("$.message", containsString("user not found")));
    }

    @Test
    @DisplayName("8. OAuth login configuration starts correctly and configures default redirect to frontend /app")
    void oauthLoginConfigurationStartsCorrectly() throws Exception {
        // Verify OAuth2 authorization initiation endpoint starts and redirects to Google
        mockMvc.perform(get("/oauth2/authorization/google"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("accounts.google.com/o/oauth2/v2/auth")));

        // Verify SecurityFilterChain contains OAuth2LoginAuthenticationFilter configured with defaultSuccessUrl
        Filter oauth2Filter = securityFilterChain.getFilters().stream()
                .filter(f -> f instanceof OAuth2LoginAuthenticationFilter)
                .findFirst()
                .orElse(null);

        assertThat(oauth2Filter).isNotNull();

        Field successHandlerField = AbstractAuthenticationProcessingFilter.class.getDeclaredField("successHandler");
        successHandlerField.setAccessible(true);
        AuthenticationSuccessHandler successHandler = (AuthenticationSuccessHandler) successHandlerField.get(oauth2Filter);

        assertThat(successHandler).isInstanceOf(SavedRequestAwareAuthenticationSuccessHandler.class);

        Field defaultTargetUrlField = AbstractAuthenticationTargetUrlRequestHandler.class.getDeclaredField("defaultTargetUrl");
        defaultTargetUrlField.setAccessible(true);
        String defaultTargetUrl = (String) defaultTargetUrlField.get(successHandler);
        assertThat(defaultTargetUrl).isEqualTo("http://localhost:3001/app");

        Field alwaysUseField = AbstractAuthenticationTargetUrlRequestHandler.class.getDeclaredField("alwaysUseDefaultTargetUrl");
        alwaysUseField.setAccessible(true);
        boolean alwaysUse = (boolean) alwaysUseField.get(successHandler);
        assertThat(alwaysUse).isTrue();

        // Verify that invoking the success handler executes the redirect to frontend /app
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        Authentication auth = new UsernamePasswordAuthenticationToken("alice.auth@ledger.com", null, Collections.emptyList());

        successHandler.onAuthenticationSuccess(request, response, auth);
        assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost:3001/app");
    }
}
