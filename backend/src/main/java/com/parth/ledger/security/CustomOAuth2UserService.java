package com.parth.ledger.security;

import com.parth.ledger.user.User;
import com.parth.ledger.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Custom OAuth2 user service that loads user information from the OAuth2 provider
 * and maps the identity to a persistent PostgreSQL User.
 */
@Service
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    private static final Logger log = LoggerFactory.getLogger(CustomOAuth2UserService.class);

    private final UserService userService;
    private final List<String> adminEmails;

    public CustomOAuth2UserService(
            UserService userService,
            @Value("${ledger.security.admin-emails:admin@ledger.com}") String adminEmailsConfig) {
        this.userService = userService;
        this.adminEmails = adminEmailsConfig != null
                ? Arrays.stream(adminEmailsConfig.split(",")).map(String::trim).map(String::toLowerCase).toList()
                : List.of("admin@ledger.com");
    }

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oauth2User = super.loadUser(userRequest);
        String email = oauth2User.getAttribute("email");
        String name = oauth2User.getAttribute("name");

        if (email == null || email.trim().isEmpty()) {
            log.error("OAuth2 user attributes missing email");
            throw new OAuth2AuthenticationException(new OAuth2Error("missing_email"), "Email attribute is required from OAuth2 provider");
        }

        User user = userService.syncOAuth2User(email, name);

        String userNameAttributeName = userRequest.getClientRegistration()
                .getProviderDetails().getUserInfoEndpoint().getUserNameAttributeName();
        if (userNameAttributeName == null || userNameAttributeName.isBlank()) {
            userNameAttributeName = "email";
        }

        Set<GrantedAuthority> authorities = new HashSet<>(oauth2User.getAuthorities());
        if (adminEmails.contains(email.trim().toLowerCase())) {
            authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        }

        return new CustomOAuth2User(authorities, oauth2User.getAttributes(), userNameAttributeName, user);
    }
}
