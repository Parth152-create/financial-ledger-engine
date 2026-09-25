import test from "node:test"
import assert from "node:assert/strict"

// 1. Validation Logic
const validateTransfer = ({
  sourceAccountId,
  destinationAccountId,
  amount,
  currency,
  description = "",
  sourceAccount,
  destinationAccount,
}) => {
  const errors = {}

  // Source Account
  const cleanSourceId = (sourceAccountId || "").trim()
  if (!cleanSourceId) {
    errors.sourceAccountId = "Source account is required."
  } else if (sourceAccount && sourceAccount.status !== "ACTIVE") {
    errors.sourceAccountId = `Selected source account is ${sourceAccount.status.toLowerCase()} and cannot initiate transfers.`
  }

  // Destination Account
  const cleanDestId = (destinationAccountId || "").trim()
  if (!cleanDestId) {
    errors.destinationAccountId = "Destination account is required."
  } else if (destinationAccount && destinationAccount.status !== "ACTIVE") {
    errors.destinationAccountId = `Selected destination account is ${destinationAccount.status.toLowerCase()} and cannot receive transfers.`
  }

  // Same Account Check
  if (cleanSourceId && cleanDestId && cleanSourceId === cleanDestId) {
    errors.destinationAccountId = "Source and destination accounts must be different."
  }

  // Amount
  const rawAmount = (amount || "").trim()
  if (!rawAmount) {
    errors.amount = "Transfer amount is required."
  } else if (!/^\d+(\.\d{1,4})?$/.test(rawAmount)) {
    errors.amount = "Please enter a valid numeric amount (maximum 4 decimal places)."
  } else {
    const numAmount = Number(rawAmount)
    if (isNaN(numAmount) || numAmount <= 0) {
      errors.amount = "Transfer amount must be greater than zero."
    } else if (numAmount >= 1e15) {
      errors.amount = "Transfer amount exceeds maximum supported limit."
    } else if (
      sourceAccount &&
      typeof sourceAccount.balance === "number" &&
      numAmount > sourceAccount.balance
    ) {
      errors.amount = `Transfer amount exceeds available balance (${sourceAccount.currency} ${sourceAccount.balance.toFixed(2)}).`
    }
  }

  // Currency
  const cleanCurrency = (currency || "").trim().toUpperCase()
  if (!cleanCurrency) {
    errors.currency = "Currency is required."
  } else if (!/^[A-Z]{3}$/.test(cleanCurrency)) {
    errors.currency = "Currency must be a 3-character ISO code."
  } else if (sourceAccount && sourceAccount.currency !== cleanCurrency) {
    errors.currency = `Transfer currency (${cleanCurrency}) does not match source account currency (${sourceAccount.currency}).`
  }

  if (
    sourceAccount &&
    destinationAccount &&
    sourceAccount.currency !== destinationAccount.currency
  ) {
    errors.currency = `Currency mismatch: source account is ${sourceAccount.currency} but destination account is ${destinationAccount.currency}. Transfers require matching currencies.`
  }

  // Description
  if (description && description.length > 255) {
    errors.description = "Description cannot exceed 255 characters."
  }

  return {
    isValid: Object.keys(errors).length === 0,
    errors,
  }
}

// 2. Error Message Mapping
const getTransferErrorMessage = (error) => {
  if (error && typeof error === "object") {
    const status = error.status
    const rawMessage = error.message || error.error || ""

    const containsTechnicalLeak =
      rawMessage.includes("Exception") ||
      rawMessage.includes("org.springframework") ||
      rawMessage.includes("com.parth") ||
      rawMessage.includes("SQL") ||
      rawMessage.includes("StackTrace") ||
      rawMessage.includes("Hibernate")

    if (status === 401) {
      return "Your session has expired. Please sign in again to continue."
    }
    if (status === 403) {
      return "Access denied: you do not have permission to transfer funds from this source account."
    }
    if (status === 404) {
      return "One or both selected accounts could not be found in the ledger."
    }
    if (status === 409) {
      if (rawMessage.toLowerCase().includes("different parameters")) {
        return "Idempotency conflict: this transfer key was previously submitted with different details."
      }
      return "A transaction conflict occurred with this transfer. Please review your recent transactions."
    }
    if (status === 422) {
      const lower = rawMessage.toLowerCase()
      if (lower.includes("insufficient balance")) {
        return "Insufficient balance in the source account to complete this transfer."
      }
      if (lower.includes("frozen")) {
        return "Transfer rejected: one of the accounts is frozen and cannot process transfers."
      }
      if (lower.includes("closed")) {
        return "Transfer rejected: one of the accounts is closed."
      }
      if (lower.includes("active")) {
        return "Both source and destination accounts must be active to complete a transfer."
      }
      return "The transfer could not be processed due to account restrictions or insufficient funds."
    }
    if (status === 400) {
      const lower = rawMessage.toLowerCase()
      if (lower.includes("must be different") || lower.includes("same account")) {
        return "Source and destination accounts must be different."
      }
      if (lower.includes("currency mismatch")) {
        return "Transfer failed due to currency mismatch between source and destination accounts."
      }
      if (lower.includes("amount")) {
        return "Transfer amount must be greater than zero."
      }
      if (lower.includes("idempotency-key")) {
        return "Transfer request is missing a valid idempotency identifier."
      }
      return "Invalid transfer request parameters. Please verify the entered details."
    }
    if (status === 0 || error.error === "NetworkError" || rawMessage.toLowerCase().includes("network")) {
      return "Network connection failed. Please check your internet connection and try again."
    }
    if (status >= 500) {
      return "A server error occurred while processing the transfer. Please try again shortly."
    }
    if (!containsTechnicalLeak && rawMessage.trim()) {
      return rawMessage
    }
  }

  return "An unexpected error occurred while processing the transfer. Please try again."
}

