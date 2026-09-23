package com.parth.ledger.reconciliation.controller;

import com.parth.ledger.reconciliation.dto.OverallReconciliationDto;
import com.parth.ledger.reconciliation.dto.ReconciliationResultDto;
import com.parth.ledger.reconciliation.service.ReconciliationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Controller exposing financial reconciliation endpoints.
 */
@RestController
@RequestMapping("/api/v1/reconciliation")
public class ReconciliationController {

    private final ReconciliationService reconciliationService;

    public ReconciliationController(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    /**
     * Reconciles a single account owned by the authenticated caller.
     *
     * @param accountId UUID of the account.
     * @return 200 OK with ReconciliationResultDto.
     */
    @GetMapping("/accounts/{accountId}")
    public ResponseEntity<ReconciliationResultDto> reconcileAccount(@PathVariable UUID accountId) {
        ReconciliationResultDto result = reconciliationService.reconcileAccount(accountId);
        return ResponseEntity.ok(result);
    }

    /**
     * Reconciles all accounts owned by the authenticated caller.
     *
     * @return 200 OK with OverallReconciliationDto.
     */
    @GetMapping({"", "/accounts"})
    public ResponseEntity<OverallReconciliationDto> reconcileUserAccounts() {
        OverallReconciliationDto summary = reconciliationService.reconcileUserAccounts();
        return ResponseEntity.ok(summary);
    }
}
