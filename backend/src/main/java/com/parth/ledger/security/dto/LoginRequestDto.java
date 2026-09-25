package com.parth.ledger.security.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginRequestDto(
        @NotBlank(message = "Email is required")
        String email,

        @NotBlank(message = "Password is required")
        String password
) {
    public String normalizedEmail() {
        return email != null ? email.trim().toLowerCase() : "";
    }
}
