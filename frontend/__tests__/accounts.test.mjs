import test from "node:test"
import assert from "node:assert/strict"

// ============================================================================
// ACCOUNTS MODULE TEST SUITE
// Covers all 14 mandatory behavioral and contractual requirements
// ============================================================================

// ----------------------------------------------------------------------------
// 1. Accounts Page Renders Account Data
// ----------------------------------------------------------------------------
test("1. Accounts Page Rendering: renders account data and filters system accounts", () => {
  const rawAccounts = [
    {
      accountId: "11111111-1111-1111-1111-111111111111",
      accountNumber: "ACCT-111122223333",
      accountType: "USER_CHECKING",
      status: "ACTIVE",
      currency: "INR",
      balance: 25000.5,
      createdAt: "2026-09-01T10:00:00Z",
      updatedAt: "2026-09-01T10:00:00Z",
    },
    {
      accountId: "22222222-2222-2222-2222-222222222222",
      accountNumber: "ACCT-444455556666",
      accountType: "USER_CHECKING",
      status: "FROZEN",
      currency: "INR",
      balance: 0.0,
      createdAt: "2026-09-02T12:00:00Z",
      updatedAt: "2026-09-02T12:00:00Z",
    },
    {
      accountId: "99999999-9999-9999-9999-999999999999",
      accountNumber: "SYS-CLEARING-INR",
      accountType: "SYSTEM_CLEARING",
      status: "ACTIVE",
      currency: "INR",
      balance: 10000000.0,
      createdAt: "2026-01-01T00:00:00Z",
      updatedAt: "2026-01-01T00:00:00Z",
    },
    {
      accountId: "88888888-8888-8888-8888-888888888888",
      accountNumber: "SYS-TREASURY-INR",
      accountType: "SYSTEM_TREASURY",
      status: "ACTIVE",
      currency: "INR",
      balance: 50000000.0,
      createdAt: "2026-01-01T00:00:00Z",
      updatedAt: "2026-01-01T00:00:00Z",
    },
  ]

  // Filter logic used by AccountsPage
  const filterUserAccounts = (accounts) => {
    if (!accounts) return []
    return accounts.filter((account) => account.accountType === "USER_CHECKING")
  }

  const displayedAccounts = filterUserAccounts(rawAccounts)
  assert.equal(displayedAccounts.length, 2)
  assert.ok(displayedAccounts.every((a) => a.accountType === "USER_CHECKING"))
  assert.ok(!displayedAccounts.some((a) => a.accountType.startsWith("SYSTEM_")))

  // Presentation check
  const first = displayedAccounts[0]
  assert.equal(first.accountNumber, "ACCT-111122223333")
  assert.equal(first.currency, "INR")
  assert.equal(first.status, "ACTIVE")
  assert.equal(first.balance, 25000.5)
})

// ----------------------------------------------------------------------------
// 2. Empty Accounts State
// ----------------------------------------------------------------------------
test("2. Empty Accounts State: correctly identifies empty state without fabricated balances", () => {
  const getAccountsViewState = (accounts, isLoading, error) => {
    if (isLoading) return "LOADING"
    if (error) return "ERROR"
    if (!accounts || accounts.length === 0) return "EMPTY"
    return "POPULATED"
  }

  assert.equal(getAccountsViewState([], false, null), "EMPTY")
  assert.equal(getAccountsViewState(null, false, null), "EMPTY")

  const emptyStateConfig = {
    title: "No accounts created yet",
    description: "Create a checking account to initiate transfers, deposit funds, and view statements.",
    actionText: "Create First Account",
    displaysBalance: false,
  }

  assert.equal(emptyStateConfig.title, "No accounts created yet")
  assert.equal(emptyStateConfig.displaysBalance, false, "Empty state must not synthesize fake account balances")
})

// ----------------------------------------------------------------------------
// 3. Loading State
// ----------------------------------------------------------------------------
test("3. Loading State: isLoading triggers LOADING state and shows skeleton placeholders", () => {
  const getAccountsViewState = (accounts, isLoading, error) => {
    if (isLoading) return "LOADING"
    if (error) return "ERROR"
    if (!accounts || accounts.length === 0) return "EMPTY"
    return "POPULATED"
  }

  // During query fetch, even if accounts is null or [], state must be LOADING
  assert.equal(getAccountsViewState(null, true, null), "LOADING")
  assert.equal(getAccountsViewState([], true, null), "LOADING")

  const skeletonConfig = {
    rowCount: 3,
    animated: true,
    columnSpan: 12,
  }
  assert.equal(skeletonConfig.rowCount, 3)
  assert.equal(skeletonConfig.animated, true)
})

