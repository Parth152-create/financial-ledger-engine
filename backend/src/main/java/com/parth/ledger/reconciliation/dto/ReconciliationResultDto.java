package com.parth.ledger.reconciliation.dto;

import com.parth.ledger.reconciliation.ReconciliationStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Result of reconciling an individual account snapshot balance against its immutable ledger entries.
 *
 * @param accountId Unique identifier of the reconciled account.
 * @param snapshotBalance Current balance recorded in accounts table snapshot.
 * @param ledgerBalance Calculated balance derived strictly from immutable ledger entries (credits - debits).
 * @param difference Discrepancy amount (snapshotBalance - ledgerBalance). Zero if consistent.
 * @param status CONSISTENT if difference is zero, DISCREPANCY otherwise.
 * @param totalCredits Cumulative credit ledger entries posted to the account.
 * @param totalDebits Cumulative debit ledger entries posted to the account.
 * @param reconciledAt Timestamp when reconciliation was performed.
 */
public record ReconciliationResultDto(
        UUID accountId,
        BigDecimal snapshotBalance,
        BigDecimal ledgerBalance,
        BigDecimal difference,
        ReconciliationStatus status,
        BigDecimal totalCredits,
        BigDecimal totalDebits,
        Instant reconciledAt
) {
}
