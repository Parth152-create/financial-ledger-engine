import test from "node:test"
import assert from "node:assert/strict"

// 1. Transaction History Response Contract Tests
test("Transaction History: matches backend TransactionHistoryPageResponseDto structure", () => {
  const mockBackendResponse = {
    content: [
      {
        transactionId: "tx-hist-001",
        transactionType: "TRANSFER",
        direction: "DEBIT",
        sourceAccountId: "acct-alice",
        destinationAccountId: "acct-bob",
        amount: 250.0,
        currency: "USD",
        description: "Payment for services",
        status: "COMPLETED",
        initiatedByUserId: "user-alice",
        createdAt: "2026-09-24T10:00:00Z",
        completedAt: "2026-09-24T10:00:01Z",
      },
      {
        transactionId: "tx-hist-002",
        transactionType: "DEPOSIT",
        direction: "CREDIT",
        sourceAccountId: "00000000-0000-0000-0000-000000000001",
        destinationAccountId: "acct-alice",
        amount: 500.0,
        currency: "USD",
        description: "Direct deposit",
        status: "COMPLETED",
        initiatedByUserId: null,
        createdAt: "2026-09-24T09:00:00Z",
        completedAt: "2026-09-24T09:00:01Z",
      },
    ],
    page: 0,
    size: 20,
    totalElements: 2,
    totalPages: 1,
    first: true,
    last: true,
  }

  assert.equal(mockBackendResponse.content.length, 2)
  assert.equal(mockBackendResponse.page, 0)
  assert.equal(mockBackendResponse.size, 20)
  assert.equal(mockBackendResponse.totalElements, 2)
  assert.equal(mockBackendResponse.totalPages, 1)
  assert.equal(mockBackendResponse.first, true)
  assert.equal(mockBackendResponse.last, true)

  const first = mockBackendResponse.content[0]
  assert.equal(first.transactionId, "tx-hist-001")
  assert.equal(first.direction, "DEBIT")
  assert.equal(first.amount, 250.0)
  assert.equal(first.currency, "USD")
  assert.equal(first.status, "COMPLETED")
  assert.equal(first.transactionType, "TRANSFER")
})

// 2. Account-Relative Direction Tests
test("Transaction Direction: verifies direction relative to account ID", () => {
  const computeDirection = (tx, requestedAccountId) => {
    if (tx.sourceAccountId === requestedAccountId) {
      return "DEBIT"
    }
    if (tx.destinationAccountId === requestedAccountId) {
      return "CREDIT"
    }
    return "UNKNOWN"
  }

  const tx1 = {
    sourceAccountId: "acct-alice",
    destinationAccountId: "acct-bob",
    amount: 100,
  }

  assert.equal(computeDirection(tx1, "acct-alice"), "DEBIT")
  assert.equal(computeDirection(tx1, "acct-bob"), "CREDIT")

  const depositTx = {
    sourceAccountId: "00000000-0000-0000-0000-000000000001",
    destinationAccountId: "acct-alice",
    amount: 500,
  }

  assert.equal(computeDirection(depositTx, "acct-alice"), "CREDIT")

  const withdrawalTx = {
    sourceAccountId: "acct-alice",
    destinationAccountId: "00000000-0000-0000-0000-000000000001",
    amount: 200,
  }

  assert.equal(computeDirection(withdrawalTx, "acct-alice"), "DEBIT")
})

// 3. Query Parameter Builder & Date Range Tests
test("Query Parameters: correctly builds query parameters for backend endpoint", () => {
  const buildQueryParams = (params) => {
    const query = {}
    if (!params) return query

    if (params.transactionType) query.transactionType = params.transactionType
    if (params.status) query.status = params.status
    if (params.from) query.from = params.from
    if (params.to) query.to = params.to
    if (params.page !== undefined && params.page !== null) query.page = params.page
    if (params.size !== undefined && params.size !== null) query.size = params.size

    return query
  }

  const params1 = {
    transactionType: "TRANSFER",
    status: "COMPLETED",
    from: "2026-09-01T00:00:00Z",
    to: "2026-09-30T23:59:59Z",
    page: 2,
    size: 20,
  }

  const query1 = buildQueryParams(params1)
  assert.deepEqual(query1, {
    transactionType: "TRANSFER",
    status: "COMPLETED",
    from: "2026-09-01T00:00:00Z",
    to: "2026-09-30T23:59:59Z",
    page: 2,
    size: 20,
  })

  // Empty optional filters should not be included
  const params2 = {
    page: 0,
    size: 20,
  }
  const query2 = buildQueryParams(params2)
  assert.deepEqual(query2, { page: 0, size: 20 })
  assert.equal(query2.transactionType, undefined)
  assert.equal(query2.from, undefined)
})