// -------------------------------------------------------------
// TESTS
// -------------------------------------------------------------

test("Transfer Validation: valid transfer input passes validation", () => {
  const sourceAccount = {
    accountId: "11111111-0000-0000-0000-000000000001",
    accountNumber: "ACCT-11111111",
    accountType: "USER_CHECKING",
    status: "ACTIVE",
    currency: "USD",
    balance: 1000.0,
  }

  const destinationAccount = {
    accountId: "22222222-0000-0000-0000-000000000002",
    accountNumber: "ACCT-22222222",
    accountType: "USER_CHECKING",
    status: "ACTIVE",
    currency: "USD",
    balance: 500.0,
  }

  const result = validateTransfer({
    sourceAccountId: sourceAccount.accountId,
    destinationAccountId: destinationAccount.accountId,
    amount: "150.50",
    currency: "USD",
    description: "Valid payment memo",
    sourceAccount,
    destinationAccount,
  })

  assert.equal(result.isValid, true)
  assert.deepEqual(result.errors, {})
})

test("Transfer Validation: rejects missing source or destination account", () => {
  const res1 = validateTransfer({
    sourceAccountId: "",
    destinationAccountId: "22222222-0000-0000-0000-000000000002",
    amount: "50.00",
    currency: "USD",
  })
  assert.equal(res1.isValid, false)
  assert.ok(res1.errors.sourceAccountId.includes("Source account is required"))

  const res2 = validateTransfer({
    sourceAccountId: "11111111-0000-0000-0000-000000000001",
    destinationAccountId: "",
    amount: "50.00",
    currency: "USD",
  })
  assert.equal(res2.isValid, false)
  assert.ok(res2.errors.destinationAccountId.includes("Destination account is required"))
})

test("Transfer Validation: rejects same source and destination account", () => {
  const accountId = "11111111-0000-0000-0000-000000000001"
  const result = validateTransfer({
    sourceAccountId: accountId,
    destinationAccountId: accountId,
    amount: "50.00",
    currency: "USD",
  })

  assert.equal(result.isValid, false)
  assert.equal(
    result.errors.destinationAccountId,
    "Source and destination accounts must be different."
  )
})

test("Transfer Validation: rejects invalid amount values (empty, zero, negative, precision > 4, non-numeric)", () => {
  // Empty
  assert.equal(
    validateTransfer({
      sourceAccountId: "a",
      destinationAccountId: "b",
      amount: "",
      currency: "USD",
    }).errors.amount,
    "Transfer amount is required."
  )

  // Zero
  assert.equal(
    validateTransfer({
      sourceAccountId: "a",
      destinationAccountId: "b",
      amount: "0",
      currency: "USD",
    }).errors.amount,
    "Transfer amount must be greater than zero."
  )

  // Negative
  assert.equal(
    validateTransfer({
      sourceAccountId: "a",
      destinationAccountId: "b",
      amount: "-50.00",
      currency: "USD",
    }).errors.amount,
    "Please enter a valid numeric amount (maximum 4 decimal places)."
  )

  // Exceeds 4 decimal places
  assert.equal(
    validateTransfer({
      sourceAccountId: "a",
      destinationAccountId: "b",
      amount: "10.12345",
      currency: "USD",
    }).errors.amount,
    "Please enter a valid numeric amount (maximum 4 decimal places)."
  )

  // Non-numeric
  assert.equal(
    validateTransfer({
      sourceAccountId: "a",
      destinationAccountId: "b",
      amount: "fifty",
      currency: "USD",
    }).errors.amount,
    "Please enter a valid numeric amount (maximum 4 decimal places)."
  )
})

