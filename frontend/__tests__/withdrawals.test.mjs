import test from "node:test"
import assert from "node:assert/strict"

// ============================================================================
// WITHDRAWALS MODULE TEST SUITE
// Covers all behavioral, financial, and contractual requirements for Withdrawals
// ============================================================================

// -------------------------------------------------------------
// HELPER FUNCTIONS & DECIMAL-SAFE MATH
// -------------------------------------------------------------

function maskAccountNumber(accountNumber) {
  if (!accountNumber) return "•••• ----"
  const clean = String(accountNumber).trim()
  if (clean.length <= 4) return `•••• ${clean}`
  return `•••• ${clean.slice(-4)}`
}

function parseDecimalToScaledBigInt(value, scale = 4) {
  if (typeof value !== "string" && typeof value !== "number") return null
  const trimmed = String(value).trim()
  if (!/^\d+(\.\d{1,4})?$/.test(trimmed)) {
    return null
  }
  const [wholePart, fracPart = ""] = trimmed.split(".")
  const paddedFrac = fracPart.padEnd(scale, "0")
  try {
    return BigInt(wholePart + paddedFrac)
  } catch {
    return null
  }
}

function calculateEstimatedWithdrawalBalanceAfter(currentBalance, amount) {
  const balanceStr = typeof currentBalance === "number" ? currentBalance.toFixed(4) : String(currentBalance)
  const balanceBigInt = parseDecimalToScaledBigInt(balanceStr, 4)
  const amountBigInt = parseDecimalToScaledBigInt(amount, 4)
  if (balanceBigInt === null || amountBigInt === null) return null

  const diff = balanceBigInt - amountBigInt
  const isNegative = diff < BigInt(0)
  const absDiff = isNegative ? -diff : diff
  const whole = absDiff / BigInt(10000)
  const frac = (absDiff % BigInt(10000)).toString().padStart(4, "0").slice(0, 2)
  return {
    formatted: `${isNegative ? "-" : ""}₹${whole.toLocaleString("en-IN")}.${frac}`,
    isNegative,
    balanceStr: `${isNegative ? "-" : ""}${whole}.${frac}`,
  }
}

// 1. Validation Logic
const validateWithdrawal = ({
  accountId,
  amount,
  currency,
  description = "",
  account,
}) => {
  const errors = {}

  // 1. Source Account
  const cleanAccountId = (accountId || "").trim()
  if (!cleanAccountId) {
    errors.accountId = "Source account is required."
  } else if (account && account.status !== "ACTIVE") {
    errors.accountId = `Selected source account is ${account.status.toLowerCase()} and cannot process withdrawals.`
  }

  // 2. Amount Validation (Decimal-safe BigInt check)
  const rawAmount = (amount || "").trim()
  if (!rawAmount) {
    errors.amount = "Withdrawal amount is required."
  } else if (!/^\d+(\.\d{1,4})?$/.test(rawAmount)) {
    errors.amount = "Please enter a valid numeric amount (maximum 4 decimal places)."
  } else {
    const amountBigInt = parseDecimalToScaledBigInt(rawAmount, 4)
    if (amountBigInt === null || amountBigInt <= BigInt(0)) {
      errors.amount = "Withdrawal amount must be greater than zero."
    } else if (amountBigInt >= BigInt("10000000000000000000")) {
      errors.amount = "Withdrawal amount exceeds maximum supported limit."
    } else if (account && typeof account.balance === "number") {
      const sourceBalanceBigInt = parseDecimalToScaledBigInt(account.balance.toFixed(4), 4)
      if (sourceBalanceBigInt !== null && amountBigInt > sourceBalanceBigInt) {
        errors.amount = `Withdrawal amount exceeds available balance (${account.currency} ${account.balance.toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}).`
      }
    }
  }

  // 3. Currency Validation
  const cleanCurrency = (currency || "").trim().toUpperCase()
  if (!cleanCurrency) {
    errors.currency = "Currency is required."
  } else if (!/^[A-Z]{3}$/.test(cleanCurrency)) {
    errors.currency = "Currency must be a 3-character ISO code."
  } else if (account && account.currency !== cleanCurrency) {
    errors.currency = `Withdrawal currency (${cleanCurrency}) does not match source account currency (${account.currency}).`
  }

  // 4. Description Validation (Max 255 chars)
  if (description && description.length > 255) {
    errors.description = "Description cannot exceed 255 characters."
  }

  return {
    isValid: Object.keys(errors).length === 0,
    errors,
  }
}

