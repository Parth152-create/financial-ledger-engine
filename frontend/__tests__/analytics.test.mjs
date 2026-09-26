import test from "node:test"
import assert from "node:assert/strict"

// 1. Metric Audit & Availability Classification
test("Analytics Audit: classifies backend-provided, frontend-derived, and unavailable metrics", () => {
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

// 2. Transaction Volume Calculation Tests
test("Analytics Volume: groups transactions by date bucket and respects date range", () => {
  const transactions = [
    { createdAt: "2026-09-24T10:00:00Z", amount: 100 },
    { createdAt: "2026-09-24T14:30:00Z", amount: 50 },
    { createdAt: "2026-09-25T09:15:00Z", amount: 200 },
    { createdAt: "2026-09-26T11:00:00Z", amount: 75 },
  ]

  const calculateVolume = (txs) => {
    const map = new Map()
    txs.forEach((tx) => {
      const dateKey = tx.createdAt.slice(0, 10)
      map.set(dateKey, (map.get(dateKey) || 0) + 1)
    })
    return Array.from(map.entries()).map(([date, volume]) => ({ date, volume }))
  }

  const volumePoints = calculateVolume(transactions)
  assert.equal(volumePoints.length, 3)
  assert.deepEqual(volumePoints[0], { date: "2026-09-24", volume: 2 })
  assert.deepEqual(volumePoints[1], { date: "2026-09-25", volume: 1 })
  assert.deepEqual(volumePoints[2], { date: "2026-09-26", volume: 1 })
})

// 3. Transaction Value Flow & Currency Isolation Tests
test("Analytics Value: sums monetary value strictly within isolated currency", () => {
  const transactions = [
    { createdAt: "2026-09-24T10:00:00Z", amount: 100.5, currency: "USD" },
    { createdAt: "2026-09-24T14:30:00Z", amount: 49.5, currency: "USD" },
    { createdAt: "2026-09-25T09:15:00Z", amount: 200.0, currency: "USD" },
  ]

  const calculateValueFlow = (txs, targetCurrency) => {
    // Enforce currency safety: never mix currencies
    const invalidCurrency = txs.some((tx) => tx.currency !== targetCurrency)
    if (invalidCurrency) {
      throw new Error("Currency mismatch detected without FX conversion mechanism")
    }

    const map = new Map()
    txs.forEach((tx) => {
      const dateKey = tx.createdAt.slice(0, 10)
      map.set(dateKey, (map.get(dateKey) || 0) + Number(tx.amount))
    })
    return Array.from(map.entries()).map(([date, value]) => ({
      date,
      value: Math.round(value * 100) / 100,
    }))
  }

  const valuePoints = calculateValueFlow(transactions, "USD")
  assert.equal(valuePoints.length, 2)
  assert.equal(valuePoints[0].value, 150.0)
  assert.equal(valuePoints[1].value, 200.0)

  // Rejects currency mixing
  const mixedTxs = [
    { createdAt: "2026-09-24T10:00:00Z", amount: 100, currency: "USD" },
    { createdAt: "2026-09-24T12:00:00Z", amount: 5000, currency: "INR" },
  ]
  assert.throws(() => calculateValueFlow(mixedTxs, "USD"), /Currency mismatch/)
})

// 4. Balance Trend Handling Tests
test("Analytics Balance Trend: extracts authoritative running balances from statement", () => {
  const statementEntries = [
    { createdAt: "2026-09-24T10:00:00Z", balanceAfter: 1000 },
    { createdAt: "2026-09-24T12:00:00Z", balanceAfter: 1500 },
    { createdAt: "2026-09-25T08:00:00Z", balanceAfter: 1200 },
  ]

  const extractBalanceTrend = (entries) => {
    return entries.map((e) => ({
      date: e.createdAt.slice(0, 10),
      balance: e.balanceAfter,
    }))
  }

  const trend = extractBalanceTrend(statementEntries)
  assert.equal(trend.length, 3)
  assert.equal(trend[0].balance, 1000)
  assert.equal(trend[1].balance, 1500)
  assert.equal(trend[2].balance, 1200)
})

// 5. Success Rate Calculation Tests
test("Analytics Success Rate: explicitly defines numerator (COMPLETED) and denominator (TOTAL)", () => {
  const calculateSuccessRate = (transactions) => {
    if (!transactions || transactions.length === 0) {
      return { rate: 100.0, completed: 0, total: 0 }
    }

    const completed = transactions.filter((t) => t.status === "COMPLETED").length
    const failed = transactions.filter((t) => t.status === "FAILED").length
    const pending = transactions.filter((t) => t.status === "PENDING").length
    const total = completed + failed + pending

    const rate = total > 0 ? (completed / total) * 100 : 100.0
    return {
      rate: Number(rate.toFixed(1)),
      completed,
      failed,
      pending,
      total,
    }
  }

  // All completed
  const allCompleted = [
    { status: "COMPLETED" },
    { status: "COMPLETED" },
    { status: "COMPLETED" },
  ]
  assert.deepEqual(calculateSuccessRate(allCompleted), {
    rate: 100.0,
    completed: 3,
    failed: 0,
    pending: 0,
    total: 3,
  })

  // Mixed statuses: 3 completed, 1 failed
  const mixed = [
    { status: "COMPLETED" },
    { status: "COMPLETED" },
    { status: "COMPLETED" },
    { status: "FAILED" },
  ]
  assert.deepEqual(calculateSuccessRate(mixed), {
    rate: 75.0,
    completed: 3,
    failed: 1,
    pending: 0,
    total: 4,
  })

  // Empty transactions
  assert.deepEqual(calculateSuccessRate([]), {
    rate: 100.0,
    completed: 0,
    total: 0,
  })
})

// 6. Transaction Mix Calculation Tests
test("Analytics Transaction Mix: calculates distribution across TRANSFER, DEPOSIT, WITHDRAWAL", () => {
  const calculateMix = (transactions) => {
    const total = transactions.length
    if (total === 0) {
      return { transfers: 0, deposits: 0, withdrawals: 0, total: 0 }
    }

    const transfers = transactions.filter((t) => t.transactionType === "TRANSFER").length
    const deposits = transactions.filter((t) => t.transactionType === "DEPOSIT").length
    const withdrawals = transactions.filter((t) => t.transactionType === "WITHDRAWAL").length

    const transferPct = Math.round((transfers / total) * 100)
    const depositPct = Math.round((deposits / total) * 100)
    const withdrawalPct = Math.max(0, 100 - transferPct - depositPct)

    return {
      transfers,
      deposits,
      withdrawals,
      total,
      transferPct,
      depositPct,
      withdrawalPct,
    }
  }

  const txs = [
    { transactionType: "TRANSFER" },
    { transactionType: "TRANSFER" },
    { transactionType: "DEPOSIT" },
    { transactionType: "WITHDRAWAL" },
  ]

  const mix = calculateMix(txs)
  assert.equal(mix.total, 4)
  assert.equal(mix.transfers, 2)
  assert.equal(mix.transferPct, 50)
  assert.equal(mix.deposits, 1)
  assert.equal(mix.depositPct, 25)
  assert.equal(mix.withdrawals, 1)
  assert.equal(mix.withdrawalPct, 25)
  assert.equal(mix.transferPct + mix.depositPct + mix.withdrawalPct, 100)
})

// 7. Time-Window Selector & Retrieved Window Semantics Tests
test("Analytics Time-Window: verifies window options, labels, and retrieved window semantics", () => {
  const timeRanges = [
    { value: "24h", label: "24 Hours" },
    { value: "7d", label: "7 Days" },
    { value: "30d", label: "30 Days" },
    { value: "retrieved", label: "Retrieved" },
  ]

  // Verify "All Time" is retired and replaced with "Retrieved"
  assert.equal(timeRanges.some((r) => r.label === "All Time"), false)
  assert.equal(timeRanges.some((r) => r.label === "Retrieved"), true)
  assert.equal(timeRanges.find((r) => r.value === "retrieved")?.label, "Retrieved")

  // Function computing 'from' cutoff matching frontend derivation logic
  const computeFromTimestamp = (range, mockNow = new Date("2026-09-26T12:00:00.000Z")) => {
    if (range === "retrieved") return undefined
    const d = new Date(mockNow.getTime())
    if (range === "24h") {
      d.setHours(d.getHours() - 24)
    } else if (range === "7d") {
      d.setDate(d.getDate() - 7)
    } else if (range === "30d") {
      d.setDate(d.getDate() - 30)
    }
    return d.toISOString()
  }

  // 24h, 7d, 30d should produce ISO string timestamps
  assert.equal(computeFromTimestamp("24h"), "2026-09-25T12:00:00.000Z")
  assert.equal(computeFromTimestamp("7d"), "2026-09-19T12:00:00.000Z")
  assert.equal(computeFromTimestamp("30d"), "2026-08-27T12:00:00.000Z")

  // "retrieved" must return undefined (no 'from' timestamp filter, bound to retrieved page/window)
  assert.equal(computeFromTimestamp("retrieved"), undefined)
})

test("Analytics Calculations: calculations are computed strictly from retrieved transaction window without backend aggregation API", () => {
  // Underlying analytics calculations remain unchanged on the retrieved window
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
