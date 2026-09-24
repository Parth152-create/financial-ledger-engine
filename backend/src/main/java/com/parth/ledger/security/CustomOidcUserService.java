package com.parth.ledger.security;

import com.parth.ledger.user.User;
import com.parth.ledger.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Custom OpenID Connect user service that loads user information from the OIDC provider (Google)
 * and maps the identity to a persistent PostgreSQL User.
 */
@Service
public class CustomOidcUserService extends OidcUserService {

    private static final Logger log = LoggerFactory.getLogger(CustomOidcUserService.class);

    private final UserService userService;
    private final List<String> adminEmails;

    public CustomOidcUserService(
            UserService userService,
            @Value("${ledger.security.admin-emails:admin@ledger.com}") String adminEmailsConfig) {
        this.userService = userService;
        this.adminEmails = adminEmailsConfig != null
                ? Arrays.stream(adminEmailsConfig.split(",")).map(String::trim).map(String::toLowerCase).toList()
                : List.of("admin@ledger.com");
    }

    @Override
    public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
        OidcUser oidcUser = super.loadUser(userRequest);
        String email = oidcUser.getEmail();
        if (email == null || email.isBlank()) {
            email = oidcUser.getAttribute("email");
        }
        String name = oidcUser.getFullName();
        if (name == null || name.isBlank()) {
            name = oidcUser.getAttribute("name");
        }

        if (email == null || email.trim().isEmpty()) {
            log.error("OIDC user claims missing email");
            throw new OAuth2AuthenticationException(new OAuth2Error("missing_email"), "Email claim is required from OIDC provider");
        }

        User user = userService.syncOAuth2User(email, name);

        String userNameAttributeName = userRequest.getClientRegistration()
                .getProviderDetails().getUserInfoEndpoint().getUserNameAttributeName();
        if (userNameAttributeName == null || userNameAttributeName.isBlank()) {
            userNameAttributeName = "email";
        }

        Set<GrantedAuthority> authorities = new HashSet<>(oidcUser.getAuthorities());
        if (adminEmails.contains(email.trim().toLowerCase())) {
            authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        }

        if (oidcUser.getUserInfo() != null) {
            return new CustomOidcUser(authorities, oidcUser.getIdToken(), oidcUser.getUserInfo(), userNameAttributeName, user);
        } else {
            return new CustomOidcUser(authorities, oidcUser.getIdToken(), userNameAttributeName, user);
        }
    }
}