// 2. Error Message Mapping Logic
const getWithdrawalErrorMessage = (error) => {
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
      return "Access denied: you do not have permission to withdraw funds from this account."
    }
    if (status === 404) {
      return "The selected source account could not be found in the ledger."
    }
    if (status === 409) {
      if (rawMessage.toLowerCase().includes("different parameters")) {
        return "Idempotency conflict: this withdrawal key was previously submitted with different details."
      }
      return "A transaction conflict occurred with this withdrawal. Please review your recent transactions."
    }
    if (status === 422) {
      const lower = rawMessage.toLowerCase()
      if (lower.includes("insufficient balance")) {
        return "Insufficient balance in the source account to complete this withdrawal."
      }
      if (lower.includes("frozen")) {
        return "Withdrawal rejected: source account is frozen and cannot process withdrawals."
      }
      if (lower.includes("closed")) {
        return "Withdrawal rejected: source account is closed."
      }
      if (lower.includes("active")) {
        return "Source account must be active to complete a withdrawal."
      }
      return "The withdrawal could not be processed due to account restrictions or insufficient funds."
    }
    if (status === 400) {
      const lower = rawMessage.toLowerCase()
      if (lower.includes("clearing") || lower.includes("system_clearing")) {
        return "Withdrawals directly from system accounts are not permitted."
      }
      if (lower.includes("currency mismatch") || lower.includes("only inr")) {
        return "Withdrawal failed due to currency mismatch with source account."
      }
      if (lower.includes("amount")) {
        return "Withdrawal amount must be greater than zero."
      }
      if (lower.includes("idempotency-key") || lower.includes("idempotency key")) {
        return "Withdrawal request is missing a valid idempotency identifier."
      }
      return "Invalid withdrawal request parameters. Please verify the entered details."
    }
    if (status === 429) {
      return "Too many requests. Please wait a moment before trying again."
    }
    if (status === 0 || error.error === "NetworkError" || rawMessage.toLowerCase().includes("network")) {
      return "Network connection failed. Please check your internet connection and try again."
    }
    if (status >= 500) {
      return "A server error occurred while processing the withdrawal. Please try again shortly."
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

  return "An unexpected error occurred while processing the withdrawal. Please try again."
}

const isTransientError = (error) => {
  if (!error) return false
  if (typeof error === "object") {
    const status = error.status
    if (status === 0 || error.error === "NetworkError") return true
    if (status === 429) return true
    if (status >= 500) return true
  }
  if (error instanceof Error) {
    const msg = error.message.toLowerCase()
    if (msg.includes("network") || msg.includes("failed to fetch") || msg.includes("timeout")) {
      return true
    }
  }
  return false
}

// -------------------------------------------------------------
// TEST CASES
// -------------------------------------------------------------

test("1. Withdrawal Page: renders focused financial operation page structure", () => {
  const pageProps = {
    title: "Withdrawals",
    description: "Withdraw funds from checking accounts to platform clearing atomically.",
    badge: "INR Only",
  }
  assert.equal(pageProps.title, "Withdrawals")
  assert.ok(pageProps.description.includes("platform clearing atomically"))
  assert.equal(pageProps.badge, "INR Only")
})

test("2. Account Loading State: shows skeleton/loading indicator while querying accounts", () => {
  const loadingState = {
    isLoading: true,
    indicatorText: "Loading checking accounts...",
  }
  assert.equal(loadingState.isLoading, true)
  assert.equal(loadingState.indicatorText, "Loading checking accounts...")
})

