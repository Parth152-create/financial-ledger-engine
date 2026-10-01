package com.parth.ledger.policy.controller;

import com.parth.ledger.policy.PolicyService;
import com.parth.ledger.policy.dto.AccountLimitSummaryDto;
import com.parth.ledger.transaction.TransactionType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST controller for authenticated users to view applicable financial limits and current daily usage
 * on their owned accounts.
 */
@RestController
@RequestMapping("/api/v1/accounts/{accountId}/limits")
public class AccountLimitsController {

    private final PolicyService policyService;

    public AccountLimitsController(PolicyService policyService) {
        this.policyService = policyService;
    }

    @GetMapping
    public ResponseEntity<?> getAccountLimits(
            @PathVariable("accountId") UUID accountId,
            @RequestParam(value = "transactionType", required = false) TransactionType transactionType
    ) {
        if (transactionType != null) {
            AccountLimitSummaryDto summary = policyService.getAccountLimitSummary(accountId, transactionType);
            return ResponseEntity.ok(summary);
        } else {
            return ResponseEntity.ok(policyService.getAllAccountLimits(accountId));
        }
    }
}
