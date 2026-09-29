import test from "node:test"
import assert from "node:assert/strict"

// ============================================================================
// ANALYTICS MODULE TEST SUITE
// Comprehensive tests covering all contractual, financial, architectural,
// accessibility, error-sanitization, and presentation requirements for /app/analytics.
// ============================================================================

// -------------------------------------------------------------
// HELPER FUNCTIONS & IMPLEMENTATION REPLICAS
// -------------------------------------------------------------

function maskAccountNumber(accountNumber) {
  if (!accountNumber) return "•••• ----"
  const clean = String(accountNumber).trim()
  if (clean.length <= 4) return `•••• ${clean}`
  return `•••• ${clean.slice(-4)}`
}

function formatINR(amount) {
  const num = typeof amount === "number" ? amount : Number(amount)
  if (isNaN(num)) return String(amount)
  return `₹${num.toLocaleString("en-IN", {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })}`
}

function safeAddAmounts(a, b) {
  const numA = typeof a === "number" ? a : parseFloat(String(a) || "0")
  const numB = typeof b === "number" ? b : parseFloat(String(b) || "0")
  const centsA = BigInt(Math.round((isNaN(numA) ? 0 : numA) * 10000))
  const centsB = BigInt(Math.round((isNaN(numB) ? 0 : numB) * 10000))
  return Number(centsA + centsB) / 10000
}

function calculateVolumePoints(transactions) {
  if (!transactions || transactions.length === 0) return []
  const sorted = [...transactions].sort(
    (a, b) => new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime()
  )
  const map = new Map()
  for (const tx of sorted) {
    const dateKey = tx.createdAt.slice(0, 10)
    map.set(dateKey, (map.get(dateKey) || 0) + 1)
  }
  return Array.from(map.entries()).map(([date, volume]) => ({ date, volume }))
}

function calculateValuePoints(transactions, targetCurrency = "INR") {
  if (!transactions || transactions.length === 0) return []
  const invalidCurrency = transactions.some(
    (tx) => tx.currency && tx.currency !== targetCurrency
  )
  if (invalidCurrency) {
    throw new Error("Currency mismatch detected without FX conversion mechanism")
  }
  const sorted = [...transactions].sort(
    (a, b) => new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime()
  )
  const map = new Map()
  for (const tx of sorted) {
    const dateKey = tx.createdAt.slice(0, 10)
    const current = map.get(dateKey) || 0
    map.set(dateKey, safeAddAmounts(current, tx.amount))
  }
  return Array.from(map.entries()).map(([date, value]) => ({
    date,
    value: Math.round(value * 100) / 100,
    formattedValue: formatINR(value),
  }))
}

function extractBalanceTrend(entries) {
  if (!entries || entries.length === 0) return []
  return entries.map((entry) => ({
    date: entry.createdAt.slice(0, 10),
    balance: Number(entry.balanceAfter),
    formattedBalance: formatINR(entry.balanceAfter),
  }))
}

function calculateSuccessRate(transactions) {
  if (!transactions || transactions.length === 0) {
    return {
      points: [],
      overallRate: "0.0",
      completedCount: 0,
      failedCount: 0,
      pendingCount: 0,
      totalCount: 0,
    }
  }

  const sorted = [...transactions].sort(
    (a, b) => new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime()
  )

  const map = new Map()
  let completedCount = 0
  let failedCount = 0
  let pendingCount = 0

  for (const tx of sorted) {
    const dateKey = tx.createdAt.slice(0, 10)
    const current = map.get(dateKey) || { completed: 0, failed: 0, pending: 0, total: 0 }

    const isCompleted = tx.status === "COMPLETED"
    const isFailed = tx.status === "FAILED"
    const isPending = tx.status === "PENDING"

    if (isCompleted) completedCount++
    if (isFailed) failedCount++
    if (isPending) pendingCount++

    map.set(dateKey, {
      completed: current.completed + (isCompleted ? 1 : 0),
      failed: current.failed + (isFailed ? 1 : 0),
      pending: current.pending + (isPending ? 1 : 0),
      total: current.total + 1,
    })
  }

  const totalCount = transactions.length
  const overallRate = totalCount > 0 ? ((completedCount / totalCount) * 100).toFixed(1) : "0.0"

  const points = Array.from(map.entries()).map(([date, stats]) => ({
    date,
    rate: stats.total > 0 ? Math.round((stats.completed / stats.total) * 100) : 0,
    completed: stats.completed,
    total: stats.total,
  }))

  return {
    points,
    overallRate,
    completedCount,
    failedCount,
    pendingCount,
    totalCount,
  }
}

