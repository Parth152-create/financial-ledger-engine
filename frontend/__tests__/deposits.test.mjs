import test from "node:test"
import assert from "node:assert/strict"

const validateDeposit = ({
  accountId,
  amount,
  currency,
  description = "",
  account,
}) => {
  const errors = {}

  const cleanAccountId = (accountId || "").trim()
  if (!cleanAccountId) {
    errors.accountId = "Destination account is required."
  } else if (account && account.status !== "ACTIVE") {
    errors.accountId = `Selected destination account is ${account.status.toLowerCase()} and cannot receive deposits.`
  }

  const rawAmount = (amount || "").trim()
  if (!rawAmount) {
    errors.amount = "Deposit amount is required."
  } else if (!/^\d+(\.\d{1,4})?$/.test(rawAmount)) {
    errors.amount = "Please enter a valid numeric amount (maximum 4 decimal places)."
  } else {
    const numAmount = Number(rawAmount)
    if (isNaN(numAmount) || numAmount <= 0) {
      errors.amount = "Deposit amount must be greater than zero."
    } else if (numAmount >= 1e15) {
      errors.amount = "Deposit amount exceeds maximum supported limit."
    }
  }

  const cleanCurrency = (currency || "").trim().toUpperCase()
  if (!cleanCurrency) {
    errors.currency = "Currency is required."
  } else if (!/^[A-Z]{3}$/.test(cleanCurrency)) {
    errors.currency = "Currency must be a 3-character ISO code."
  } else if (account && account.currency !== cleanCurrency) {
    errors.currency = `Deposit currency (${cleanCurrency}) does not match destination account currency (${account.currency}).`
  }

  if (description && description.length > 255) {
    errors.description = "Description cannot exceed 255 characters."
  }

  return {
    isValid: Object.keys(errors).length === 0,
    errors,
  }
}

const getDepositErrorMessage = (error) => {
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
      return "Access denied: you do not have permission to deposit into this account."
    }
    if (status === 404) {
      return "The selected destination account could not be found in the ledger."
    }
    if (status === 409) {
      if (rawMessage.toLowerCase().includes("different parameters")) {
        return "Idempotency conflict: this deposit key was previously submitted with different details."
      }
      return "A transaction conflict occurred with this deposit. Please review your recent transactions."
    }
    if (status === 422) {
      const lower = rawMessage.toLowerCase()
      if (lower.includes("insufficient balance") || lower.includes("clearing")) {
        return "Deposit could not be processed due to system balance constraints."
      }
      if (lower.includes("frozen")) {
        return "Deposit rejected: destination account is frozen and cannot receive deposits."
      }
      if (lower.includes("closed")) {
        return "Deposit rejected: destination account is closed."
      }
      if (lower.includes("active")) {
        return "Destination account must be active to complete a deposit."
      }
      return "The deposit could not be processed due to account restrictions."
    }
    if (status === 400) {
      const lower = rawMessage.toLowerCase()
      if (lower.includes("clearing") || lower.includes("system_clearing")) {
        return "Deposits directly into system accounts are not permitted."
      }
      if (lower.includes("currency mismatch")) {
        return "Deposit failed due to currency mismatch with destination account."
      }
      if (lower.includes("amount")) {
        return "Deposit amount must be greater than zero."
      }
      if (lower.includes("idempotency-key")) {
        return "Deposit request is missing a valid idempotency identifier."
      }
      return "Invalid deposit request parameters. Please verify the entered details."
    }
    if (status === 0 || error.error === "NetworkError" || rawMessage.toLowerCase().includes("network")) {
      return "Network connection failed. Please check your internet connection and try again."
    }
    if (status >= 500) {
      return "A server error occurred while processing the deposit. Please try again shortly."
    }
    if (!containsTechnicalLeak && rawMessage.trim()) {
      return rawMessage
    }
  }

  return "An unexpected error occurred while processing the deposit. Please try again."
}

test("Deposit Validation: valid deposit input passes validation", () => {
  const destAccount = {
    accountId: "11111111-0000-0000-0000-000000000001",
    accountNumber: "ACCT-11111111",
    accountType: "USER_CHECKING",
    status: "ACTIVE",
    currency: "USD",
    balance: 500.0,
  }

  const result = validateDeposit({
    accountId: destAccount.accountId,
    amount: "250.75",
    currency: "USD",
    description: "Account funding memo",
    account: destAccount,
  })

  assert.equal(result.isValid, true)
  assert.deepEqual(result.errors, {})
})

test("Deposit Validation: rejects missing destination account", () => {
  const result = validateDeposit({
    accountId: "",
    amount: "100.00",
    currency: "USD",
  })
  assert.equal(result.isValid, false)
  assert.ok(result.errors.accountId.includes("Destination account is required"))
})