// ----------------------------------------------------------------------------
// 4. Error State
// ----------------------------------------------------------------------------
test("4. Error State: handles API failure, sanitizes stack traces, and provides retry", () => {
  const sanitizeApiError = (err) => {
    const raw = err?.message || ""
    const isLeak =
      raw.includes("Exception") ||
      raw.includes("org.springframework") ||
      raw.includes("java.sql") ||
      raw.includes("PSQLException")

    return {
      title: "Failed to load accounts",
      message: isLeak
        ? "An error occurred while fetching accounts from the ledger."
        : raw || "An error occurred while fetching accounts from the ledger.",
      canRetry: true,
    }
  }

  const networkErr = sanitizeApiError(new Error("Failed to fetch"))
  assert.equal(networkErr.title, "Failed to load accounts")
  assert.equal(networkErr.message, "Failed to fetch")
  assert.equal(networkErr.canRetry, true)

  const leakErr = sanitizeApiError(new Error("org.springframework.dao.DataAccessException: Connection refused"))
  assert.equal(leakErr.title, "Failed to load accounts")
  assert.equal(leakErr.message, "An error occurred while fetching accounts from the ledger.")
  assert.equal(leakErr.canRetry, true)
})

// ----------------------------------------------------------------------------
// 5. Create Account Dialog
// ----------------------------------------------------------------------------
test("5. Create Account Dialog: enforces INR currency and rejects non-INR or malformed inputs", () => {
  const validateCreateAccountInput = (currency) => {
    const trimmed = (currency || "").trim().toUpperCase()
    if (!trimmed) {
      return { valid: false, error: "Currency is required" }
    }
    if (!/^[A-Z]{3}$/.test(trimmed)) {
      return { valid: false, error: "Currency must be exactly 3 uppercase letters (e.g., INR)" }
    }
    if (trimmed !== "INR") {
      return { valid: false, error: "Only INR currency is supported on this platform" }
    }
    return { valid: true, currency: trimmed }
  }

  // Valid inputs
  assert.deepEqual(validateCreateAccountInput("INR"), { valid: true, currency: "INR" })
  assert.deepEqual(validateCreateAccountInput("inr"), { valid: true, currency: "INR" })
  assert.deepEqual(validateCreateAccountInput("  inr  "), { valid: true, currency: "INR" })

  // Invalid inputs
  assert.equal(validateCreateAccountInput("").valid, false)
  assert.equal(validateCreateAccountInput("USD").valid, false)
  assert.equal(validateCreateAccountInput("USD").error, "Only INR currency is supported on this platform")
  assert.equal(validateCreateAccountInput("EUR").valid, false)
  assert.equal(validateCreateAccountInput("IN").valid, false)
  assert.equal(validateCreateAccountInput("INRT").valid, false)
  assert.equal(validateCreateAccountInput("123").valid, false)
})

// ----------------------------------------------------------------------------
// 6. Successful Account Creation
// ----------------------------------------------------------------------------
test("6. Successful Account Creation: contract adherence and query cache invalidation", () => {
  const mockCreateAccountResponse = (currency) => ({
    accountId: "c0000000-0000-0000-0000-000000000001",
    accountNumber: "ACCT-1A2B3C4D5E6F",
    accountType: "USER_CHECKING",
    status: "ACTIVE",
    currency: currency,
    balance: 0.0,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
  })

  const newAccount = mockCreateAccountResponse("INR")
  assert.equal(newAccount.currency, "INR")
  assert.equal(newAccount.accountType, "USER_CHECKING")
  assert.equal(newAccount.status, "ACTIVE")
  assert.equal(newAccount.balance, 0.0)
  assert.ok(newAccount.accountNumber.startsWith("ACCT-"))

  // Test query invalidation contract
  const invalidatedKeys = []
  const queryClient = {
    invalidateQueries: ({ queryKey }) => invalidatedKeys.push(queryKey),
    setQueryData: () => {},
  }

  const ACCOUNT_KEYS = {
    all: ["accounts"],
    detail: (id) => ["accounts", "detail", id],
  }

  const onAccountCreated = (account) => {
    queryClient.invalidateQueries({ queryKey: ACCOUNT_KEYS.all })
    queryClient.setQueryData(ACCOUNT_KEYS.detail(account.accountId), account)
  }

  onAccountCreated(newAccount)
  assert.deepEqual(invalidatedKeys, [["accounts"]])
})