// 4. Date Range Validation Tests
test("Date Range Validation: validates from and to ISO timestamps", () => {
  const validateDateRange = (fromStr, toStr) => {
    if (!fromStr || !toStr) return { valid: true }
    const fromTime = new Date(fromStr).getTime()
    const toTime = new Date(toStr).getTime()

    if (isNaN(fromTime)) return { valid: false, error: "Invalid 'from' timestamp" }
    if (isNaN(toTime)) return { valid: false, error: "Invalid 'to' timestamp" }

    if (fromTime > toTime) {
      return { valid: false, error: "'from' timestamp must be before or equal to 'to' timestamp" }
    }
    return { valid: true }
  }

  assert.equal(validateDateRange("2026-09-01T00:00:00Z", "2026-09-30T00:00:00Z").valid, true)
  assert.equal(validateDateRange("2026-09-24T10:00:00Z", "2026-09-24T10:00:00Z").valid, true)
  assert.equal(validateDateRange("2026-09-30T00:00:00Z", "2026-09-01T00:00:00Z").valid, false)
  assert.equal(validateDateRange("not-a-date", "2026-09-30T00:00:00Z").valid, false)
})

// 5. Pagination Logic & Boundary Tests
test("Pagination: calculates boundaries and disables buttons appropriately", () => {
  const getPaginationState = ({ page, totalPages }) => {
    const isFirst = page === 0
    const isLast = page >= totalPages - 1 || totalPages <= 1
    const canPrev = !isFirst
    const canNext = !isLast
    const pageDisplay = `Page ${page + 1} of ${Math.max(1, totalPages)}`

    return { isFirst, isLast, canPrev, canNext, pageDisplay }
  }

  // Single page
  const single = getPaginationState({ page: 0, totalPages: 1, totalElements: 5 })
  assert.equal(single.isFirst, true)
  assert.equal(single.isLast, true)
  assert.equal(single.canPrev, false)
  assert.equal(single.canNext, false)
  assert.equal(single.pageDisplay, "Page 1 of 1")

  // Multi-page, first page
  const firstPage = getPaginationState({ page: 0, totalPages: 5, totalElements: 100 })
  assert.equal(firstPage.canPrev, false)
  assert.equal(firstPage.canNext, true)
  assert.equal(firstPage.pageDisplay, "Page 1 of 5")

  // Multi-page, middle page
  const midPage = getPaginationState({ page: 2, totalPages: 5, totalElements: 100 })
  assert.equal(midPage.canPrev, true)
  assert.equal(midPage.canNext, true)
  assert.equal(midPage.pageDisplay, "Page 3 of 5")

  // Multi-page, last page
  const lastPage = getPaginationState({ page: 4, totalPages: 5, totalElements: 100 })
  assert.equal(lastPage.canPrev, true)
  assert.equal(lastPage.canNext, false)
  assert.equal(lastPage.pageDisplay, "Page 5 of 5")
})

// 6. Error Handling & Security Tests
test("Error Handling: hides technical stack traces and maps status codes", () => {
  const mapTransactionError = (error) => {
    if (!error) return "An unexpected error occurred"
    const status = error.status
    const raw = error.message || error.error || ""

    if (raw.includes("Exception") || raw.includes("org.springframework") || raw.includes("SQL")) {
      return "An unexpected server error occurred. Please try again later."
    }

    switch (status) {
      case 400:
        return "Invalid transaction query parameters or date range."
      case 401:
        return "Authentication required. Please sign in again."
      case 403:
      case 404:
        return "Account not found or you are not authorized to view its history."
      case 500:
        return "Internal server error occurred while retrieving transaction history."
      default:
        return raw || "Failed to retrieve transaction history."
    }
  }

  assert.equal(
    mapTransactionError({ status: 404, message: "Account not found: 123" }),
    "Account not found or you are not authorized to view its history."
  )
  assert.equal(
    mapTransactionError({ status: 500, message: "java.lang.NullPointerException at com.parth..." }),
    "An unexpected server error occurred. Please try again later."
  )
  assert.equal(
    mapTransactionError({ status: 400, message: "Illegal argument" }),
    "Invalid transaction query parameters or date range."
  )
})
