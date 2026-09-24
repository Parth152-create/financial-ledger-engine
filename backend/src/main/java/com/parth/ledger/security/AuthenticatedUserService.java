package com.parth.ledger.security;

import com.parth.ledger.user.User;
import com.parth.ledger.user.UserService;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

/**
 * Service providing clean access to the authenticated application user from the SecurityContext.
 * Avoids scattering direct SecurityContextHolder queries and casting across domain services.
 */
@Service
public class AuthenticatedUserService {

    private final UserService userService;

    public AuthenticatedUserService(UserService userService) {
        this.userService = userService;
    }

    /**
     * Resolves the current authenticated application User entity from the SecurityContext.
     *
     * @return The authenticated application User.
     * @throws AuthenticationCredentialsNotFoundException if no user is authenticated.
     * @throws UserNotFoundException if the authenticated identity does not correspond to an application user.
     */
    public User getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || authentication instanceof AnonymousAuthenticationToken) {
            throw new AuthenticationCredentialsNotFoundException("No authenticated user present in SecurityContext");
        }

        Object principal = authentication.getPrincipal();

        // 1. Check custom principal types
        if (principal instanceof CustomOAuth2User customOAuth2User) {
            return customOAuth2User.getUser();
        }
        if (principal instanceof CustomOidcUser customOidcUser) {
            return customOidcUser.getUser();
        }

        // 2. Check standard OAuth2User
        if (principal instanceof OAuth2User oauth2User) {
            String email = oauth2User.getAttribute("email");
            if (email != null && !email.isBlank()) {
                return userService.findByEmail(email)
                        .orElseThrow(() -> new UserNotFoundException("User not found for authenticated OAuth2 email: " + email));
            }
        }

        // 3. Check standard UserDetails
        if (principal instanceof UserDetails userDetails) {
            String username = userDetails.getUsername();
            return userService.findByEmail(username)
                    .orElseThrow(() -> new UserNotFoundException("User not found for authenticated username: " + username));
        }

        // 4. Fallback to authentication name
        String name = authentication.getName();
        if (name != null && !name.isBlank()) {
            return userService.findByEmail(name)
                    .orElseThrow(() -> new UserNotFoundException("User not found for authenticated principal name: " + name));
        }

        throw new AuthenticationCredentialsNotFoundException("Unable to resolve authenticated user from SecurityContext");
    }

    /**
     * Checks if the currently authenticated user possesses administrative authority (ROLE_ADMIN).
     *
     * @return true if the user has ROLE_ADMIN, false otherwise.
     */
    public boolean isAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || authentication instanceof AnonymousAuthenticationToken) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()) || "ADMIN".equals(a.getAuthority()));
    }
}
