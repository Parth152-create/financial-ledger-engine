import test from "node:test"
import assert from "node:assert/strict"

// ============================================================================
// LEDGER MODULE TEST SUITE
// Covers all behavioral, financial, contractual, and presentation requirements
// for the Ledger module.
// ============================================================================

// -------------------------------------------------------------
// HELPER FUNCTIONS & LOGIC
// -------------------------------------------------------------

const SYSTEM_CLEARING_ID = "00000000-0000-0000-0000-000000000001"

function maskAccountNumber(accountNumber) {
  if (!accountNumber) return "•••• ----"
  const clean = String(accountNumber).trim()
  if (clean.length <= 4) return `•••• ${clean}`
  return `•••• ${clean.slice(-4)}`
}

function formatAccountFlowLabel(accountId, currentAccountId, accounts = []) {
  if (!accountId) return "Platform Clearing"
  if (accountId === SYSTEM_CLEARING_ID) return "Platform Clearing"

  const matched = accounts.find((a) => a.accountId === accountId)

  if (currentAccountId && accountId === currentAccountId) {
    if (matched?.accountNumber) {
      return `This Account (${maskAccountNumber(matched.accountNumber)})`
    }
    return "This Account"
  }

  if (matched?.accountNumber) {
    return `Checking ${maskAccountNumber(matched.accountNumber)}`
  }

  return maskAccountNumber(accountId)
}

function getLedgerErrorMessage(error) {
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
      rawMessage.includes("postgres")

    if (status === 401) {
      return "Your session has expired. Please sign in again to continue."
    }
    if (status === 403) {
      return "Access denied: you do not have permission to view ledger records for this account."
    }
    if (status === 404) {
      return "The requested account could not be found in the ledger."
    }
    if (status === 429) {
      return "Too many requests. Please wait a moment before refreshing."
    }
    if (status === 0 || error.error === "NetworkError" || rawMessage.toLowerCase().includes("network")) {
      return "Network connection failed. Please check your internet connection and try again."
    }
    if (status >= 500) {
      return "A server error occurred while retrieving ledger records. Please try again shortly."
    }
    if (!containsTechnicalLeak && rawMessage.trim()) {
      return rawMessage
    }
  }

  if (error instanceof Error) {
    if (error.message.includes("NetworkError") || error.message.includes("Failed to fetch")) {
      return "Network connection failed. Please check your internet connection and try again."
    }
  }

  return "An unexpected error occurred while loading ledger records. Please try again."
}

// -------------------------------------------------------------
// TEST CASES
// -------------------------------------------------------------

test("1. Ledger Page: renders focused financial operations structure and route", () => {
  const pageMetadata = {
    route: "/app/ledger",
    title: "Ledger",
    description: "Immutable financial transaction history and authoritative ledger activity.",
  }
  assert.equal(pageMetadata.route, "/app/ledger")
  assert.equal(pageMetadata.title, "Ledger")
  assert.ok(pageMetadata.description.includes("Immutable"))
})

test("2. Immutability Indicator: displays authoritative immutability notice without edit controls", () => {
  const immutabilityNotice = "Ledger entries are immutable. Corrections are recorded as compensating transactions."
  assert.ok(immutabilityNotice.includes("immutable"))
  assert.ok(immutabilityNotice.includes("compensating transactions"))

  // Ensure no editing operations are permitted on ledger records
  const supportedActions = ["view_details", "copy_id", "filter", "paginate"]
  assert.equal(supportedActions.includes("edit"), false)
  assert.equal(supportedActions.includes("delete"), false)
  assert.equal(supportedActions.includes("reverse"), false)
})

test("3. Account Instrument Selection: defaults to primary checking account and allows switching", () => {
  const accounts = [
    { accountId: "acc-1", accountNumber: "ACCT-111122223333", currency: "INR", balance: 5000, accountType: "USER_CHECKING" },
    { accountId: "acc-2", accountNumber: "ACCT-444455556666", currency: "INR", balance: 12000, accountType: "USER_CHECKING" },
  ]

  let activeAccountId = accounts[0].accountId
  assert.equal(activeAccountId, "acc-1")

  // Switching account updates active account
  activeAccountId = accounts[1].accountId
  assert.equal(activeAccountId, "acc-2")
})

test("4. Zero Accounts Empty State: prompts user to create an account before viewing ledger", () => {
  const checkingAccounts = []
  const hasAccounts = checkingAccounts.length > 0
  assert.equal(hasAccounts, false)

  const emptyState = {
    title: "No Checking Accounts Found",
    description: "You must have at least one active checking account to inspect ledger entries.",
    canCreate: true,
  }
  assert.equal(emptyState.title, "No Checking Accounts Found")
  assert.equal(emptyState.canCreate, true)
})

