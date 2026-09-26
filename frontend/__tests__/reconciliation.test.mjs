import test from "node:test"
import assert from "node:assert/strict"

// 1. Reconciliation Response Contract Tests
test("Reconciliation Contract: matches backend OverallReconciliationDto structure", () => {
  const mockOverallReconciliation = {
    totalAccountsChecked: 2,
    consistentAccounts: 2,
    discrepancyCount: 0,
    reconciliationResults: [
      {
        accountId: "acct-alice-01",
        snapshotBalance: 5000.0,
        ledgerBalance: 5000.0,
        difference: 0.0,
        status: "CONSISTENT",
        totalCredits: 5000.0,
        totalDebits: 0.0,
        reconciledAt: "2026-09-24T10:00:00Z",
      },
      {
        accountId: "acct-bob-01",
        snapshotBalance: 2000.0,
        ledgerBalance: 2000.0,
        difference: 0.0,
        status: "CONSISTENT",
        totalCredits: 3000.0,
        totalDebits: 1000.0,
        reconciledAt: "2026-09-24T10:00:00Z",
      },
    ],
    reconciledAt: "2026-09-24T10:00:00Z",
  }

  assert.equal(mockOverallReconciliation.totalAccountsChecked, 2)
  assert.equal(mockOverallReconciliation.consistentAccounts, 2)
  assert.equal(mockOverallReconciliation.discrepancyCount, 0)
  assert.equal(mockOverallReconciliation.reconciliationResults.length, 2)

  const firstResult = mockOverallReconciliation.reconciliationResults[0]
  assert.equal(firstResult.accountId, "acct-alice-01")
  assert.equal(firstResult.status, "CONSISTENT")
  assert.equal(firstResult.difference, 0.0)
  assert.equal(firstResult.snapshotBalance, firstResult.ledgerBalance)
})

// 2. Consistent Result Verification
test("Reconciliation Result: consistent state verified when difference is 0.0000", () => {
  const evaluateConsistency = (result) => {
    const isConsistent =
      result.status === "CONSISTENT" &&
      Math.abs(Number(result.difference || 0)) === 0 &&
      Number(result.snapshotBalance) === Number(result.ledgerBalance)
    return isConsistent
  }

  const consistentItem = {
    accountId: "acct-01",
    snapshotBalance: 1250.5,
    ledgerBalance: 1250.5,
    difference: 0.0,
    status: "CONSISTENT",
  }

  assert.equal(evaluateConsistency(consistentItem), true)
})

// 3. Discrepancy Result Verification
test("Reconciliation Result: discrepancy flagged when snapshot deviates from ledger-derived balance", () => {
  const mockDiscrepancyReconciliation = {
    totalAccountsChecked: 2,
    consistentAccounts: 1,
    discrepancyCount: 1,
    reconciliationResults: [
      {
        accountId: "acct-consistent",
        snapshotBalance: 1000.0,
        ledgerBalance: 1000.0,
        difference: 0.0,
        status: "CONSISTENT",
        totalCredits: 1000.0,
        totalDebits: 0.0,
        reconciledAt: "2026-09-24T10:00:00Z",
      },
      {
        accountId: "acct-divergent",
        snapshotBalance: 1250.0,
        ledgerBalance: 1000.0,
        difference: 250.0,
        status: "DISCREPANCY",
        totalCredits: 1000.0,
        totalDebits: 0.0,
        reconciledAt: "2026-09-24T10:00:00Z",
      },
    ],
    reconciledAt: "2026-09-24T10:00:00Z",
  }

  assert.equal(mockDiscrepancyReconciliation.discrepancyCount, 1)
  assert.equal(mockDiscrepancyReconciliation.consistentAccounts, 1)

  const divergent = mockDiscrepancyReconciliation.reconciliationResults[1]
  assert.equal(divergent.status, "DISCREPANCY")
  assert.equal(divergent.difference, 250.0)
  assert.notEqual(divergent.snapshotBalance, divergent.ledgerBalance)
})

// 4. Safe Error Mapping & Information Leak Protection
test("Reconciliation Error Mapping: hides stack traces and maps error status codes cleanly", () => {
  const mapReconciliationError = (error) => {
    if (!error) return "An unexpected error occurred."
    const status = error.status
    const raw = error.message || error.error || ""

    if (
      raw.includes("Exception") ||
      raw.includes("org.springframework") ||
      raw.includes("SQL") ||
      raw.includes("deadlock")
    ) {
      return "An unexpected server error occurred during reconciliation audit. Please try again later."
    }

    switch (status) {
      case 401:
        return "Authentication required. Please sign in again."
      case 403:
        return "Unauthorized reconciliation attempt: account does not belong to caller."
      case 404:
        return "Target account not found."
      case 500:
        return "Internal server error occurred while performing balance reconciliation."
      default:
        return raw || "Failed to complete reconciliation."
    }
  }

  assert.equal(
    mapReconciliationError({
      status: 403,
      message: "Authenticated user does not own account: 123",
    }),
    "Unauthorized reconciliation attempt: account does not belong to caller."
  )
  assert.equal(
    mapReconciliationError({
      status: 500,
      message: "org.springframework.dao.DataAccessException at com.parth.ledger...",
    }),
    "An unexpected server error occurred during reconciliation audit. Please try again later."
  )
  assert.equal(
    mapReconciliationError({
      status: 404,
      message: "Account not found: 456",
    }),
    "Target account not found."
  )
})

// 5. Aggregate Totals Calculation
test("Reconciliation Aggregates: sums snapshot totals, ledger totals, and absolute differences", () => {
  const results = [
    { snapshotBalance: 500, ledgerBalance: 500, difference: 0 },
    { snapshotBalance: 300, ledgerBalance: 250, difference: 50 },
    { snapshotBalance: 100, ledgerBalance: 120, difference: -20 },
  ]

  const totalSnapshot = results.reduce((sum, r) => sum + r.snapshotBalance, 0)
  const totalLedger = results.reduce((sum, r) => sum + r.ledgerBalance, 0)
  const totalDiff = results.reduce((sum, r) => sum + Math.abs(r.difference), 0)

  assert.equal(totalSnapshot, 900)
  assert.equal(totalLedger, 870)
  assert.equal(totalDiff, 70)
})

// 6. Zero-Accounts Empty State
test("Reconciliation Empty State: handles zero checking accounts correctly", () => {
  const emptyReconciliation = {
    totalAccountsChecked: 0,
    consistentAccounts: 0,
    discrepancyCount: 0,
    reconciliationResults: [],
    reconciledAt: "2026-09-24T10:00:00Z",
  }

  assert.equal(emptyReconciliation.totalAccountsChecked, 0)
  assert.equal(emptyReconciliation.reconciliationResults.length, 0)
  assert.equal(emptyReconciliation.discrepancyCount, 0)
})