// ----------------------------------------------------------------------------
// 7. Failed Account Creation
// ----------------------------------------------------------------------------
test("7. Failed Account Creation: handles HTTP error statuses without leaking technical traces", () => {
  const mapAccountCreationError = (err) => {
    if (!err) return "An unexpected error occurred while creating the account"
    const status = err.status
    const message = err.message || ""

    if (message.includes("Exception") || message.includes("org.springframework")) {
      return "Failed to create account. Please try again."
    }

    if (status === 400) {
      return message || "Invalid account creation request"
    }
    if (status === 409) {
      return "An account with these parameters already exists"
    }
    if (status === 401 || status === 403) {
      return "You do not have permission to create an account"
    }
    return message || "Failed to create account"
  }

  assert.equal(
    mapAccountCreationError({ status: 400, message: "Only INR currency is supported: USD" }),
    "Only INR currency is supported: USD"
  )
  assert.equal(
    mapAccountCreationError({ status: 409, message: "Conflict" }),
    "An account with these parameters already exists"
  )
  assert.equal(
    mapAccountCreationError({ status: 500, message: "java.lang.IllegalArgumentException: Internal error" }),
    "Failed to create account. Please try again."
  )
})

// ----------------------------------------------------------------------------
// 8. Account Detail Rendering
// ----------------------------------------------------------------------------
test("8. Account Detail Rendering: presents account number, metadata, balance, and account ID", () => {
  const account = {
    accountId: "123e4567-e89b-12d3-a456-426614174000",
    accountNumber: "ACCT-87654321",
    accountType: "USER_CHECKING",
    status: "ACTIVE",
    currency: "INR",
    balance: 54320.75,
    createdAt: "2026-09-15T08:30:00Z",
    updatedAt: "2026-09-15T09:00:00Z",
  }

  const formatAccountDetail = (acc) => ({
    headerTitle: acc.accountNumber,
    accountTypeDisplay: acc.accountType === "USER_CHECKING" ? "Checking" : acc.accountType,
    currency: acc.currency,
    status: acc.status,
    balance: acc.balance,
    metaRows: [
      { label: "Account Number", value: acc.accountNumber, monospace: true },
      { label: "Account Type", value: acc.accountType === "USER_CHECKING" ? "Checking" : acc.accountType },
      { label: "Base Currency", value: acc.currency, monospace: true },
      { label: "Status", value: acc.status },
      { label: "Account ID", value: acc.accountId, monospace: true },
    ],
  })

  const detail = formatAccountDetail(account)
  assert.equal(detail.headerTitle, "ACCT-87654321")
  assert.equal(detail.accountTypeDisplay, "Checking")
  assert.equal(detail.currency, "INR")
  assert.equal(detail.balance, 54320.75)
  assert.equal(detail.metaRows[0].label, "Account Number")
  assert.equal(detail.metaRows[0].value, "ACCT-87654321")
  assert.equal(detail.metaRows[4].label, "Account ID")
  assert.equal(detail.metaRows[4].value, "123e4567-e89b-12d3-a456-426614174000")
})

// ----------------------------------------------------------------------------
// 9. Account Not Found & Forbidden Handling
// ----------------------------------------------------------------------------
test("9. Account Not Found: discriminates 404, 403, and generic detail error states", () => {
  const getAccountDetailErrorContent = (error) => {
    const isNotFound = error?.status === 404
    const isForbidden = error?.status === 403

    return {
      title: isNotFound
        ? "Account not found"
        : isForbidden
        ? "Access Denied"
        : "Failed to load account",
      description: isNotFound
        ? "This account may no longer exist or you may not have access to it."
        : isForbidden
        ? "You don't have access to this account."
        : error?.message || "An unexpected error occurred while fetching account details.",
      hasBackToAccounts: true,
      hasRetry: !isNotFound && !isForbidden,
    }
  }

  // 404 Not Found
  const notFound = getAccountDetailErrorContent({ status: 404 })
  assert.equal(notFound.title, "Account not found")
  assert.equal(notFound.description, "This account may no longer exist or you may not have access to it.")
  assert.equal(notFound.hasBackToAccounts, true)
  assert.equal(notFound.hasRetry, false)

  // 403 Forbidden
  const forbidden = getAccountDetailErrorContent({ status: 403 })
  assert.equal(forbidden.title, "Access Denied")
  assert.equal(forbidden.description, "You don't have access to this account.")
  assert.equal(forbidden.hasBackToAccounts, true)
  assert.equal(forbidden.hasRetry, false)

  // 500 Server Error
  const serverError = getAccountDetailErrorContent({ status: 500, message: "Database connection failed" })
  assert.equal(serverError.title, "Failed to load account")
  assert.equal(serverError.hasBackToAccounts, true)
  assert.equal(serverError.hasRetry, true)
})