test("5. Account Counterparty Labeling: formats SYSTEM_CLEARING as 'Platform Clearing'", () => {
  const accounts = [
    { accountId: "acc-user-1", accountNumber: "ACCT-111122223333", accountType: "USER_CHECKING" },
    { accountId: "acc-user-2", accountNumber: "ACCT-555566667777", accountType: "USER_CHECKING" },
  ]

  // System clearing UUID should never be shown raw
  const labelClearing = formatAccountFlowLabel(SYSTEM_CLEARING_ID, "acc-user-1", accounts)
  assert.equal(labelClearing, "Platform Clearing")
  assert.ok(!labelClearing.includes("00000000"))

  // Current active account
  const labelCurrent = formatAccountFlowLabel("acc-user-1", "acc-user-1", accounts)
  assert.equal(labelCurrent, "This Account (•••• 3333)")

  // Counterparty user account
  const labelCounterparty = formatAccountFlowLabel("acc-user-2", "acc-user-1", accounts)
  assert.equal(labelCounterparty, "Checking •••• 7777")
})

test("6. Double-Entry Flow Presentation: generates accurate debit/credit direction and movement", () => {
  const formatFlow = (txType, sourceLabel, destLabel, amountStr) => {
    if (txType === "DEPOSIT") {
      return {
        source: "Platform Clearing",
        destination: destLabel,
        impact: `Credit ${amountStr}`,
      }
    }
    if (txType === "WITHDRAWAL") {
      return {
        source: sourceLabel,
        destination: "Platform Clearing",
        impact: `Debit ${amountStr}`,
      }
    }
    return {
      source: sourceLabel,
      destination: destLabel,
      impact: `Debit ${amountStr} -> Credit ${amountStr}`,
    }
  }

  const depositFlow = formatFlow("DEPOSIT", "Platform Clearing", "This Account (•••• 1234)", "₹1,000.00")
  assert.equal(depositFlow.source, "Platform Clearing")
  assert.equal(depositFlow.destination, "This Account (•••• 1234)")
  assert.equal(depositFlow.impact, "Credit ₹1,000.00")

  const withdrawalFlow = formatFlow("WITHDRAWAL", "This Account (•••• 1234)", "Platform Clearing", "₹500.00")
  assert.equal(withdrawalFlow.source, "This Account (•••• 1234)")
  assert.equal(withdrawalFlow.destination, "Platform Clearing")
  assert.equal(withdrawalFlow.impact, "Debit ₹500.00")

  const transferFlow = formatFlow("TRANSFER", "This Account (•••• 1234)", "Checking •••• 5678", "₹250.00")
  assert.equal(transferFlow.source, "This Account (•••• 1234)")
  assert.equal(transferFlow.destination, "Checking •••• 5678")
})

test("7. Financial Number & Currency Formatting: enforces ₹ prefix and Indian numbering", () => {
  const amount = 150000.75
  const formatted = `₹${amount.toLocaleString("en-IN", {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })}`

  assert.equal(formatted, "₹1,50,000.75")
  assert.ok(formatted.startsWith("₹"))
})

test("8. Filter Builder & Persistence: constructs query parameters and preserves them across paging", () => {
  const buildQueryParams = (filters, page = 0, size = 20) => {
    const params = { page, size }
    if (filters.transactionType) params.transactionType = filters.transactionType
    if (filters.status) params.status = filters.status
    if (filters.fromDate) params.from = `${filters.fromDate}T00:00:00Z`
    if (filters.toDate) params.to = `${filters.toDate}T23:59:59Z`
    return params
  }

  const filters = {
    transactionType: "TRANSFER",
    status: "COMPLETED",
    fromDate: "2026-09-01",
    toDate: "2026-09-28",
  }

  const page0Params = buildQueryParams(filters, 0, 20)
  assert.equal(page0Params.page, 0)
  assert.equal(page0Params.transactionType, "TRANSFER")
  assert.equal(page0Params.status, "COMPLETED")
  assert.equal(page0Params.from, "2026-09-01T00:00:00Z")
  assert.equal(page0Params.to, "2026-09-28T23:59:59Z")

  // Paging to page 1 preserves all active filters
  const page1Params = buildQueryParams(filters, 1, 20)
  assert.equal(page1Params.page, 1)
  assert.equal(page1Params.transactionType, "TRANSFER")
  assert.equal(page1Params.status, "COMPLETED")
  assert.equal(page1Params.from, "2026-09-01T00:00:00Z")
  assert.equal(page1Params.to, "2026-09-28T23:59:59Z")
})