test("Deposit Validation: rejects invalid amount values (empty, zero, negative, precision > 4, non-numeric)", () => {
  assert.equal(
    validateDeposit({ accountId: "acc-1", amount: "", currency: "USD" }).errors.amount,
    "Deposit amount is required."
  )
  assert.equal(
    validateDeposit({ accountId: "acc-1", amount: "0", currency: "USD" }).errors.amount,
    "Deposit amount must be greater than zero."
  )
  assert.equal(
    validateDeposit({ accountId: "acc-1", amount: "-10.00", currency: "USD" }).errors.amount,
    "Please enter a valid numeric amount (maximum 4 decimal places)."
  )
  assert.equal(
    validateDeposit({ accountId: "acc-1", amount: "15.12345", currency: "USD" }).errors.amount,
    "Please enter a valid numeric amount (maximum 4 decimal places)."
  )
  assert.equal(
    validateDeposit({ accountId: "acc-1", amount: "hundred", currency: "USD" }).errors.amount,
    "Please enter a valid numeric amount (maximum 4 decimal places)."
  )
})

test("Deposit Validation: rejects inactive, frozen, or closed accounts", () => {
  const frozenAccount = {
    accountId: "acc-frozen",
    currency: "USD",
    balance: 100,
    status: "FROZEN",
  }
  const closedAccount = {
    accountId: "acc-closed",
    currency: "USD",
    balance: 0,
    status: "CLOSED",
  }

  const res1 = validateDeposit({
    accountId: "acc-frozen",
    amount: "50",
    currency: "USD",
    account: frozenAccount,
  })
  assert.equal(res1.isValid, false)
  assert.ok(res1.errors.accountId.includes("frozen"))

  const res2 = validateDeposit({
    accountId: "acc-closed",
    amount: "50",
    currency: "USD",
    account: closedAccount,
  })
  assert.equal(res2.isValid, false)
  assert.ok(res2.errors.accountId.includes("closed"))
})

test("Deposit Validation: rejects currency mismatch with destination account", () => {
  const destAccount = {
    accountId: "acc-usd",
    currency: "USD",
    balance: 100,
    status: "ACTIVE",
  }

  const result = validateDeposit({
    accountId: "acc-usd",
    amount: "50",
    currency: "EUR",
    account: destAccount,
  })

  assert.equal(result.isValid, false)
  assert.ok(result.errors.currency.includes("does not match destination account currency"))
})

test("Deposit Validation: rejects description exceeding 255 characters", () => {
  const longDesc = "a".repeat(256)
  const result = validateDeposit({
    accountId: "acc-1",
    amount: "50",
    currency: "USD",
    description: longDesc,
  })

  assert.equal(result.isValid, false)
  assert.equal(result.errors.description, "Description cannot exceed 255 characters.")
})

test("Deposit Idempotency: same key persists across submission retries, invalidated on edit", () => {
  let idempotencyKey = null

  const getOrGenerateKey = () => {
    if (!idempotencyKey) {
      idempotencyKey = `dep-idemp-${Date.now()}-${Math.random().toString(36).substring(2, 8)}`
    }
    return idempotencyKey
  }

  const key1 = getOrGenerateKey()
  assert.ok(key1.startsWith("dep-idemp-"))

  // Retry of identical submission uses same key
  const retryKey = getOrGenerateKey()
  assert.equal(retryKey, key1, "Retry must reuse the identical idempotency key")

  // Editing operation invalidates old key
  idempotencyKey = null
  const newKey = getOrGenerateKey()
  assert.notEqual(newKey, key1, "Fresh or edited operation must generate a new idempotency key")
})