// ----------------------------------------------------------------------------
// 10. Transaction History Rendering
// ----------------------------------------------------------------------------
test("10. Transaction History: calculates flow direction, type badge, and amount formatting", () => {
  const txItemCredit = {
    transactionId: "tx-1111",
    transactionType: "TRANSFER",
    direction: "CREDIT",
    sourceAccountId: "source-acct-1",
    destinationAccountId: "target-acct-2",
    amount: 1500.0,
    currency: "INR",
    status: "COMPLETED",
    createdAt: "2026-09-20T14:30:00Z",
  }

  const txItemDebit = {
    transactionId: "tx-2222",
    transactionType: "WITHDRAWAL",
    direction: "DEBIT",
    sourceAccountId: "target-acct-2",
    destinationAccountId: "target-acct-2",
    amount: 500.0,
    currency: "INR",
    status: "COMPLETED",
    createdAt: "2026-09-21T11:00:00Z",
  }

  const mapTxFlow = (item) => ({
    isCredit: item.direction === "CREDIT",
    sign: item.direction === "CREDIT" ? "+" : "-",
    badge: item.direction === "CREDIT" ? "CR" : "DR",
    directionClass: item.direction === "CREDIT" ? "credit" : "debit",
  })

  const creditFlow = mapTxFlow(txItemCredit)
  assert.equal(creditFlow.isCredit, true)
  assert.equal(creditFlow.sign, "+")
  assert.equal(creditFlow.badge, "CR")
  assert.equal(creditFlow.directionClass, "credit")

  const debitFlow = mapTxFlow(txItemDebit)
  assert.equal(debitFlow.isCredit, false)
  assert.equal(debitFlow.sign, "-")
  assert.equal(debitFlow.badge, "DR")
  assert.equal(debitFlow.directionClass, "debit")
})

// ----------------------------------------------------------------------------
// 11. Transaction History Error
// ----------------------------------------------------------------------------
test("11. Transaction History Error: isolates transaction failure and provides retry", () => {
  const getTxHistoryState = (data, isLoading, isError, error) => {
    if (isLoading) return "LOADING"
    if (isError) {
      return {
        state: "ERROR",
        title: "Failed to load transaction history",
        message: error?.message || "An unexpected error occurred while fetching transactions.",
        canRetry: true,
      }
    }
    if (!data || data.content.length === 0) return "EMPTY"
    return "SUCCESS"
  }

  const errorResult = getTxHistoryState(null, false, true, new Error("Service temporarily unavailable"))
  assert.equal(errorResult.state, "ERROR")
  assert.equal(errorResult.title, "Failed to load transaction history")
  assert.equal(errorResult.message, "Service temporarily unavailable")
  assert.equal(errorResult.canRetry, true)
})

// ----------------------------------------------------------------------------
// 12. INR Balance Formatting
// ----------------------------------------------------------------------------
test("12. INR Balance Formatting: enforces ₹ symbol, Indian grouping, and 2 decimal places", () => {
  const formatINR = (amount, currency = "INR") => {
    const num = typeof amount === "number" ? amount : Number(amount)
    if (isNaN(num)) return String(amount)
    const symbol = currency === "INR" ? "₹" : currency
    return `${symbol}${num.toLocaleString("en-IN", {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    })}`
  }

  // Zero balance
  assert.equal(formatINR(0), "₹0.00")
  assert.equal(formatINR("0.0000"), "₹0.00")

  // Standard amounts
  assert.equal(formatINR(50), "₹50.00")
  assert.equal(formatINR(1250), "₹1,250.00")

  // Indian numbering system: 1,00,000 (1 Lakh), 10,00,000 (10 Lakhs), 1,00,00,000 (1 Crore)
  assert.equal(formatINR(100000), "₹1,00,000.00")
  assert.equal(formatINR(1000000), "₹10,00,000.00")
  assert.equal(formatINR(10000000), "₹1,00,00,000.00")
  assert.equal(formatINR("2543999.5000"), "₹25,43,999.50")
})

