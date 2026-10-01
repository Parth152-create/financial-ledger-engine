package com.parth.ledger.policy.dto;

import java.math.BigDecimal;

public record UpdatePolicyRequestDto(
        BigDecimal amountLimit,
        Integer countLimit,
        Boolean enabled
) {
}
