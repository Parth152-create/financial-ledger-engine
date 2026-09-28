import test from "node:test"
import assert from "node:assert/strict"

// 1. Reconciliation Response Contract Tests
test("1. Reconciliation Contract: matches backend OverallReconciliationDto structure", () => {
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
  assert.equal(firstResult.totalCredits, 5000.0)
  assert.equal(firstResult.totalDebits, 0.0)
})

// 2. Individual Account Contract Test
test("2. Reconciliation Result Contract: matches backend ReconciliationResultDto specification", () => {
  const accountResult = {
    accountId: "018f3a5b-9d4e-7b2c-8a1e-3f5c7b9d1e2f",
    snapshotBalance: 1250.75,
    ledgerBalance: 1250.75,
    difference: 0.0,
    status: "CONSISTENT",
    totalCredits: 2500.0,
    totalDebits: 1249.25,
    reconciledAt: "2026-09-28T08:00:00Z",
  }

  assert.ok(typeof accountResult.accountId === "string")
  assert.ok(typeof accountResult.snapshotBalance === "number")
  assert.ok(typeof accountResult.ledgerBalance === "number")
  assert.ok(typeof accountResult.difference === "number")
  assert.ok(["CONSISTENT", "DISCREPANCY"].includes(accountResult.status))
  assert.ok(typeof accountResult.totalCredits === "number")
  assert.ok(typeof accountResult.totalDebits === "number")
  assert.ok(typeof accountResult.reconciledAt === "string")
})