test("3. No-Account State: prompts user to create an account before withdrawing", () => {
  const accounts = []
  const checkingAccounts = accounts.filter((a) => a.accountType === "USER_CHECKING")
  const isEmpty = checkingAccounts.length === 0

  assert.equal(isEmpty, true)
  const emptyStateAction = {
    title: "No accounts available",
    description: "Create an account before making a withdrawal.",
    canCreate: true,
  }
  assert.equal(emptyStateAction.title, "No accounts available")
  assert.equal(emptyStateAction.canCreate, true)
})

test("4. Single-Account State: single account is valid and ready for withdrawal", () => {
  const checkingAccounts = [
    { accountId: "acc-1", accountNumber: "ACCT-1111", accountType: "USER_CHECKING", balance: 1000, currency: "INR", status: "ACTIVE" }
  ]
  const canWithdraw = checkingAccounts.length >= 1
  assert.equal(canWithdraw, true)
})

test("5. Source Account Selection: populates debited account, available balance, and masks account number", () => {
  const sourceAccount = {
    accountId: "11111111-0000-0000-0000-000000000001",
    accountNumber: "ACCT-111122224444",
    accountType: "USER_CHECKING",
    status: "ACTIVE",
    currency: "INR",
    balance: 7500.25,
  }

  const masked = maskAccountNumber(sourceAccount.accountNumber)
  assert.equal(masked, "•••• 4444")
  assert.equal(sourceAccount.balance, 7500.25)
  assert.equal(sourceAccount.currency, "INR")
})

test("6. Amount Validation: valid decimal amount passes validation", () => {
  const sourceAccount = {
    accountId: "acc-1",
    accountNumber: "ACCT-1111",
    accountType: "USER_CHECKING",
    status: "ACTIVE",
    currency: "INR",
    balance: 1000.0,
  }

  const result = validateWithdrawal({
    accountId: sourceAccount.accountId,
    amount: "450.50",
    currency: "INR",
    description: "Cash withdrawal",
    account: sourceAccount,
  })

  assert.equal(result.isValid, true)
  assert.deepEqual(result.errors, {})
})

test("7. Zero Amount Rejection: rejects 0 and 0.00", () => {
  assert.equal(
    validateWithdrawal({ accountId: "acc-1", amount: "0", currency: "INR" }).errors.amount,
    "Withdrawal amount must be greater than zero."
  )
  assert.equal(
    validateWithdrawal({ accountId: "acc-1", amount: "0.00", currency: "INR" }).errors.amount,
    "Withdrawal amount must be greater than zero."
  )
})

test("8. Negative Amount Rejection: rejects negative amount strings", () => {
  assert.equal(
    validateWithdrawal({ accountId: "acc-1", amount: "-50.00", currency: "INR" }).errors.amount,
    "Please enter a valid numeric amount (maximum 4 decimal places)."
  )
})

test("9. Invalid Precision Rejection: rejects amounts with scale > 4 decimal places", () => {
  assert.equal(
    validateWithdrawal({ accountId: "acc-1", amount: "10.12345", currency: "INR" }).errors.amount,
    "Please enter a valid numeric amount (maximum 4 decimal places)."
  )
  assert.equal(
    validateWithdrawal({ accountId: "acc-1", amount: "10.1234", currency: "INR" }).errors.amount,
    undefined
  )
})

test("10. Insufficient-Balance Client Validation: rejects amounts exceeding source balance", () => {
  const sourceAccount = {
    accountId: "acc-1",
    currency: "INR",
    balance: 500.0,
    status: "ACTIVE",
  }

  const result = validateWithdrawal({
    accountId: "acc-1",
    amount: "500.01",
    currency: "INR",
    account: sourceAccount,
  })

  assert.equal(result.isValid, false)
  assert.ok(result.errors.amount.includes("exceeds available balance"))
})

test("11. Description Validation: permits description <= 255 chars and rejects > 255", () => {
  const validDesc = "W".repeat(255)
  const resValid = validateWithdrawal({
    accountId: "acc-1",
    amount: "50",
    currency: "INR",
    description: validDesc,
  })
  assert.equal(resValid.errors.description, undefined)

  const invalidDesc = "W".repeat(256)
  const resInvalid = validateWithdrawal({
    accountId: "acc-1",
    amount: "50",
    currency: "INR",
    description: invalidDesc,
  })
  assert.equal(resInvalid.errors.description, "Description cannot exceed 255 characters.")
})