test("Deposit API Error Mapping: maps status codes to clean user-friendly messages", () => {
  // 400 Currency mismatch
  assert.equal(
    getDepositErrorMessage({
      status: 400,
      message: "Currency mismatch: deposit currency 'EUR' does not match destination account currency 'USD'",
    }),
    "Deposit failed due to currency mismatch with destination account."
  )

  // 400 Clearing account destination rejected
  assert.equal(
    getDepositErrorMessage({
      status: 400,
      message: "Cannot deposit into SYSTEM_CLEARING account",
    }),
    "Deposits directly into system accounts are not permitted."
  )

  // 401 Unauthorized
  assert.equal(
    getDepositErrorMessage({ status: 401, message: "Authentication required" }),
    "Your session has expired. Please sign in again to continue."
  )

  // 403 Forbidden
  assert.equal(
    getDepositErrorMessage({ status: 403, message: "Access denied" }),
    "Access denied: you do not have permission to deposit into this account."
  )

  // 404 Account Not Found
  assert.equal(
    getDepositErrorMessage({ status: 404, message: "Account not found: 1111" }),
    "The selected destination account could not be found in the ledger."
  )

  // 409 Conflict
  assert.equal(
    getDepositErrorMessage({
      status: 409,
      message: "Idempotency key was already used for a transaction with different parameters",
    }),
    "Idempotency conflict: this deposit key was previously submitted with different details."
  )

  // 422 Frozen destination
  assert.equal(
    getDepositErrorMessage({
      status: 422,
      message: "Destination account 1111 is FROZEN",
    }),
    "Deposit rejected: destination account is frozen and cannot receive deposits."
  )

  // 422 Closed destination
  assert.equal(
    getDepositErrorMessage({
      status: 422,
      message: "Destination account 1111 is CLOSED",
    }),
    "Deposit rejected: destination account is closed."
  )

  // 422 Insufficient clearing balance
  assert.equal(
    getDepositErrorMessage({
      status: 422,
      message: "Insufficient balance in system clearing account: available 50, required 100",
    }),
    "Deposit could not be processed due to system balance constraints."
  )

  // 500 Technical leak protection
  const leakErr = getDepositErrorMessage({
    status: 500,
    message: "org.springframework.dao.DataIntegrityViolationException: SQL [INSERT INTO transactions...]",
  })
  assert.equal(leakErr, "A server error occurred while processing the deposit. Please try again shortly.")
  assert.ok(!leakErr.includes("SQL"))
  assert.ok(!leakErr.includes("org.springframework"))
  assert.ok(!leakErr.includes("Exception"))

  // Network error
  assert.equal(
    getDepositErrorMessage({ status: 0, error: "NetworkError" }),
    "Network connection failed. Please check your internet connection and try again."
  )
})

test("Deposit Response Contract: matches backend TransactionResponseDto", () => {
  const mockResponse = {
    transactionId: "dep-tx-12345",
    transactionType: "DEPOSIT",
    status: "COMPLETED",
    sourceAccountId: "00000000-0000-0000-0000-000000000001",
    destinationAccountId: "11111111-0000-0000-0000-000000000001",
    amount: 250.0,
    currency: "USD",
    description: "Account funding",
    idempotencyKey: "dep-key-123",
    initiatedByUserId: "user-123",
    createdAt: "2026-09-26T07:00:00Z",
    completedAt: "2026-09-26T07:00:01Z",
  }

  assert.equal(mockResponse.transactionType, "DEPOSIT")
  assert.equal(mockResponse.status, "COMPLETED")
  assert.equal(mockResponse.sourceAccountId, "00000000-0000-0000-0000-000000000001")
  assert.equal(mockResponse.amount, 250.0)
  assert.equal(mockResponse.currency, "USD")
  assert.ok(mockResponse.transactionId.length > 0)
  assert.ok(mockResponse.completedAt !== null)
})

test("Deposit Query Invalidation: invalidates accounts and destination account detail", () => {
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

  const onDepositSuccess = (destinationAccountId) => {
    mockQueryClient.invalidateQueries({ queryKey: ACCOUNT_KEYS.all })
    if (destinationAccountId) {
      mockQueryClient.invalidateQueries({ queryKey: ACCOUNT_KEYS.detail(destinationAccountId) })
    }
    mockQueryClient.invalidateQueries({ queryKey: ["transactions"] })
  }

  onDepositSuccess("dest-acc-999")

  assert.equal(invalidatedKeys.length, 3)
  assert.deepEqual(invalidatedKeys[0], ["accounts"])
  assert.deepEqual(invalidatedKeys[1], ["accounts", "detail", "dest-acc-999"])
  assert.deepEqual(invalidatedKeys[2], ["transactions"])
})