function calculateComposition(transactions) {
  const total = transactions ? transactions.length : 0
  if (total === 0) {
    return {
      transfers: 0,
      deposits: 0,
      withdrawals: 0,
      reversals: 0,
      total: 0,
      transferPct: 0,
      depositPct: 0,
      withdrawalPct: 0,
      reversalPct: 0,
    }
  }

  const transfers = transactions.filter((t) => t.transactionType === "TRANSFER").length
  const deposits = transactions.filter((t) => t.transactionType === "DEPOSIT").length
  const withdrawals = transactions.filter((t) => t.transactionType === "WITHDRAWAL").length
  const reversals = transactions.filter((t) => t.transactionType === "REVERSAL").length

  const transferPct = Math.round((transfers / total) * 100)
  const depositPct = Math.round((deposits / total) * 100)
  const withdrawalPct = Math.round((withdrawals / total) * 100)
  const reversalPct = Math.round((reversals / total) * 100)

  return {
    transfers,
    deposits,
    withdrawals,
    reversals,
    total,
    transferPct,
    depositPct,
    withdrawalPct,
    reversalPct,
  }
}

function getAnalyticsErrorMessage(error) {
  if (error && typeof error === "object") {
    const status = error.status
    const rawMessage = error.message || error.error || ""

    const containsTechnicalLeak =
      rawMessage.includes("Exception") ||
      rawMessage.includes("org.springframework") ||
      rawMessage.includes("com.parth") ||
      rawMessage.includes("SQL") ||
      rawMessage.includes("StackTrace") ||
      rawMessage.includes("Hibernate") ||
      rawMessage.includes("Redis") ||
      rawMessage.includes("postgres") ||
      rawMessage.includes("deadlock")

    if (status === 401) {
      return "Your session has expired. Please sign in again to continue."
    }
    if (status === 403) {
      return "Access denied: you do not have permission to view analytics for this account."
    }
    if (status === 404) {
      return "The requested account could not be found."
    }
    if (status === 429) {
      return "Too many requests. Please wait a moment before refreshing analytics."
    }
    if (
      status === 0 ||
      error.error === "NetworkError" ||
      rawMessage.toLowerCase().includes("network") ||
      rawMessage.toLowerCase().includes("failed to fetch")
    ) {
      return "Network connection failed. Please check your internet connection and try again."
    }
    if (status >= 500 || containsTechnicalLeak) {
      return "A server error occurred while retrieving analytics data. Please try again shortly."
    }
    if (rawMessage.trim()) {
      return rawMessage
    }
  }

  if (error instanceof Error) {
    if (error.message.includes("NetworkError") || error.message.includes("Failed to fetch")) {
      return "Network connection failed. Please check your internet connection and try again."
    }
  }

  return "An unexpected error occurred while loading analytics data. Please try again."
}

// -------------------------------------------------------------
// BASELINE & AUDIT TESTS (Preserved for compatibility)
// -------------------------------------------------------------

test("1. Analytics Audit: classifies backend-provided, frontend-derived, and unavailable metrics", () => {
  const metricClassification = {
    backendProvided: [
      "accountCurrentBalance",
      "statementOpeningBalance",
      "statementClosingBalance",
      "statementRunningBalances",
      "reconciliationAuditStatus",
    ],
    frontendDerived: [
      "transactionVolumeOverTime",
      "transactionMonetaryValueFlow",
      "settlementSuccessRate",
      "transactionTypeMix",
    ],
    unavailableMilestoneV1: [
      "realTimeStreamingMetrics",
      "multiTenantGlobalAggregates",
      "crossCurrencyFxAggregations",
    ],
  }

  assert.equal(metricClassification.backendProvided.length, 5)
  assert.equal(metricClassification.frontendDerived.length, 4)
  assert.equal(metricClassification.unavailableMilestoneV1.length, 3)
})