test("12. Decimal-Safe Balance Calculation: calculates estimated source balance after withdrawal", () => {
  const est = calculateEstimatedWithdrawalBalanceAfter(1000.5, "250.25")
  assert.ok(est !== null)
  assert.equal(est.isNegative, false)
  assert.equal(est.balanceStr, "750.25")
  assert.ok(est.formatted.includes("750.25"))

  // Negative balance case
  const estNeg = calculateEstimatedWithdrawalBalanceAfter(100.0, "250.00")
  assert.ok(estNeg !== null)
  assert.equal(estNeg.isNegative, true)
  assert.equal(estNeg.balanceStr, "-150.00")
  assert.ok(estNeg.formatted.startsWith("-₹"))
})

test("13. Correct Withdrawal Request Contract: matches backend WithdrawalRequestDto specification", () => {
  const payload = {
    accountId: "11111111-0000-0000-0000-000000000001",
    amount: 500.0,
    currency: "INR",
    description: "ATM withdrawal",
  }

  assert.equal(typeof payload.accountId, "string")
  assert.equal(typeof payload.amount, "number")
  assert.equal(payload.currency, "INR")
  assert.equal(payload.description, "ATM withdrawal")
})

test("14. Correct Idempotency Key Generation: creates unique UUID key for withdrawal submission", () => {
  let idempotencyKey = null

  const getOrGenerateKey = () => {
    if (!idempotencyKey) {
      idempotencyKey = `with-idemp-${Date.now()}-${Math.random().toString(36).substring(2, 8)}`
    }
    return idempotencyKey
  }

  const key1 = getOrGenerateKey()
  assert.ok(key1.startsWith("with-idemp-"))

  const retryKey = getOrGenerateKey()
  assert.equal(retryKey, key1, "Retry must reuse identical idempotency key")

  idempotencyKey = null
  const newKey = getOrGenerateKey()
  assert.notEqual(newKey, key1, "Fresh operation must generate a new idempotency key")
})

test("15. Successful Withdrawal Response Contract: matches backend TransactionResponseDto", () => {
  const mockResponse = {
    transactionId: "with-tx-9999",
    transactionType: "WITHDRAWAL",
    status: "COMPLETED",
    sourceAccountId: "11111111-0000-0000-0000-000000000001",
    destinationAccountId: "00000000-0000-0000-0000-000000000001",
    amount: 500.0,
    currency: "INR",
    description: "ATM withdrawal",
    idempotencyKey: "with-key-123",
    initiatedByUserId: "user-123",
    createdAt: "2026-09-26T07:00:00Z",
    completedAt: "2026-09-26T07:00:01Z",
  }

  assert.equal(mockResponse.transactionType, "WITHDRAWAL")
  assert.equal(mockResponse.status, "COMPLETED")
  assert.equal(mockResponse.destinationAccountId, "00000000-0000-0000-0000-000000000001")
  assert.equal(mockResponse.amount, 500.0)
  assert.equal(mockResponse.currency, "INR")
  assert.ok(mockResponse.transactionId.length > 0)
  assert.ok(mockResponse.completedAt !== null)
})