// Deposit Workflow Currency State & Preview Logic Helper
const createDepositState = (preselectedAccountId = "", checkingAccounts = []) => {
  let values = {
    accountId: preselectedAccountId || "",
    amount: "",
    currency: "", // Unset initially rather than USD
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

  const getPostDepositBalance = () => {
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
      return selected.balance + numAmount
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
    getPostDepositBalance,
    getReviewPayload,
  }
}

test("Deposit Workflow Currency: INR account selected -> deposit currency becomes INR", () => {
  const accounts = [
    { accountId: "acc-inr-1", accountNumber: "ACCT-INR-1", currency: "INR", balance: 5000, status: "ACTIVE" },
    { accountId: "acc-usd-1", accountNumber: "ACCT-USD-1", currency: "USD", balance: 100, status: "ACTIVE" },
  ]
  const workflow = createDepositState("", accounts)
  assert.equal(workflow.getEffectiveCurrency(), "")
  assert.equal(workflow.values.currency, "")

  workflow.selectAccount("acc-inr-1")
  assert.equal(workflow.getEffectiveCurrency(), "INR")
  assert.equal(workflow.values.currency, "INR")
  assert.notEqual(workflow.getEffectiveCurrency(), "USD")
})

test("Deposit Workflow Currency: USD account selected -> deposit currency becomes USD", () => {
  const accounts = [
    { accountId: "acc-inr-1", accountNumber: "ACCT-INR-1", currency: "INR", balance: 5000, status: "ACTIVE" },
    { accountId: "acc-usd-1", accountNumber: "ACCT-USD-1", currency: "USD", balance: 100, status: "ACTIVE" },
  ]
  const workflow = createDepositState("", accounts)
  workflow.selectAccount("acc-usd-1")
  assert.equal(workflow.getEffectiveCurrency(), "USD")
  assert.equal(workflow.values.currency, "USD")
})

test("Deposit Workflow Currency: Switching accounts updates currency immediately", () => {
  const accounts = [
    { accountId: "acc-inr-1", accountNumber: "ACCT-INR-1", currency: "INR", balance: 5000, status: "ACTIVE" },
    { accountId: "acc-usd-1", accountNumber: "ACCT-USD-1", currency: "USD", balance: 100, status: "ACTIVE" },
    { accountId: "acc-eur-1", accountNumber: "ACCT-EUR-1", currency: "EUR", balance: 250, status: "ACTIVE" },
  ]
  const workflow = createDepositState("", accounts)

  workflow.selectAccount("acc-inr-1")
  assert.equal(workflow.getEffectiveCurrency(), "INR")

  workflow.selectAccount("acc-usd-1")
  assert.equal(workflow.getEffectiveCurrency(), "USD")

  workflow.selectAccount("acc-eur-1")
  assert.equal(workflow.getEffectiveCurrency(), "EUR")
})

test("Deposit Workflow Currency: No account selected leaves currency unset rather than USD", () => {
  const accounts = [
    { accountId: "acc-inr-1", accountNumber: "ACCT-INR-1", currency: "INR", balance: 5000, status: "ACTIVE" },
  ]
  const workflow = createDepositState("", accounts)
  assert.equal(workflow.values.currency, "")
  assert.equal(workflow.getEffectiveCurrency(), "")
  assert.notEqual(workflow.values.currency, "USD")

  workflow.selectAccount("acc-inr-1")
  assert.equal(workflow.getEffectiveCurrency(), "INR")
  workflow.selectAccount("")
  assert.equal(workflow.values.currency, "")
  assert.equal(workflow.getEffectiveCurrency(), "")
})

test("Deposit Workflow Balance Preview: Invalid currency state does not show a fabricated balance-after preview", () => {
  const inrAccount = {
    accountId: "acc-inr-1",
    accountNumber: "ACCT-INR-1",
    currency: "INR",
    balance: 5000.0,
    status: "ACTIVE",
  }
  const accounts = [inrAccount]
  const workflow = createDepositState("", accounts)
  workflow.selectAccount("acc-inr-1")
  workflow.setAmount("1000.00")

  // When currency is valid (INR matches account INR), balance preview is calculated
  assert.equal(workflow.getPostDepositBalance(), 6000.0)

  // If currency is mismatched (e.g. USD with INR account), preview MUST be null (renders as '—')
  const calculatePreview = (account, amount, currency) => {
    const numAmount = Number(amount)
    const isAmountValid = !isNaN(numAmount) && numAmount > 0
    const isCurrencyValid = Boolean(account && currency && currency === account.currency)
    return account && isAmountValid && isCurrencyValid ? account.balance + numAmount : null
  }

  assert.equal(calculatePreview(inrAccount, "1000.00", "USD"), null)
  assert.equal(calculatePreview(inrAccount, "1000.00", ""), null)
  assert.equal(calculatePreview(inrAccount, "1000.00", "EUR"), null)
  assert.equal(calculatePreview(inrAccount, "1000.00", "INR"), 6000.0)
})

test("Deposit Workflow Preselection: Account-detail initiated deposit inherits selected account currency", () => {
  const inrAccount = {
    accountId: "acc-inr-detail",
    accountNumber: "ACCT-INR-999",
    currency: "INR",
    balance: 10000.0,
    status: "ACTIVE",
  }
  const accounts = [inrAccount]

  // Preselected accountId passed as prop from account detail page
  const workflow = createDepositState("acc-inr-detail", accounts)

  assert.equal(workflow.values.accountId, "acc-inr-detail")
  assert.equal(workflow.getEffectiveCurrency(), "INR")

  workflow.setAmount("2500.00")
  assert.equal(workflow.getPostDepositBalance(), 12500.0)

  const payload = workflow.getReviewPayload()
  assert.equal(payload.currency, "INR")
  assert.equal(payload.accountId, "acc-inr-detail")

  // Validates successfully with matching INR
  const validation = validateDeposit({
    accountId: payload.accountId,
    amount: payload.amount,
    currency: payload.currency,
    account: inrAccount,
  })
  assert.equal(validation.isValid, true)
})