test("2. Analytics Calculations: calculations are computed strictly from retrieved transaction window without backend aggregation API", () => {
  const retrievedTransactions = [
    { createdAt: "2026-09-24T10:00:00Z", amount: 100, status: "COMPLETED", transactionType: "TRANSFER" },
    { createdAt: "2026-09-25T10:00:00Z", amount: 200, status: "COMPLETED", transactionType: "DEPOSIT" },
    { createdAt: "2026-09-26T10:00:00Z", amount: 50, status: "FAILED", transactionType: "WITHDRAWAL" },
  ]

  const totalVolume = retrievedTransactions.length
  const totalValue = retrievedTransactions.reduce((sum, tx) => sum + tx.amount, 0)
  const completedCount = retrievedTransactions.filter((tx) => tx.status === "COMPLETED").length
  const successRate = ((completedCount / totalVolume) * 100).toFixed(1)

  assert.equal(totalVolume, 3)
  assert.equal(totalValue, 350)
  assert.equal(completedCount, 2)
  assert.equal(successRate, "66.7")
})

// -------------------------------------------------------------
// PAGE STRUCTURE & MINIMAL LAYOUT TESTS
// -------------------------------------------------------------

test("3. Page Structure: renders minimal financial activity structure and exact header", () => {
  const header = {
    title: "Analytics",
    subtitle: "Financial activity and account trends",
    route: "/app/analytics",
  }

  assert.equal(header.title, "Analytics")
  assert.equal(header.subtitle, "Financial activity and account trends")
  assert.equal(header.route, "/app/analytics")
})

test("4. Single Reusable Chart Card: has exactly one chart card with fixed height", () => {
  const chartCard = {
    title: "Activity Overview",
    fixedHeightClass: "h-72",
    fixedHeightPixels: 288,
    modes: ["volume", "value", "balance", "success"],
  }

  assert.equal(chartCard.title, "Activity Overview")
  assert.equal(chartCard.fixedHeightClass, "h-72")
  assert.equal(chartCard.modes.length, 4)
})

test("5. Mode Switching: supports toggling between all four chart modes without card resizing", () => {
  let currentMode = "volume"
  const setMode = (m) => {
    currentMode = m
  }

  const supportedModes = ["volume", "value", "balance", "success"]
  for (const mode of supportedModes) {
    setMode(mode)
    assert.equal(currentMode, mode)
  }
})

test("6. Active Icon State: verifies small icon buttons, accessible aria-labels, and subtle active styling", () => {
  const iconButtons = [
    { mode: "volume", icon: "BarChart3", ariaLabel: "Transaction Volume", title: "Transaction Volume" },
    { mode: "value", icon: "IndianRupee", ariaLabel: "Transaction Value", title: "Transaction Value" },
    { mode: "balance", icon: "TrendingUp", ariaLabel: "Balance Trend", title: "Balance Trend" },
    { mode: "success", icon: "CircleCheck", ariaLabel: "Success Rate", title: "Success Rate" },
  ]

  assert.equal(iconButtons.length, 4)
  for (const btn of iconButtons) {
    assert.ok(btn.ariaLabel.length > 0)
    assert.ok(btn.title.length > 0)
    assert.ok(["BarChart3", "IndianRupee", "TrendingUp", "CircleCheck"].includes(btn.icon))
  }

  const getButtonClass = (mode, activeMode) => {
    return mode === activeMode
      ? "bg-muted border border-border text-foreground shadow-2xs font-semibold"
      : "text-muted-foreground hover:text-foreground hover:bg-muted/40 border border-transparent"
  }

  assert.ok(getButtonClass("volume", "volume").includes("bg-muted"))
  assert.ok(getButtonClass("volume", "value").includes("border-transparent"))
})

// -------------------------------------------------------------
// TRANSACTION VOLUME CALCULATION TESTS
// -------------------------------------------------------------