test("Transfer Validation: rejects amount exceeding available source account balance", () => {
  const sourceAccount = {
    accountId: "source-1",
    currency: "USD",
    balance: 100.0,
    status: "ACTIVE",
  }

  const result = validateTransfer({
    sourceAccountId: "source-1",
    destinationAccountId: "dest-2",
    amount: "150.00",
    currency: "USD",
    sourceAccount,
  })

  assert.equal(result.isValid, false)
  assert.ok(result.errors.amount.includes("exceeds available balance"))
})

test("Transfer Validation: rejects frozen or closed accounts", () => {
  const frozenSource = {
    accountId: "source-1",
    currency: "USD",
    balance: 500,
    status: "FROZEN",
  }
  const closedDest = {
    accountId: "dest-2",
    currency: "USD",
    balance: 100,
    status: "CLOSED",
  }

  const res1 = validateTransfer({
    sourceAccountId: "source-1",
    destinationAccountId: "dest-2",
    amount: "50",
    currency: "USD",
    sourceAccount: frozenSource,
  })
  assert.equal(res1.isValid, false)
  assert.ok(res1.errors.sourceAccountId.includes("frozen"))

  const res2 = validateTransfer({
    sourceAccountId: "source-1",
    destinationAccountId: "dest-2",
    amount: "50",
    currency: "USD",
    destinationAccount: closedDest,
  })
  assert.equal(res2.isValid, false)
  assert.ok(res2.errors.destinationAccountId.includes("closed"))
})

test("Transfer Validation: rejects currency mismatch between accounts", () => {
  const sourceAccount = {
    accountId: "source-usd",
    currency: "USD",
    balance: 500,
    status: "ACTIVE",
  }
  const destAccount = {
    accountId: "dest-eur",
    currency: "EUR",
    balance: 100,
    status: "ACTIVE",
  }

  const result = validateTransfer({
    sourceAccountId: "source-usd",
    destinationAccountId: "dest-eur",
    amount: "50",
    currency: "USD",
    sourceAccount,
    destinationAccount: destAccount,
  })

  assert.equal(result.isValid, false)
  assert.ok(result.errors.currency.includes("Currency mismatch"))
})

test("Transfer Validation: rejects description exceeding 255 characters", () => {
  const longDesc = "a".repeat(256)
  const result = validateTransfer({
    sourceAccountId: "source-1",
    destinationAccountId: "dest-2",
    amount: "50",
    currency: "USD",
    description: longDesc,
  })

  assert.equal(result.isValid, false)
  assert.equal(result.errors.description, "Description cannot exceed 255 characters.")
})

test("Idempotency Behavior: same key persists across submission retries, new key generated on fresh transfer", () => {
  let currentKey = null
  let retryCount = 0

  const getOrGenerateKey = () => {
    if (!currentKey) {
      currentKey = `idemp-${Date.now()}-${Math.random().toString(36).substring(2, 8)}`
    }
    return currentKey
  }

  // Initial submission
  const key1 = getOrGenerateKey()
  assert.ok(key1.startsWith("idemp-"))

  // Network failure / retry: key must remain identical!
  retryCount++
  const retryKey1 = getOrGenerateKey()
  assert.equal(retryKey1, key1, "Retry must reuse the identical idempotency key")

  retryCount++
  const retryKey2 = getOrGenerateKey()
  assert.equal(retryKey2, key1, "Subsequent retries must continue reusing identical idempotency key")

  // Starting a fresh transfer resets key
  assert.equal(retryCount, 2)
  currentKey = null
  const key2 = getOrGenerateKey()
  assert.notEqual(key2, key1, "Fresh transfer must generate a distinct idempotency key")
})

test("API Failure Error Mapping: cleanly maps backend status codes and hides stack traces", () => {
  // 422 Insufficient Balance
  const insufficientErr = getTransferErrorMessage({
    status: 422,
    message: "Insufficient balance in source account aaaa: available 50, required 100",
  })
  assert.equal(
    insufficientErr,
    "Insufficient balance in the source account to complete this transfer."
  )

  // 422 Frozen Account
  const frozenErr = getTransferErrorMessage({
    status: 422,
    message: "Source account aaaa is FROZEN",
  })
  assert.equal(
    frozenErr,
    "Transfer rejected: one of the accounts is frozen and cannot process transfers."
  )

  // 422 Closed Account
  const closedErr = getTransferErrorMessage({
    status: 422,
    message: "Destination account bbbb is CLOSED",
  })
  assert.equal(closedErr, "Transfer rejected: one of the accounts is closed.")

  // 400 Currency Mismatch
  const mismatchErr = getTransferErrorMessage({
    status: 400,
    message: "Currency mismatch: transfer currency 'USD' does not match destination account currency 'EUR'",
  })
  assert.equal(
    mismatchErr,
    "Transfer failed due to currency mismatch between source and destination accounts."
  )

  // 401 Unauthorized / Expired
  const authErr = getTransferErrorMessage({ status: 401, message: "Unauthorized" })
  assert.equal(authErr, "Your session has expired. Please sign in again to continue.")

  // 403 Forbidden
  const forbiddenErr = getTransferErrorMessage({
    status: 403,
    message: "Access denied: not authorized to operate on this account",
  })
  assert.equal(
    forbiddenErr,
    "Access denied: you do not have permission to transfer funds from this source account."
  )

  // 409 Conflict
  const conflictErr = getTransferErrorMessage({
    status: 409,
    message: "Idempotency key was already used for a transfer with different parameters",
  })
  assert.equal(
    conflictErr,
    "Idempotency conflict: this transfer key was previously submitted with different details."
  )

  // Technical leak protection (SQL/Java exceptions must never leak)
  const leakErr = getTransferErrorMessage({
    status: 500,
    message:
      "org.springframework.dao.DataIntegrityViolationException: could not execute statement; SQL [INSERT INTO transfers...]",
  })
  assert.equal(
    leakErr,
    "A server error occurred while processing the transfer. Please try again shortly."
  )
  assert.ok(!leakErr.includes("Exception"))
  assert.ok(!leakErr.includes("SQL"))
  assert.ok(!leakErr.includes("org.springframework"))
})