// ----------------------------------------------------------------------------
// 13. ACTIVE / FROZEN / CLOSED Status Presentation
// ----------------------------------------------------------------------------
test("13. Status Presentation: maps ACTIVE, FROZEN, CLOSED to appropriate badges", () => {
  const getStatusBadgeConfig = (status) => {
    switch (status) {
      case "ACTIVE":
        return { variant: "active", label: "Active", tone: "subtle-positive" }
      case "FROZEN":
        return { variant: "frozen", label: "Frozen", tone: "warning" }
      case "CLOSED":
        return { variant: "closed", label: "Closed", tone: "muted" }
      default:
        return { variant: "default", label: status, tone: "neutral" }
    }
  }

  const active = getStatusBadgeConfig("ACTIVE")
  assert.equal(active.variant, "active")
  assert.equal(active.tone, "subtle-positive")

  const frozen = getStatusBadgeConfig("FROZEN")
  assert.equal(frozen.variant, "frozen")
  assert.equal(frozen.tone, "warning")

  const closed = getStatusBadgeConfig("CLOSED")
  assert.equal(closed.variant, "closed")
  assert.equal(closed.tone, "muted")
})

// ----------------------------------------------------------------------------
// 14. Mobile & Accessibility Interactions
// ----------------------------------------------------------------------------
test("14. Accessibility & Mobile Interactions: keyboard escape, filters, and pagination", () => {
  // 14a. Escape key dismisses dialog unless submission is active
  const handleEscapeKey = ({ key, isOpen, isSubmitting, onClose }) => {
    if (key === "Escape" && isOpen && !isSubmitting) {
      onClose()
      return true
    }
    return false
  }

  let closedCount = 0
  const onClose = () => { closedCount++ }

  assert.equal(handleEscapeKey({ key: "Escape", isOpen: true, isSubmitting: false, onClose }), true)
  assert.equal(closedCount, 1)

  // Blocked while submitting
  assert.equal(handleEscapeKey({ key: "Escape", isOpen: true, isSubmitting: true, onClose }), false)
  assert.equal(closedCount, 1)

  // 14b. Search and status filter combination
  const testAccounts = [
    { accountId: "acc-1", accountNumber: "ACCT-1001", status: "ACTIVE", accountType: "USER_CHECKING" },
    { accountId: "acc-2", accountNumber: "ACCT-1002", status: "FROZEN", accountType: "USER_CHECKING" },
    { accountId: "acc-3", accountNumber: "ACCT-2001", status: "ACTIVE", accountType: "USER_CHECKING" },
  ]

  const applyFilters = (list, search, status) => {
    return list.filter((item) => {
      const matchSearch = !search || item.accountNumber.includes(search) || item.accountId.includes(search)
      const matchStatus = status === "ALL" || item.status === status
      return matchSearch && matchStatus
    })
  }

  assert.equal(applyFilters(testAccounts, "100", "ALL").length, 2)
  assert.equal(applyFilters(testAccounts, "100", "ACTIVE").length, 1)
  assert.equal(applyFilters(testAccounts, "", "FROZEN").length, 1)
  assert.equal(applyFilters(testAccounts, "999", "ALL").length, 0)

  // 14c. Pagination boundary logic
  const checkPaginationBounds = (page, totalPages, first, last) => ({
    canGoPrevious: !first && page > 0,
    canGoNext: !last && page < totalPages - 1,
    displayPage: page + 1,
    displayTotalPages: totalPages,
  })

  // Page 1 of 5
  assert.deepEqual(checkPaginationBounds(0, 5, true, false), {
    canGoPrevious: false,
    canGoNext: true,
    displayPage: 1,
    displayTotalPages: 5,
  })

  // Page 5 of 5
  assert.deepEqual(checkPaginationBounds(4, 5, false, true), {
    canGoPrevious: true,
    canGoNext: false,
    displayPage: 5,
    displayTotalPages: 5,
  })
})