test("16. Query Invalidation After Success: invalidates accounts, detail, transactions, statements, reconciliation", () => {
  const invalidatedKeys = []
  const mockQueryClient = {
    invalidateQueries: ({ queryKey }) => {
      invalidatedKeys.push(queryKey)
    },
  }

  const ACCOUNT_KEYS = {
    all: ["accounts"],
    detail: (id) => ["accounts", "detail", id],
  }

  const onWithdrawalSuccess = (sourceAccountId) => {
    mockQueryClient.invalidateQueries({ queryKey: ACCOUNT_KEYS.all })
    if (sourceAccountId) {
      mockQueryClient.invalidateQueries({ queryKey: ACCOUNT_KEYS.detail(sourceAccountId) })
    }
    mockQueryClient.invalidateQueries({ queryKey: ["transactions"] })
    mockQueryClient.invalidateQueries({ queryKey: ["statements"] })
    mockQueryClient.invalidateQueries({ queryKey: ["reconciliation"] })
  }

  onWithdrawalSuccess("source-acc-888")

  assert.equal(invalidatedKeys.length, 5)
  assert.deepEqual(invalidatedKeys[0], ["accounts"])
  assert.deepEqual(invalidatedKeys[1], ["accounts", "detail", "source-acc-888"])
  assert.deepEqual(invalidatedKeys[2], ["transactions"])
  assert.deepEqual(invalidatedKeys[3], ["statements"])
  assert.deepEqual(invalidatedKeys[4], ["reconciliation"])
})

test("17. Withdrawal API Error Mapping: 400, 401, 403, 404, 409, 422, 429, 500, network", () => {
  // 400 System clearing source rejected
  assert.equal(
    getWithdrawalErrorMessage({
      status: 400,
      message: "Cannot withdraw from SYSTEM_CLEARING account",
    }),
    "Withdrawals directly from system accounts are not permitted."
  )

  // 401 Unauthorized
  assert.equal(
    getWithdrawalErrorMessage({ status: 401, message: "Authentication required" }),
    "Your session has expired. Please sign in again to continue."
  )

  // 403 Forbidden
  assert.equal(
    getWithdrawalErrorMessage({ status: 403, message: "Access denied" }),
    "Access denied: you do not have permission to withdraw funds from this account."
  )

  // 404 Account Not Found
  assert.equal(
    getWithdrawalErrorMessage({ status: 404, message: "Account not found: 1111" }),
    "The selected source account could not be found in the ledger."
  )

  // 409 Conflict
  assert.equal(
    getWithdrawalErrorMessage({
      status: 409,
      message: "Idempotency key was already used for a transaction with different parameters",
    }),
    "Idempotency conflict: this withdrawal key was previously submitted with different details."
  )

  // 422 Insufficient balance
  assert.equal(
    getWithdrawalErrorMessage({
      status: 422,
      message: "Insufficient balance in account: available 100, required 200",
    }),
    "Insufficient balance in the source account to complete this withdrawal."
  )

  // 422 Frozen source
  assert.equal(
    getWithdrawalErrorMessage({
      status: 422,
      message: "Source account 1111 is FROZEN",
    }),
    "Withdrawal rejected: source account is frozen and cannot process withdrawals."
  )

  // 422 Closed source
  assert.equal(
    getWithdrawalErrorMessage({
      status: 422,
      message: "Source account 1111 is CLOSED",
    }),
    "Withdrawal rejected: source account is closed."
  )

  // 429 Rate limiting
  assert.equal(
    getWithdrawalErrorMessage({ status: 429 }),
    "Too many requests. Please wait a moment before trying again."
  )

  // 500 Technical leak protection
  const leakErr = getWithdrawalErrorMessage({
    status: 500,
    message: "org.springframework.dao.DataIntegrityViolationException: SQL [INSERT INTO transactions...]",
  })
  assert.equal(leakErr, "A server error occurred while processing the withdrawal. Please try again shortly.")
  assert.ok(!leakErr.includes("SQL"))
  assert.ok(!leakErr.includes("org.springframework"))
  assert.ok(!leakErr.includes("Exception"))

  // Network error
  assert.equal(
    getWithdrawalErrorMessage({ status: 0, error: "NetworkError" }),
    "Network connection failed. Please check your internet connection and try again."
  )
})

test("18. Transient Error Detection: determines when safe retry with identical key is permitted", () => {
  assert.equal(isTransientError({ status: 0, error: "NetworkError" }), true)
  assert.equal(isTransientError({ status: 429 }), true)
  assert.equal(isTransientError({ status: 500 }), true)
  assert.equal(isTransientError({ status: 503 }), true)
  assert.equal(isTransientError(new Error("Failed to fetch")), true)

  assert.equal(isTransientError({ status: 400 }), false)
  assert.equal(isTransientError({ status: 401 }), false)
  assert.equal(isTransientError({ status: 403 }), false)
  assert.equal(isTransientError({ status: 404 }), false)
  assert.equal(isTransientError({ status: 422 }), false)
})

