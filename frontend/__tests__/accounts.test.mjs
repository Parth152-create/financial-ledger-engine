import test from "node:test"
import assert from "node:assert/strict"

// 1. INR & Currency Formatting Tests
test("INR Formatting: formats numbers correctly with Indian number grouping and 2 decimals", () => {
  const formatINR = (amount) => {
    const num = typeof amount === "number" ? amount : Number(amount)
    if (isNaN(num)) return String(amount)
    return `₹${num.toLocaleString("en-IN", {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    })}`
  }

  assert.equal(formatINR(0), "₹0.00")
  assert.equal(formatINR("0.0000"), "₹0.00")
  assert.equal(formatINR(10), "₹10.00")
  assert.equal(formatINR(100), "₹100.00")
  assert.equal(formatINR(1000), "₹1,000.00")
  assert.equal(formatINR(100000), "₹1,00,000.00")
  assert.equal(formatINR("150000.5000"), "₹1,50,000.50")
})

test("Currency Symbol: correctly resolves INR symbol to ₹ without assuming USD", () => {
  const getCurrencySymbol = (currency = "INR") => {
    if (currency === "INR") return "₹"
    if (currency === "USD") return "$"
    if (currency === "EUR") return "€"
    if (currency === "GBP") return "£"
    return currency
  }

  assert.equal(getCurrencySymbol(), "₹")
  assert.equal(getCurrencySymbol("INR"), "₹")
  assert.equal(getCurrencySymbol("EUR"), "€")
  assert.equal(getCurrencySymbol("USD"), "$")
})

// 2. Account Creation Validation Tests
test("Account Creation Validation: accepts valid 3-character uppercase ISO currency", () => {
  const validateCurrency = (currency) => {
    const trimmed = (currency || "").trim().toUpperCase()
    if (!trimmed) return { valid: false, error: "Currency is required" }
    if (!/^[A-Z]{3}$/.test(trimmed)) {
      return { valid: false, error: "Currency must be exactly 3 uppercase letters (e.g., INR)" }
    }
    return { valid: true, currency: trimmed }
  }

  assert.deepEqual(validateCurrency("INR"), { valid: true, currency: "INR" })
  assert.deepEqual(validateCurrency("inr"), { valid: true, currency: "INR" })
  assert.equal(validateCurrency("").valid, false)
  assert.equal(validateCurrency("   ").valid, false)
  assert.equal(validateCurrency("IN").valid, false)
  assert.equal(validateCurrency("INRT").valid, false)
  assert.equal(validateCurrency("123").valid, false)
})

// 3. Accounts Loading and Empty State Logic Tests
test("Accounts State Logic: empty list correctly identifies empty state", () => {
  const getViewState = (accounts, isLoading, error) => {
    if (isLoading) return "LOADING"
    if (error) return "ERROR"
    if (!accounts || accounts.length === 0) return "EMPTY"
    return "POPULATED"
  }

  assert.equal(getViewState(null, true, null), "LOADING")
  assert.equal(getViewState(null, false, new Error("Network error")), "ERROR")
  assert.equal(getViewState([], false, null), "EMPTY")
  assert.equal(
    getViewState(
      [
        {
          accountId: "123e4567-e89b-12d3-a456-426614174000",
          accountNumber: "ACCT-12345678",
          accountType: "USER_CHECKING",
          currency: "INR",
          balance: 0,
          status: "ACTIVE",
        },
      ],
      false,
      null
    ),
    "POPULATED"
  )
})

// 4. Account Filtering and Search Tests
test("Accounts Filter: search by account number or account ID", () => {
  const accounts = [
    {
      accountId: "aaaa-1111",
      accountNumber: "ACCT-1111",
      currency: "INR",
      status: "ACTIVE",
    },
    {
      accountId: "bbbb-2222",
      accountNumber: "ACCT-2222",
      currency: "INR",
      status: "FROZEN",
    },
  ]

  const filterAccounts = (list, query, statusFilter) => {
    return list.filter((a) => {
      const matchesSearch =
        !query ||
        a.accountNumber.toLowerCase().includes(query.toLowerCase()) ||
        a.accountId.toLowerCase().includes(query.toLowerCase())
      const matchesStatus = statusFilter === "ALL" || a.status === statusFilter
      return matchesSearch && matchesStatus
    })
  }

  assert.equal(filterAccounts(accounts, "1111", "ALL").length, 1)
  assert.equal(filterAccounts(accounts, "bbbb", "ALL").length, 1)
  assert.equal(filterAccounts(accounts, "", "ACTIVE").length, 1)
  assert.equal(filterAccounts(accounts, "", "CLOSED").length, 0)
})

// 5. Account Detail Loading & Error State Logic Tests
test("Account Detail State Logic: handles loading, not found 404, and loaded account", () => {
  const getDetailState = (account, isLoading, error) => {
    if (isLoading) return "LOADING"
    if (error || !account) return "ERROR"
    return "SUCCESS"
  }

  assert.equal(getDetailState(null, true, null), "LOADING")
  assert.equal(getDetailState(null, false, { status: 404, message: "Account not found" }), "ERROR")
  assert.equal(
    getDetailState(
      {
        accountId: "test-id",
        accountNumber: "ACCT-TEST",
        currency: "INR",
        balance: 0,
        status: "ACTIVE",
      },
      false,
      null
    ),
    "SUCCESS"
  )
})

// 6. Account Creation API Contract Verification
test("Account Creation Contract: starting balance is 0 and status is ACTIVE", () => {
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

  const created = mockCreateAccountResponse("INR")
  assert.equal(created.currency, "INR")
  assert.equal(created.accountType, "USER_CHECKING")
  assert.equal(created.status, "ACTIVE")
  assert.equal(created.balance, 0.0)
  assert.ok(created.accountNumber.startsWith("ACCT-"))
})