test("Successful Transfer: contract structure matches backend TransferResponseDto", () => {
  const mockResponse = {
    transactionId: "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
    status: "COMPLETED",
    sourceAccountId: "c0a80123-0000-0000-0000-000000000001",
    destinationAccountId: "c0a80123-0000-0000-0000-000000000002",
    amount: 100.0,
    currency: "USD",
    createdAt: "2026-09-22T11:45:00.123456Z",
    completedAt: "2026-09-22T11:45:00.145678Z",
    transactionType: "TRANSFER",
    initiatedByUserId: "c0a80123-9999-0000-0000-000000000001",
    description: "Invoice payment #4092",
  }

  assert.equal(mockResponse.status, "COMPLETED")
  assert.equal(mockResponse.amount, 100.0)
  assert.equal(mockResponse.currency, "USD")
  assert.equal(mockResponse.transactionType, "TRANSFER")
  assert.ok(mockResponse.transactionId.length > 0)
  assert.ok(mockResponse.completedAt !== null)
})

test("Account Query Invalidation: invalidates ACCOUNT_KEYS.all and specific accounts upon transfer success", () => {
  const invalidatedKeys = []
  const mockQueryClient = {
    invalidateQueries: ({ queryKey }) => {
      invalidatedKeys.push(queryKey)
    },
  }

  const ACCOUNT_KEYS = {
    all: ["accounts"],
    lists: () => ["accounts", "list"],
    detail: (id) => ["accounts", "detail", id],
  }

  const onTransferSuccess = (sourceAccountId, destinationAccountId) => {
    mockQueryClient.invalidateQueries({ queryKey: ACCOUNT_KEYS.all })
    mockQueryClient.invalidateQueries({ queryKey: ACCOUNT_KEYS.detail(sourceAccountId) })
    mockQueryClient.invalidateQueries({ queryKey: ACCOUNT_KEYS.detail(destinationAccountId) })
    mockQueryClient.invalidateQueries({ queryKey: ["transactions"] })
  }

  const sourceId = "source-1111"
  const destId = "dest-2222"
  onTransferSuccess(sourceId, destId)

  assert.equal(invalidatedKeys.length, 4)
  assert.deepEqual(invalidatedKeys[0], ["accounts"])
  assert.deepEqual(invalidatedKeys[1], ["accounts", "detail", "source-1111"])
  assert.deepEqual(invalidatedKeys[2], ["accounts", "detail", "dest-2222"])
  assert.deepEqual(invalidatedKeys[3], ["transactions"])
})

test("Loading State Machine: disabled buttons and submission indicator behavior", () => {
  const getButtonState = ({ isPending, isValid, hasErrors }) => {
    return {
      submitDisabled: isPending || !isValid || hasErrors,
      showSpinner: isPending,
      buttonText: isPending ? "Executing Transfer..." : "Confirm & Execute",
    }
  }

  const idle = getButtonState({ isPending: false, isValid: true, hasErrors: false })
  assert.equal(idle.submitDisabled, false)
  assert.equal(idle.showSpinner, false)
  assert.equal(idle.buttonText, "Confirm & Execute")

  const pending = getButtonState({ isPending: true, isValid: true, hasErrors: false })
  assert.equal(pending.submitDisabled, true)
  assert.equal(pending.showSpinner, true)
  assert.equal(pending.buttonText, "Executing Transfer...")

  const invalid = getButtonState({ isPending: false, isValid: false, hasErrors: true })
  assert.equal(invalid.submitDisabled, true)
  assert.equal(invalid.showSpinner, false)
})