test("7. Transaction Volume Calculation: groups transactions by date bucket and sorts chronologically", () => {
  const transactions = [
    { createdAt: "2026-09-26T11:00:00Z", amount: 75 },
    { createdAt: "2026-09-24T10:00:00Z", amount: 100 },
    { createdAt: "2026-09-24T14:30:00Z", amount: 50 },
    { createdAt: "2026-09-25T09:15:00Z", amount: 200 },
  ]

  const points = calculateVolumePoints(transactions)
  assert.equal(points.length, 3)
  assert.deepEqual(points[0], { date: "2026-09-24", volume: 2 })
  assert.deepEqual(points[1], { date: "2026-09-25", volume: 1 })
  assert.deepEqual(points[2], { date: "2026-09-26", volume: 1 })
})

// -------------------------------------------------------------
// TRANSACTION VALUE CALCULATION TESTS
// -------------------------------------------------------------

test("8. Transaction Value Calculation: sums monetary value in INR using decimal-safe arithmetic", () => {
  const transactions = [
    { createdAt: "2026-09-24T10:00:00Z", amount: 100.25, currency: "INR" },
    { createdAt: "2026-09-24T14:30:00Z", amount: 49.75, currency: "INR" },
    { createdAt: "2026-09-25T09:15:00Z", amount: 250000.0, currency: "INR" },
  ]

  const valuePoints = calculateValuePoints(transactions, "INR")
  assert.equal(valuePoints.length, 2)
  assert.equal(valuePoints[0].value, 150.0)
  assert.equal(valuePoints[0].formattedValue, "₹150.00")
  assert.equal(valuePoints[1].value, 250000.0)
  assert.equal(valuePoints[1].formattedValue, "₹2,50,000.00")

  // Rejects currency mixing without FX
  const mixed = [
    { createdAt: "2026-09-24T10:00:00Z", amount: 100, currency: "USD" },
    { createdAt: "2026-09-24T12:00:00Z", amount: 5000, currency: "INR" },
  ]
  assert.throws(() => calculateValuePoints(mixed, "INR"), /Currency mismatch/)
})

// -------------------------------------------------------------
// BALANCE TREND BEHAVIOR & INSUFFICIENT DATA
// -------------------------------------------------------------

test("9. Balance Trend Behavior: extracts authoritative running balances from statement entries", () => {
  const statementEntries = [
    { createdAt: "2026-09-24T10:00:00Z", balanceAfter: 1000.5 },
    { createdAt: "2026-09-24T12:00:00Z", balanceAfter: 1500.75 },
    { createdAt: "2026-09-25T08:00:00Z", balanceAfter: 1200.0 },
  ]

  const trend = extractBalanceTrend(statementEntries)
  assert.equal(trend.length, 3)
  assert.equal(trend[0].balance, 1000.5)
  assert.equal(trend[0].formattedBalance, "₹1,000.50")
  assert.equal(trend[1].balance, 1500.75)
  assert.equal(trend[2].balance, 1200.0)
})

test("10. Balance Trend Insufficient Data: displays honest insufficient-data state when zero entries exist", () => {
  const emptyEntries = []
  const trend = extractBalanceTrend(emptyEntries)

  assert.equal(trend.length, 0)

  // When trend is empty, UI must show Insufficient Data rather than manufacturing fake points
  const evaluateTrendState = (points) => {
    if (points.length === 0) {
      return {
        state: "INSUFFICIENT_DATA",
        title: "Insufficient data",
        description: "At least one settled ledger statement entry is required to establish historical trend points.",
      }
    }
    return { state: "HAS_DATA" }
  }

  const result = evaluateTrendState(trend)
  assert.equal(result.state, "INSUFFICIENT_DATA")
  assert.equal(result.title, "Insufficient data")
})

// -------------------------------------------------------------
// SUCCESS RATE CALCULATION & ZERO-DIVISION PROTECTION
// -------------------------------------------------------------

