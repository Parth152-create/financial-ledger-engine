package com.parth.ledger.policy.controller;

import com.parth.ledger.policy.PolicyScope;
import com.parth.ledger.policy.PolicyService;
import com.parth.ledger.policy.dto.CreatePolicyRequestDto;
import com.parth.ledger.policy.dto.FinancialPolicyResponseDto;
import com.parth.ledger.policy.dto.UpdatePolicyRequestDto;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Administrative REST controller for financial policy management.
 * Strictly restricted to users possessing administrative authority (ROLE_ADMIN).
 */
@RestController
@RequestMapping("/api/v1/admin/policies")
public class AdminPolicyController {

    private final PolicyService policyService;

    public AdminPolicyController(PolicyService policyService) {
        this.policyService = policyService;
    }

    @PostMapping
    public ResponseEntity<FinancialPolicyResponseDto> createPolicy(@Valid @RequestBody CreatePolicyRequestDto request) {
        FinancialPolicyResponseDto created = policyService.createPolicy(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping
    public ResponseEntity<List<FinancialPolicyResponseDto>> listPolicies(
            @RequestParam(value = "accountId", required = false) UUID accountId,
            @RequestParam(value = "policyScope", required = false) PolicyScope policyScope
    ) {
        List<FinancialPolicyResponseDto> policies = policyService.listPolicies(accountId, policyScope);
        return ResponseEntity.ok(policies);
    }

    @GetMapping("/{id}")
    public ResponseEntity<FinancialPolicyResponseDto> getPolicy(@PathVariable("id") UUID id) {
        FinancialPolicyResponseDto policy = policyService.getPolicy(id);
        return ResponseEntity.ok(policy);
    }

    @PutMapping("/{id}")
    public ResponseEntity<FinancialPolicyResponseDto> updatePolicy(
            @PathVariable("id") UUID id,
            @RequestBody UpdatePolicyRequestDto request
    ) {
        FinancialPolicyResponseDto updated = policyService.updatePolicy(id, request);
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletePolicy(@PathVariable("id") UUID id) {
        policyService.deletePolicy(id);
        return ResponseEntity.noContent().build();
    }
}