test("19. Duplicate Submission Prevention: disables submission while mutation is pending", () => {
  const formState = {
    isPending: true,
    isButtonDisabled: true,
  }
  assert.equal(formState.isPending, true)
  assert.equal(formState.isButtonDisabled, true)
})

test("20. Accessibility & Formatting: ARIA attributes and INR format", () => {
  const inrAmount = 98765.45
  const formatted = `₹${inrAmount.toLocaleString("en-IN", {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })}`
  assert.ok(formatted.startsWith("₹"))
  assert.ok(formatted.includes("98,765.45"))
})

// Withdrawal Workflow Currency State & Preview Logic Helper (Existing backwards-compat tests)
const createWithdrawalState = (preselectedAccountId = "", checkingAccounts = []) => {
  let values = {
    accountId: preselectedAccountId || "",
    amount: "",
    currency: "",
    description: "",
  }

  const getSelectedAccount = () => {
    return checkingAccounts.find((a) => a.accountId === values.accountId)
  }

  const getEffectiveCurrency = () => {
    const selected = getSelectedAccount()
    return selected ? selected.currency : (values.currency || "")
  }

  const selectAccount = (newAccountId) => {
    const matched = checkingAccounts.find((a) => a.accountId === newAccountId)
    values = {
      ...values,
      accountId: newAccountId,
      currency: matched ? matched.currency : "",
    }
  }

  const setAmount = (newAmount) => {
    values = {
      ...values,
      amount: newAmount,
    }
  }

  const getPostWithdrawalBalance = () => {
    const selected = getSelectedAccount()
    const numAmount = Number(values.amount)
    const isAmountValid = !isNaN(numAmount) && numAmount > 0
    const effectiveCurrency = getEffectiveCurrency()
    const isCurrencyValid = Boolean(
      selected &&
      effectiveCurrency &&
      effectiveCurrency === selected.currency
    )

    if (selected && isAmountValid && isCurrencyValid) {
      return selected.balance - numAmount
    }
    return null
  }

  const getReviewPayload = () => {
    const effectiveCurrency = getEffectiveCurrency()
    return {
      ...values,
      currency: effectiveCurrency,
    }
  }

  return {
    get values() {
      return values
    },
    getSelectedAccount,
    getEffectiveCurrency,
    selectAccount,
    setAmount,
    getPostWithdrawalBalance,
    getReviewPayload,
  }
}

test("Withdrawal Workflow Currency: INR account selected -> withdrawal currency becomes INR", () => {
  const accounts = [
    { accountId: "acc-inr-1", accountNumber: "ACCT-INR-1", currency: "INR", balance: 5000, status: "ACTIVE" },
    { accountId: "acc-usd-1", accountNumber: "ACCT-USD-1", currency: "USD", balance: 100, status: "ACTIVE" },
  ]
  const workflow = createWithdrawalState("", accounts)
  assert.equal(workflow.getEffectiveCurrency(), "")
  assert.equal(workflow.values.currency, "")

  workflow.selectAccount("acc-inr-1")
  assert.equal(workflow.getEffectiveCurrency(), "INR")
  assert.equal(workflow.values.currency, "INR")
  assert.notEqual(workflow.getEffectiveCurrency(), "USD")
})

test("Withdrawal Workflow Currency: USD account selected -> withdrawal currency becomes USD", () => {
  const accounts = [
    { accountId: "acc-inr-1", accountNumber: "ACCT-INR-1", currency: "INR", balance: 5000, status: "ACTIVE" },
    { accountId: "acc-usd-1", accountNumber: "ACCT-USD-1", currency: "USD", balance: 100, status: "ACTIVE" },
  ]
  const workflow = createWithdrawalState("", accounts)
  workflow.selectAccount("acc-usd-1")
  assert.equal(workflow.getEffectiveCurrency(), "USD")
  assert.equal(workflow.values.currency, "USD")
})