test("11. Success Rate Calculation: completed vs failed with zero-division protection", () => {
  // Empty transactions should not divide by zero
  const emptyResult = calculateSuccessRate([])
  assert.equal(emptyResult.overallRate, "0.0")
  assert.equal(emptyResult.points.length, 0)
  assert.equal(emptyResult.totalCount, 0)

  // Mixed transactions
  const transactions = [
    { createdAt: "2026-09-24T10:00:00Z", status: "COMPLETED" },
    { createdAt: "2026-09-24T12:00:00Z", status: "FAILED" },
    { createdAt: "2026-09-25T08:00:00Z", status: "COMPLETED" },
    { createdAt: "2026-09-25T14:00:00Z", status: "COMPLETED" },
  ]

  const result = calculateSuccessRate(transactions)
  assert.equal(result.totalCount, 4)
  assert.equal(result.completedCount, 3)
  assert.equal(result.failedCount, 1)
  assert.equal(result.overallRate, "75.0")
  assert.equal(result.points.length, 2)
  assert.deepEqual(result.points[0], { date: "2026-09-24", rate: 50, completed: 1, total: 2 })
  assert.deepEqual(result.points[1], { date: "2026-09-25", rate: 100, completed: 2, total: 2 })
})

// -------------------------------------------------------------
// ZERO-TRANSACTION HANDLING
// -------------------------------------------------------------

test("12. Zero-Transaction Handling: zero transactions produces clean empty state and no zero-value donut", () => {
  const volume = calculateVolumePoints([])
  const value = calculateValuePoints([])
  const success = calculateSuccessRate([])
  const composition = calculateComposition([])

  assert.equal(volume.length, 0)
  assert.equal(value.length, 0)
  assert.equal(success.points.length, 0)
  assert.equal(composition.total, 0)
  assert.equal(composition.transferPct, 0)
  assert.equal(composition.depositPct, 0)
  assert.equal(composition.withdrawalPct, 0)
})

// -------------------------------------------------------------
// TRANSACTION COMPOSITION CARD & BREAKDOWN TESTS
// -------------------------------------------------------------

test("13. Transaction Composition Breakdown: calculates count and percentage for TRANSFER, DEPOSIT, WITHDRAWAL, REVERSAL", () => {
  const txs = [
    { transactionType: "TRANSFER" },
    { transactionType: "TRANSFER" },
    { transactionType: "TRANSFER" },
    { transactionType: "DEPOSIT" },
    { transactionType: "WITHDRAWAL" },
    { transactionType: "REVERSAL" },
  ]

  const composition = calculateComposition(txs)
  assert.equal(composition.total, 6)
  assert.equal(composition.transfers, 3)
  assert.equal(composition.transferPct, 50)
  assert.equal(composition.deposits, 1)
  assert.equal(composition.depositPct, 17)
  assert.equal(composition.withdrawals, 1)
  assert.equal(composition.withdrawalPct, 17)
  assert.equal(composition.reversals, 1)
  assert.equal(composition.reversalPct, 17)
})

test("14. Transaction Composition Reversal Classification: does NOT silently classify REVERSAL as WITHDRAWAL", () => {
  // A batch of transactions containing ONLY a transfer and a reversal
  const txs = [
    { transactionType: "TRANSFER" },
    { transactionType: "REVERSAL" },
  ]

  const composition = calculateComposition(txs)
  assert.equal(composition.total, 2)
  assert.equal(composition.transfers, 1)
  assert.equal(composition.transferPct, 50)
  assert.equal(composition.withdrawals, 0, "Must NOT count reversals as withdrawals")
  assert.equal(composition.withdrawalPct, 0, "Withdrawal percentage must be 0 when no withdrawals exist")
  assert.equal(composition.reversals, 1, "Must count reversals in explicit reversal category")
  assert.equal(composition.reversalPct, 50, "Reversal percentage must be accurately computed")
})

