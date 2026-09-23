package com.parth.ledger.security.controller;

import com.parth.ledger.security.AuthenticatedUserService;
import com.parth.ledger.user.User;
import com.parth.ledger.user.dto.UserResponseDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller handling authentication and identity verification endpoints.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthenticatedUserService authenticatedUserService;

    public AuthController(AuthenticatedUserService authenticatedUserService) {
        this.authenticatedUserService = authenticatedUserService;
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
}