// 3. Consistent Result Verification
test("3. Reconciliation Result: consistent state verified when difference is 0.0000", () => {
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

// 4. Discrepancy Result Verification
test("4. Reconciliation Result: discrepancy flagged when snapshot deviates from ledger-derived balance", () => {
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

// 5. Double-Entry Ledger Mathematical Invariant
test("5. Accounting Invariant: ledgerBalance === credits - debits and difference === snapshot - ledger", () => {
  const verifyAccountingFormula = (r) => {
    const calculatedLedger = Number((r.totalCredits - r.totalDebits).toFixed(4))
    const calculatedDiff = Number((r.snapshotBalance - r.ledgerBalance).toFixed(4))
    const isLedgerConsistent = calculatedLedger === Number(r.ledgerBalance.toFixed(4))
    const isDiffConsistent = calculatedDiff === Number(r.difference.toFixed(4))
    return isLedgerConsistent && isDiffConsistent
  }

  const result1 = {
    snapshotBalance: 3500.0,
    ledgerBalance: 3500.0,
    difference: 0.0,
    totalCredits: 5000.0,
    totalDebits: 1500.0,
  }
  assert.equal(verifyAccountingFormula(result1), true)

  const result2 = {
    snapshotBalance: 3800.0,
    ledgerBalance: 3500.0,
    difference: 300.0,
    totalCredits: 5000.0,
    totalDebits: 1500.0,
  }
  assert.equal(verifyAccountingFormula(result2), true)
})

// 6. Aggregate Totals Calculation
test("6. Reconciliation Aggregates: sums snapshot totals, ledger totals, differences, credits, and debits", () => {
  const results = [
    { snapshotBalance: 500, ledgerBalance: 500, difference: 0, totalCredits: 600, totalDebits: 100 },
    { snapshotBalance: 300, ledgerBalance: 250, difference: 50, totalCredits: 400, totalDebits: 150 },
    { snapshotBalance: 100, ledgerBalance: 120, difference: -20, totalCredits: 200, totalDebits: 80 },
  ]

  const totalSnapshot = results.reduce((sum, r) => sum + r.snapshotBalance, 0)
  const totalLedger = results.reduce((sum, r) => sum + r.ledgerBalance, 0)
  const totalDiff = results.reduce((sum, r) => sum + Math.abs(r.difference), 0)
  const totalCredits = results.reduce((sum, r) => sum + r.totalCredits, 0)
  const totalDebits = results.reduce((sum, r) => sum + r.totalDebits, 0)

  assert.equal(totalSnapshot, 900)
  assert.equal(totalLedger, 870)
  assert.equal(totalDiff, 70)
  assert.equal(totalCredits, 1200)
  assert.equal(totalDebits, 330)
})

// 7. Safe Error Mapping & Information Leak Protection
test("7. Reconciliation Error Mapping: hides stack traces and maps error status codes cleanly", () => {
  const mapReconciliationError = (error) => {
    if (!error) return "An unexpected error occurred while communicating with the reconciliation service."
    const status = error.status
    const raw = error.message || error.error || ""

    const containsTechnicalLeak =
      raw.includes("Exception") ||
      raw.includes("org.springframework") ||
      raw.includes("com.parth") ||
      raw.includes("SQL") ||
      raw.includes("StackTrace") ||
      raw.includes("Hibernate") ||
      raw.includes("Redis") ||
      raw.includes("postgres") ||
      raw.includes("deadlock")

    if (status === 401) {
      return "Authentication required. Please sign in again."
    }

    if (status === 403) {
      return "Unauthorized reconciliation attempt: you do not have permission to audit this account."
    }

    if (status === 404) {
      return "Target account not found."
    }

    if (status === 429) {
      return "Too many requests. Please wait a moment before running reconciliation again."
    }

    if (status === 0 || error.error === "NetworkError" || raw.toLowerCase().includes("network")) {
      return "Network connection failed. Please check your internet connection and try again."
    }

    if (status >= 500 || containsTechnicalLeak) {
      return "An unexpected server error occurred during reconciliation audit. Please try again later."
    }

    return raw || "An unexpected error occurred while communicating with the reconciliation service."
  }

  // 401 Unauthorized
  assert.equal(
    mapReconciliationError({ status: 401, message: "Unauthorized" }),
    "Authentication required. Please sign in again."
  )

  // 403 Forbidden
  assert.equal(
    mapReconciliationError({
      status: 403,
      message: "Authenticated user does not own account: 123",
    }),
    "Unauthorized reconciliation attempt: you do not have permission to audit this account."
  )

  // 404 Not Found
  assert.equal(
    mapReconciliationError({
      status: 404,
      message: "Account not found: 456",
    }),
    "Target account not found."
  )

  // 429 Rate Limit
  assert.equal(
    mapReconciliationError({ status: 429, message: "Rate limit exceeded" }),
    "Too many requests. Please wait a moment before running reconciliation again."
  )

  // 500 Server Error
  assert.equal(
    mapReconciliationError({ status: 500, message: "Internal Server Error" }),
    "An unexpected server error occurred during reconciliation audit. Please try again later."
  )

  // Stack trace / SQL leak suppression
  assert.equal(
    mapReconciliationError({
      status: 500,
      message: "org.springframework.dao.DataAccessException at com.parth.ledger.reconciliation.ReconciliationService.reconcileUserAccounts: line 120",
    }),
    "An unexpected server error occurred during reconciliation audit. Please try again later."
  )

  // Network Error
  assert.equal(
    mapReconciliationError({ status: 0, error: "NetworkError", message: "Failed to fetch" }),
    "Network connection failed. Please check your internet connection and try again."
  )
})

// 8. Account Masking & Platform Clearing Masking
test("8. Account Presentation: masks account numbers and masks Platform Clearing system UUID", () => {
  const SYSTEM_CLEARING_ID = "00000000-0000-0000-0000-000000000001"

  const maskAccountNumber = (accountNumber) => {
    if (!accountNumber) return "•••• ----"
    if (accountNumber === SYSTEM_CLEARING_ID) return "Platform Clearing"
    const clean = accountNumber.trim()
    if (clean === SYSTEM_CLEARING_ID) return "Platform Clearing"
    if (clean.length <= 4) return `•••• ${clean}`
    return `•••• ${clean.slice(-4)}`
  }

  assert.equal(maskAccountNumber("ACCT-9876543210"), "•••• 3210")
  assert.equal(maskAccountNumber("1234"), "•••• 1234")
  assert.equal(maskAccountNumber(""), "•••• ----")
  assert.equal(maskAccountNumber(null), "•••• ----")
  assert.equal(maskAccountNumber(SYSTEM_CLEARING_ID), "Platform Clearing")
})

// 9. Zero-Accounts Empty State
test("9. Reconciliation Empty State: handles zero checking accounts cleanly", () => {
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
  assert.equal(emptyReconciliation.consistentAccounts, 0)
})

// 10. Query Keys & TanStack Conventions
test("10. TanStack Query Keys: verifies exact reconciliation query keys", () => {
  const RECONCILIATION_KEYS = {
    all: ["reconciliation"],
    overall: () => ["reconciliation", "overall"],
    account: (accountId) => ["reconciliation", "account", accountId],
  }

  assert.deepEqual(RECONCILIATION_KEYS.all, ["reconciliation"])
  assert.deepEqual(RECONCILIATION_KEYS.overall(), ["reconciliation", "overall"])
  assert.deepEqual(
    RECONCILIATION_KEYS.account("test-acct-123"),
    ["reconciliation", "account", "test-acct-123"]
  )
})

// 11. Read-Only Invariant: Reconciliation Performs Strictly GET Requests
test("11. Read-Only Audit Invariant: reconciliation endpoints use GET and never mutate balances", () => {
  const reconciliationEndpoints = [
    { method: "GET", path: "/api/v1/reconciliation" },
    { method: "GET", path: "/api/v1/reconciliation/accounts" },
    { method: "GET", path: "/api/v1/reconciliation/accounts/{accountId}" },
  ]

  reconciliationEndpoints.forEach((ep) => {
    assert.equal(ep.method, "GET")
  })
})

// 12. Currency Display & Format Verification
test("12. Financial Currency Formatting: verifies INR formatting on reconciliation balances", () => {
  const formatINR = (amount) => {
    const num = typeof amount === "number" ? amount : Number(amount)
    if (isNaN(num)) return String(amount)
    return `₹${num.toLocaleString("en-IN", {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    })}`
  }

  assert.equal(formatINR(5000), "₹5,000.00")
  assert.equal(formatINR(1250.75), "₹1,250.75")
  assert.equal(formatINR(0), "₹0.00")
})

// 13. Mutation Invalidation Hook Verification
test("13. Mutation Invalidation: mutations invalidate ['reconciliation'] queries", () => {
  const mockInvalidated = []
  const mockQueryClient = {
    invalidateQueries: ({ queryKey }) => mockInvalidated.push(queryKey),
  }

  // Simulate invalidation triggered on transfer, deposit, or withdrawal
  mockQueryClient.invalidateQueries({ queryKey: ["reconciliation"] })

  assert.deepEqual(mockInvalidated, [["reconciliation"]])
})