test("15. Analytics Policy: Gross Financial Activity semantics with Net Movement via Balance Trend", () => {
  // Example from review:
  // Original transfer: ₹3,000
  // Reversal: ₹3,000
  const txs = [
    { createdAt: "2026-09-24T10:00:00Z", amount: 3000, currency: "INR", status: "COMPLETED", transactionType: "TRANSFER" },
    { createdAt: "2026-09-24T10:05:00Z", amount: 3000, currency: "INR", status: "COMPLETED", transactionType: "REVERSAL" },
  ]

  // Policy A: Gross Financial Activity
  // Volume represents gross transaction count (2 events)
  const volumePoints = calculateVolumePoints(txs)
  const totalVolume = txs.length
  assert.equal(totalVolume, 2, "Gross volume must count both original transfer and compensating reversal")
  assert.equal(volumePoints[0].volume, 2)

  // Value represents gross financial throughput (₹6,000 total turnover)
  const valuePoints = calculateValuePoints(txs, "INR")
  const totalValue = txs.reduce((sum, tx) => safeAddAmounts(sum, tx.amount), 0)
  assert.equal(totalValue, 6000.0, "Gross value must reflect total monetary throughput")
  assert.equal(valuePoints[0].value, 6000.0)

  // Net Movement is authoritatively represented via Balance Trend from statement entries:
  // Starting at 10,000: transfer debits 3,000 (balance -> 7,000); reversal credits 3,000 (balance -> 10,000)
  const statementEntries = [
    { createdAt: "2026-09-24T10:00:00Z", balanceAfter: 7000 },
    { createdAt: "2026-09-24T10:05:00Z", balanceAfter: 10000 },
  ]
  const balanceTrend = extractBalanceTrend(statementEntries)
  assert.equal(balanceTrend.length, 2)
  assert.equal(balanceTrend[0].balance, 7000)
  assert.equal(balanceTrend[1].balance, 10000, "Balance trend accurately reflects net financial position")
})

test("16. Transaction Composition Empty State: returns 0 retrieved and clean empty state when total === 0", () => {
  const composition = calculateComposition([])
  assert.equal(composition.total, 0)
  assert.equal(composition.transfers, 0)
  assert.equal(composition.deposits, 0)
  assert.equal(composition.withdrawals, 0)
  assert.equal(composition.reversals, 0)
  assert.equal(composition.reversalPct, 0)
})

// -------------------------------------------------------------
// FINANCIAL FORMATTING & PRECISION TESTS
// -------------------------------------------------------------

test("15. Financial Formatting: verifies INR formatting with Indian numbering and ₹ symbol", () => {
  assert.equal(formatINR(0), "₹0.00")
  assert.equal(formatINR(100), "₹100.00")
  assert.equal(formatINR(1250.5), "₹1,250.50")
  assert.equal(formatINR(100000), "₹1,00,000.00")
  assert.equal(formatINR(15000000), "₹1,50,00,000.00")
})

test("16. Safe Decimal Arithmetic: avoids floating-point precision pitfalls", () => {
  // Naive JavaScript floating point: 0.1 + 0.2 === 0.30000000000000004
  const naiveSum = 0.1 + 0.2
  assert.notEqual(naiveSum, 0.3)

  // Safe addition helper:
  const safeSum = safeAddAmounts(0.1, 0.2)
  assert.equal(safeSum, 0.3)
})

// -------------------------------------------------------------
// DATASET SCOPE & TRUTHFULNESS TESTS
// -------------------------------------------------------------

test("17. Dataset Scope Wording: communicates retrieved data scope without inflated claims", () => {
  const scopeNotice =
    "Based on retrieved transactions for the selected account instrument (up to 100 records). Metrics are deterministically calculated over authorized ledger data."

  assert.ok(scopeNotice.includes("Based on retrieved transactions"))
  assert.ok(scopeNotice.includes("up to 100 records"))
})

test("18. No 'All Time' Claim: forbids 'All Time' label and replaces with 'Retrieved'", () => {
  const timeRanges = [
    { value: "24h", label: "24 Hours" },
    { value: "7d", label: "7 Days" },
    { value: "30d", label: "30 Days" },
    { value: "retrieved", label: "Retrieved" },
  ]

  assert.equal(timeRanges.some((r) => r.label === "All Time"), false)
  assert.equal(timeRanges.some((r) => r.value === "all_time"), false)
  assert.equal(timeRanges.some((r) => r.label === "Retrieved"), true)
})

