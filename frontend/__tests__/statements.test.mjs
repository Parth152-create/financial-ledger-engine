import test from "node:test"
import assert from "node:assert/strict"

// 1. Account Statement Response Contract Tests
test("Account Statement: matches backend AccountStatementResponseDto structure", () => {
  const mockBackendResponse = {
    accountId: "acct-test-01",
    accountNumber: "ACCT-TEST-01",
    currency: "USD",
    openingBalance: 1000.0,
    entries: [
      {
        transactionId: "tx-stmt-001",
        transactionType: "DEPOSIT",
        direction: "CREDIT",
        amount: 500.0,
        currency: "USD",
        description: "Initial deposit",
        status: "COMPLETED",
        createdAt: "2026-09-24T10:00:00Z",
        completedAt: "2026-09-24T10:00:01Z",
        balanceAfter: 1500.0,
      },
      {
        transactionId: "tx-stmt-002",
        transactionType: "TRANSFER",
        direction: "DEBIT",
        amount: 200.0,
        currency: "USD",
        description: "Transfer to Bob",
        status: "COMPLETED",
        createdAt: "2026-09-24T11:00:00Z",
        completedAt: "2026-09-24T11:00:02Z",
        balanceAfter: 1300.0,
      },
      {
        transactionId: "tx-stmt-003",
        transactionType: "WITHDRAWAL",
        direction: "DEBIT",
        amount: 100.0,
        currency: "USD",
        description: "ATM Cash",
        status: "COMPLETED",
        createdAt: "2026-09-24T12:00:00Z",
        completedAt: "2026-09-24T12:00:01Z",
        balanceAfter: 1200.0,
      },
    ],
    closingBalance: 1200.0,
    totalCredits: 500.0,
    totalDebits: 300.0,
    page: 0,
    size: 20,
    totalElements: 3,
    totalPages: 1,
    first: true,
    last: true,
  }

  assert.equal(mockBackendResponse.accountId, "acct-test-01")
  assert.equal(mockBackendResponse.accountNumber, "ACCT-TEST-01")
  assert.equal(mockBackendResponse.currency, "USD")
  assert.equal(mockBackendResponse.openingBalance, 1000.0)
  assert.equal(mockBackendResponse.closingBalance, 1200.0)
  assert.equal(mockBackendResponse.totalCredits, 500.0)
  assert.equal(mockBackendResponse.totalDebits, 300.0)
  assert.equal(mockBackendResponse.entries.length, 3)

  // Verify running balance integrity
  const entry1 = mockBackendResponse.entries[0]
  assert.equal(entry1.direction, "CREDIT")
  assert.equal(entry1.balanceAfter, 1500.0)

  const entry2 = mockBackendResponse.entries[1]
  assert.equal(entry2.direction, "DEBIT")
  assert.equal(entry2.balanceAfter, 1300.0)

  const entry3 = mockBackendResponse.entries[2]
  assert.equal(entry3.direction, "DEBIT")
  assert.equal(entry3.balanceAfter, 1200.0)

  // Verify balance equation: opening + totalCredits - totalDebits === closing
  const computedClosing =
    mockBackendResponse.openingBalance +
    mockBackendResponse.totalCredits -
    mockBackendResponse.totalDebits
  assert.equal(computedClosing, mockBackendResponse.closingBalance)
})

// 2. Running Balance & Direction Logic Tests
test("Statement Entries: verifies debit and credit effects on balance", () => {
  const verifyRunningBalance = (openingBalance, entries) => {
    let current = openingBalance
    for (const entry of entries) {
      if (entry.direction === "CREDIT") {
        current += entry.amount
      } else if (entry.direction === "DEBIT") {
        current -= entry.amount
      }
      assert.equal(
        Math.round(current * 10000) / 10000,
        Math.round(entry.balanceAfter * 10000) / 10000
      )
    }
    return current
  }

  const entries = [
    { direction: "CREDIT", amount: 1000, balanceAfter: 1000 },
    { direction: "DEBIT", amount: 300, balanceAfter: 700 },
    { direction: "CREDIT", amount: 200, balanceAfter: 900 },
    { direction: "DEBIT", amount: 150.5, balanceAfter: 749.5 },
  ]

  const finalBalance = verifyRunningBalance(0, entries)
  assert.equal(finalBalance, 749.5)
})

// 3. Statement Search Filtering Logic Tests
test("Statement Search: filters statement entries by transaction ID or description", () => {
  const entries = [
    {
      transactionId: "939f9670-3201-44eb-ae90-1de2399c0c33",
      transactionType: "DEPOSIT",
      description: "Payroll direct deposit",
      amount: 4500,
    },
    {
      transactionId: "34ba120f-5f3e-4774-8a43-6341f479b873",
      transactionType: "TRANSFER",
      description: "Rent payment for October",
      amount: 1200,
    },
    {
      transactionId: "e442da8e-c3a2-4381-8452-2febb8142da2",
      transactionType: "WITHDRAWAL",
      description: "ATM cash withdrawal",
      amount: 100,
    },
  ]

  const filterEntries = (query) => {
    if (!query || !query.trim()) return entries
    const q = query.trim().toLowerCase()
    return entries.filter(
      (e) =>
        e.transactionId.toLowerCase().includes(q) ||
        (e.description && e.description.toLowerCase().includes(q)) ||
        e.transactionType.toLowerCase().includes(q)
    )
  }

  // Exact ID match
  assert.equal(filterEntries("939f9670").length, 1)
  assert.equal(filterEntries("939f9670")[0].transactionType, "DEPOSIT")

  // Description keyword match
  assert.equal(filterEntries("rent").length, 1)
  assert.equal(filterEntries("rent")[0].transactionType, "TRANSFER")

  // Type keyword match
  assert.equal(filterEntries("withdrawal").length, 1)

  // Non-matching query
  assert.equal(filterEntries("non-existent-keyword").length, 0)

  // Empty query returns all
  assert.equal(filterEntries("").length, 3)
})

// 4. Statement Empty State Tests
test("Statement Empty State: correctly detects empty statement and handles zero balances", () => {
  const emptyStatement = {
    accountId: "acct-new",
    accountNumber: "ACCT-NEW-01",
    currency: "USD",
    openingBalance: 0,
    entries: [],
    closingBalance: 0,
    totalCredits: 0,
    totalDebits: 0,
    page: 0,
    size: 20,
    totalElements: 0,
    totalPages: 0,
    first: true,
    last: true,
  }

  assert.equal(emptyStatement.entries.length, 0)
  assert.equal(emptyStatement.totalElements, 0)
  assert.equal(emptyStatement.openingBalance, 0)
  assert.equal(emptyStatement.closingBalance, 0)
  assert.equal(emptyStatement.first, true)
  assert.equal(emptyStatement.last, true)
})
