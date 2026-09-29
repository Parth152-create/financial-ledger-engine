package com.parth.ledger.transaction.dto;

import jakarta.validation.constraints.Size;

/**
 * Request payload for reversing an existing completed financial transaction.
 *
 * All financial parameters (amount, currency, source account, destination account)
 * are derived directly from the original transaction and cannot be specified by the caller.
 */
public record ReversalRequestDto(
        @Size(max = 255, message = "Reversal reason must not exceed 255 characters")
        String reason
) {
    public ReversalRequestDto() {
        this(null);
    }
}