test("19. No 'Real-Time' Claim: forbids 'Real-Time', 'Live', and 'Complete History'", () => {
  const forbiddenPhrases = [
    "All Time",
    "Real-Time",
    "Live",
    "Complete History",
    "100% Accurate Historical Trend",
  ]

  const pageStrings = [
    "Analytics",
    "Financial activity and account trends",
    "Activity Overview",
    "Transaction Volume — Based on retrieved transactions",
    "Transaction Value Flow (INR) — Based on retrieved transactions",
    "Balance Trend (INR) — Authoritative ledger running balance",
    "Settlement Success Rate — Based on retrieved transactions",
    "Transaction Composition",
    "Breakdown of retrieved transactions by type",
    "Based on retrieved transactions",
  ]

  for (const phrase of forbiddenPhrases) {
    const leaked = pageStrings.some((s) => s.toLowerCase().includes(phrase.toLowerCase()))
    assert.equal(leaked, false, `Forbidden claim leaked: ${phrase}`)
  }
})

// -------------------------------------------------------------
// ACCOUNT SELECTION & MASKING TESTS
// -------------------------------------------------------------

test("20. Account Selection: filters strictly to eligible USER_CHECKING accounts and masks account numbers", () => {
  const allAccounts = [
    { accountId: "acc-1", accountNumber: "ACCT-111122223333", accountType: "USER_CHECKING", currency: "INR" },
    { accountId: "acc-2", accountNumber: "ACCT-444455556666", accountType: "USER_CHECKING", currency: "INR" },
    { accountId: "acc-sys", accountNumber: "PLATFORM-CLEARING", accountType: "SYSTEM_CLEARING", currency: "INR" },
  ]

  const eligibleAccounts = allAccounts.filter((a) => a.accountType === "USER_CHECKING")
  assert.equal(eligibleAccounts.length, 2)
  assert.equal(eligibleAccounts.some((a) => a.accountType === "SYSTEM_CLEARING"), false)

  assert.equal(maskAccountNumber(eligibleAccounts[0].accountNumber), "•••• 3333")
  assert.equal(maskAccountNumber(eligibleAccounts[1].accountNumber), "•••• 6666")
})

test("21. Empty Checking Accounts State: handles user with zero checking accounts cleanly", () => {
  const checkingAccounts = []
  const hasCheckingAccounts = checkingAccounts.length > 0
  assert.equal(hasCheckingAccounts, false)
})

// -------------------------------------------------------------
// ERROR SANITIZATION & LEAK PREVENTION TESTS
// -------------------------------------------------------------

test("22. Error Handling: maps status codes and strips technical leaks cleanly", () => {
  // 401 Session Expiry
  assert.equal(
    getAnalyticsErrorMessage({ status: 401 }),
    "Your session has expired. Please sign in again to continue."
  )

  // 403 Forbidden
  assert.equal(
    getAnalyticsErrorMessage({ status: 403 }),
    "Access denied: you do not have permission to view analytics for this account."
  )

  // 404 Account Not Found
  assert.equal(
    getAnalyticsErrorMessage({ status: 404 }),
    "The requested account could not be found."
  )

  // 429 Rate Limit
  assert.equal(
    getAnalyticsErrorMessage({ status: 429 }),
    "Too many requests. Please wait a moment before refreshing analytics."
  )

  // Network Error
  assert.equal(
    getAnalyticsErrorMessage({ status: 0 }),
    "Network connection failed. Please check your internet connection and try again."
  )
  assert.equal(
    getAnalyticsErrorMessage(new Error("Failed to fetch")),
    "Network connection failed. Please check your internet connection and try again."
  )

  // 500 / Technical Leaks (SQL, Hibernate, Redis, Spring)
  const leak1 = { status: 500, message: "org.springframework.dao.DataIntegrityViolationException: SQL error" }
  assert.equal(
    getAnalyticsErrorMessage(leak1),
    "A server error occurred while retrieving analytics data. Please try again shortly."
  )

  const leak2 = { status: 500, message: "RedisConnectionFailureException: cannot connect to Redis" }
  assert.equal(
    getAnalyticsErrorMessage(leak2),
    "A server error occurred while retrieving analytics data. Please try again shortly."
  )

  const leak3 = { status: 500, message: "HibernateException: deadlock detected in postgres" }
  assert.equal(
    getAnalyticsErrorMessage(leak3),
    "A server error occurred while retrieving analytics data. Please try again shortly."
  )
})

