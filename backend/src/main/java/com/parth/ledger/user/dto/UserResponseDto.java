package com.parth.ledger.user.dto;

import com.parth.ledger.user.User;

import java.util.UUID;

/**
 * Safe identity representation of an authenticated application user.
 * Exposes only non-sensitive identity attributes (id, email, name).
 */
public record UserResponseDto(
        UUID id,
        String email,
        String name
) {
    public static UserResponseDto from(User user) {
        return new UserResponseDto(
                user.getId(),
                user.getEmail(),
                user.getName()
        );
    }
}
