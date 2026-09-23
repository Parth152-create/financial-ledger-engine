package com.parth.ledger.security;

import com.parth.ledger.user.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

import java.util.Collection;
import java.util.Map;

/**
 * Custom OAuth2 principal wrapping standard OAuth2 attributes and the persistent application User.
 */
public class CustomOAuth2User extends DefaultOAuth2User {

    private final User user;

    public CustomOAuth2User(Collection<? extends GrantedAuthority> authorities,
                            Map<String, Object> attributes,
                            String nameAttributeKey,
                            User user) {
        super(authorities, attributes, nameAttributeKey);
        this.user = user;
    }

    public User getUser() {
        return user;
    }
}