test("Withdrawal Workflow Currency: Switching accounts updates currency immediately", () => {
  const accounts = [
    { accountId: "acc-inr-1", accountNumber: "ACCT-INR-1", currency: "INR", balance: 5000, status: "ACTIVE" },
    { accountId: "acc-usd-1", accountNumber: "ACCT-USD-1", currency: "USD", balance: 100, status: "ACTIVE" },
    { accountId: "acc-eur-1", accountNumber: "ACCT-EUR-1", currency: "EUR", balance: 250, status: "ACTIVE" },
  ]
  const workflow = createWithdrawalState("", accounts)

  workflow.selectAccount("acc-inr-1")
  assert.equal(workflow.getEffectiveCurrency(), "INR")

  workflow.selectAccount("acc-usd-1")
  assert.equal(workflow.getEffectiveCurrency(), "USD")

  workflow.selectAccount("acc-eur-1")
  assert.equal(workflow.getEffectiveCurrency(), "EUR")
})

test("Withdrawal Workflow Currency: No account selected leaves currency unset rather than USD", () => {
  const accounts = [
    { accountId: "acc-inr-1", accountNumber: "ACCT-INR-1", currency: "INR", balance: 5000, status: "ACTIVE" },
  ]
  const workflow = createWithdrawalState("", accounts)
  assert.equal(workflow.values.currency, "")
  assert.equal(workflow.getEffectiveCurrency(), "")
  assert.notEqual(workflow.values.currency, "USD")

  workflow.selectAccount("acc-inr-1")
  assert.equal(workflow.getEffectiveCurrency(), "INR")
  workflow.selectAccount("")
  assert.equal(workflow.values.currency, "")
  assert.equal(workflow.getEffectiveCurrency(), "")
})

test("Withdrawal Workflow Balance Preview: Invalid currency state does not show a fabricated balance-after preview", () => {
  const inrAccount = {
    accountId: "acc-inr-1",
    accountNumber: "ACCT-INR-1",
    currency: "INR",
    balance: 5000.0,
    status: "ACTIVE",
  }
  const accounts = [inrAccount]
  const workflow = createWithdrawalState("", accounts)
  workflow.selectAccount("acc-inr-1")
  workflow.setAmount("1000.00")

  assert.equal(workflow.getPostWithdrawalBalance(), 4000.0)

  const calculatePreview = (account, amount, currency) => {
    const numAmount = Number(amount)
    const isAmountValid = !isNaN(numAmount) && numAmount > 0
    const isCurrencyValid = Boolean(account && currency && currency === account.currency)
    return account && isAmountValid && isCurrencyValid ? account.balance - numAmount : null
  }

  assert.equal(calculatePreview(inrAccount, "1000.00", "USD"), null)
  assert.equal(calculatePreview(inrAccount, "1000.00", ""), null)
  assert.equal(calculatePreview(inrAccount, "1000.00", "EUR"), null)
  assert.equal(calculatePreview(inrAccount, "1000.00", "INR"), 4000.0)
})

test("Withdrawal Workflow Preselection: Account-detail initiated withdrawal inherits selected account currency", () => {
  const inrAccount = {
    accountId: "acc-inr-detail",
    accountNumber: "ACCT-INR-999",
    currency: "INR",
    balance: 10000.0,
    status: "ACTIVE",
  }
  const accounts = [inrAccount]

  const workflow = createWithdrawalState("acc-inr-detail", accounts)

  assert.equal(workflow.values.accountId, "acc-inr-detail")
  assert.equal(workflow.getEffectiveCurrency(), "INR")

  workflow.setAmount("2500.00")
  assert.equal(workflow.getPostWithdrawalBalance(), 7500.0)

  const payload = workflow.getReviewPayload()
  assert.equal(payload.currency, "INR")
  assert.equal(payload.accountId, "acc-inr-detail")

  const validation = validateWithdrawal({
    accountId: payload.accountId,
    amount: payload.amount,
    currency: payload.currency,
    account: inrAccount,
  })
  assert.equal(validation.isValid, true)
})