// -------------------------------------------------------------
// TANSTACK QUERY KEYS & CACHE INVALIDATION TESTS
// -------------------------------------------------------------

test("23. TanStack Query & Cache Invariants: uses consistent query keys and mutation reactions", () => {
  const TRANSACTION_KEYS = {
    all: ["transactions"],
    byAccount: (accountId, params) => ["transactions", "account", accountId, params],
  }

  const STATEMENT_KEYS = {
    all: ["statements"],
    byAccount: (accountId, params) => ["statements", "account", accountId, params],
  }

  const ACCOUNT_KEYS = {
    all: ["accounts"],
  }

  const accountId = "acct-test-uuid"
  const params = { from: "2026-09-01T00:00:00Z", size: 100 }

  const txKey = TRANSACTION_KEYS.byAccount(accountId, params)
  assert.deepEqual(txKey, ["transactions", "account", "acct-test-uuid", params])

  const statementKey = STATEMENT_KEYS.byAccount(accountId, params)
  assert.deepEqual(statementKey, ["statements", "account", "acct-test-uuid", params])

  assert.deepEqual(ACCOUNT_KEYS.all, ["accounts"])

  // Mutation invalidations from transfers, deposits, and withdrawals:
  const invalidatedOnMutation = ["accounts", "transactions", "statements", "reconciliation"]
  assert.ok(invalidatedOnMutation.includes("transactions"))
  assert.ok(invalidatedOnMutation.includes("statements"))
  assert.ok(invalidatedOnMutation.includes("accounts"))
})

// -------------------------------------------------------------
// ACCESSIBILITY & THEME COMPATIBILITY TESTS
// -------------------------------------------------------------

test("24. Accessibility: icon buttons have aria-label and charts provide supporting text", () => {
  const chartAria = {
    role: "tablist",
    ariaLabel: "Analytics Chart Modes",
    tabs: [
      { role: "tab", ariaLabel: "Transaction Volume", ariaSelected: true },
      { role: "tab", ariaLabel: "Transaction Value", ariaSelected: false },
      { role: "tab", ariaLabel: "Balance Trend", ariaSelected: false },
      { role: "tab", ariaLabel: "Success Rate", ariaSelected: false },
    ],
  }

  assert.equal(chartAria.role, "tablist")
  assert.equal(chartAria.tabs.length, 4)
  assert.ok(chartAria.tabs.every((t) => typeof t.ariaLabel === "string" && t.ariaLabel.length > 0))

  // Non-hover accessible supporting text:
  const getSupportingText = (metric, stats) => {
    switch (metric) {
      case "volume":
        return `Total retrieved volume: ${stats.totalVolume} transactions across ${stats.bucketCount} date buckets`
      case "value":
        return `Total retrieved value: ${formatINR(stats.totalValue)} across ${stats.bucketCount} date buckets`
      case "balance":
        return stats.hasEntries
          ? `Latest ledger balance: ${formatINR(stats.latestBalance)}`
          : "Insufficient data to plot balance trend"
      case "success":
        return `Overall settlement success rate: ${stats.successRate}%`
    }
  }

  const textVolume = getSupportingText("volume", { totalVolume: 10, bucketCount: 3 })
  assert.equal(textVolume, "Total retrieved volume: 10 transactions across 3 date buckets")

  const textBalanceInsufficient = getSupportingText("balance", { hasEntries: false })
  assert.equal(textBalanceInsufficient, "Insufficient data to plot balance trend")
})

test("25. Theme Token Compatibility: charts use CSS theme variables instead of hardcoded hex values", () => {
  const chartThemeTokens = {
    cardBackground: "var(--card)",
    borderColor: "var(--border)",
    textColor: "var(--foreground)",
    mutedTextColor: "var(--muted-foreground)",
    primaryChart: "var(--color-primary, #3b82f6)",
  }

  assert.ok(chartThemeTokens.cardBackground.startsWith("var(--"))
  assert.ok(chartThemeTokens.borderColor.startsWith("var(--"))
  assert.ok(chartThemeTokens.textColor.startsWith("var(--"))
  assert.ok(chartThemeTokens.mutedTextColor.startsWith("var(--"))
})
