package com.parth.ledger.reconciliation.dto;

import java.time.Instant;
import java.util.List;

/**
 * Summary result of multi-account reconciliation.
 *
 * @param totalAccountsChecked Total count of accounts evaluated.
 * @param consistentAccounts Number of accounts whose snapshot matches the ledger-derived balance.
 * @param discrepancyCount Number of accounts with an unbalanced discrepancy.
 * @param reconciliationResults Detailed reconciliation result for each evaluated account.
 * @param reconciledAt Timestamp when overall reconciliation was executed.
 */
public record OverallReconciliationDto(
        int totalAccountsChecked,
        int consistentAccounts,
        int discrepancyCount,
        List<ReconciliationResultDto> reconciliationResults,
        Instant reconciledAt
) {
    public List<ReconciliationResultDto> results() {
        return reconciliationResults;
    }
}