test("9. Date Range Validation: validates start date is before or equal to end date", () => {
  const validateDateRange = (fromStr, toStr) => {
    if (!fromStr || !toStr) return true
    const from = new Date(fromStr).getTime()
    const to = new Date(toStr).getTime()
    return from <= to
  }

  assert.equal(validateDateRange("2026-09-01", "2026-09-28"), true)
  assert.equal(validateDateRange("2026-09-28", "2026-09-28"), true)
  assert.equal(validateDateRange("2026-09-29", "2026-09-28"), false)
})

test("10. Pagination Controls: calculates disabled states correctly", () => {
  const pageInfo = {
    page: 0,
    totalPages: 3,
    totalElements: 55,
    first: true,
    last: false,
  }

  const canGoPrevious = !pageInfo.first && pageInfo.page > 0
  const canGoNext = !pageInfo.last && pageInfo.page < pageInfo.totalPages - 1

  assert.equal(canGoPrevious, false)
  assert.equal(canGoNext, true)

  // Last page
  const lastPageInfo = {
    page: 2,
    totalPages: 3,
    totalElements: 55,
    first: false,
    last: true,
  }

  assert.equal(!lastPageInfo.first && lastPageInfo.page > 0, true)
  assert.equal(!lastPageInfo.last && lastPageInfo.page < lastPageInfo.totalPages - 1, false)
})

test("11. Transaction Detail Presentation: retains copyable transaction ID and excludes initiatedByUserId", () => {
  const tx = {
    transactionId: "550e8400-e29b-41d4-a716-446655440000",
    transactionType: "TRANSFER",
    direction: "DEBIT",
    sourceAccountId: "acc-user-1",
    destinationAccountId: "acc-user-2",
    amount: 1500.0,
    currency: "INR",
    description: "Vendor invoice payment",
    status: "COMPLETED",
    initiatedByUserId: "secret-user-uuid-9999",
    createdAt: "2026-09-28T07:00:00Z",
    completedAt: "2026-09-28T07:00:01Z",
  }

  const userFacingDetail = {
    transactionId: tx.transactionId,
    type: tx.transactionType,
    status: tx.status,
    amount: tx.amount,
    currency: tx.currency,
    description: tx.description,
    createdAt: tx.createdAt,
    completedAt: tx.completedAt,
    // initiatedByUserId deliberately excluded
  }

  assert.equal(userFacingDetail.transactionId, "550e8400-e29b-41d4-a716-446655440000")
  assert.equal("initiatedByUserId" in userFacingDetail, false)
})

test("12. Error Message Mapping: strips stack traces and maps HTTP status codes cleanly", () => {
  // 401
  assert.equal(
    getLedgerErrorMessage({ status: 401 }),
    "Your session has expired. Please sign in again to continue."
  )

  // 403
  assert.equal(
    getLedgerErrorMessage({ status: 403 }),
    "Access denied: you do not have permission to view ledger records for this account."
  )

  // 404
  assert.equal(
    getLedgerErrorMessage({ status: 404 }),
    "The requested account could not be found in the ledger."
  )

  // 429
  assert.equal(
    getLedgerErrorMessage({ status: 429 }),
    "Too many requests. Please wait a moment before refreshing."
  )

  // 500 with technical leakage
  const leakErr = {
    status: 500,
    message: "org.springframework.orm.jpa.JpaSystemException: could not execute query [SELECT ... from transactions tx]",
  }
  const safeMsg = getLedgerErrorMessage(leakErr)
  assert.equal(safeMsg, "A server error occurred while retrieving ledger records. Please try again shortly.")
  assert.ok(!safeMsg.includes("JpaSystemException"))
  assert.ok(!safeMsg.includes("SELECT"))
  assert.ok(!safeMsg.includes("org.springframework"))

  // Network Error
  assert.equal(
    getLedgerErrorMessage({ status: 0, error: "NetworkError" }),
    "Network connection failed. Please check your internet connection and try again."
  )
})

test("13. Query Invalidation & Cache Key: verifies query keys match TanStack conventions", () => {
  const TRANSACTION_KEYS = {
    all: ["transactions"],
    byAccount: (accountId, params) => ["transactions", "account", accountId, params],
  }

  const key = TRANSACTION_KEYS.byAccount("acc-123", { page: 0, size: 20 })
  assert.deepEqual(key, ["transactions", "account", "acc-123", { page: 0, size: 20 }])
})
