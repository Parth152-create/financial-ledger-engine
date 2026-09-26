import test from "node:test"
import assert from "node:assert/strict"

const validateWithdrawal = ({
  accountId,
  amount,
  currency,
  description = "",
  account,
}) => {
  const errors = {}

  const cleanAccountId = (accountId || "").trim()
  if (!cleanAccountId) {
    errors.accountId = "Source account is required."
  } else if (account && account.status !== "ACTIVE") {
    errors.accountId = `Selected source account is ${account.status.toLowerCase()} and cannot process withdrawals.`
  }

  const rawAmount = (amount || "").trim()
  if (!rawAmount) {
    errors.amount = "Withdrawal amount is required."
  } else if (!/^\d+(\.\d{1,4})?$/.test(rawAmount)) {
    errors.amount = "Please enter a valid numeric amount (maximum 4 decimal places)."
  } else {
    const numAmount = Number(rawAmount)
    if (isNaN(numAmount) || numAmount <= 0) {
      errors.amount = "Withdrawal amount must be greater than zero."
    } else if (numAmount >= 1e15) {
      errors.amount = "Withdrawal amount exceeds maximum supported limit."
    } else if (account && typeof account.balance === "number" && numAmount > account.balance) {
      errors.amount = `Withdrawal amount exceeds available balance (${account.currency} ${account.balance.toFixed(2)}).`
    }
  }

  const cleanCurrency = (currency || "").trim().toUpperCase()
  if (!cleanCurrency) {
    errors.currency = "Currency is required."
  } else if (!/^[A-Z]{3}$/.test(cleanCurrency)) {
    errors.currency = "Currency must be a 3-character ISO code."
  } else if (account && account.currency !== cleanCurrency) {
    errors.currency = `Withdrawal currency (${cleanCurrency}) does not match source account currency (${account.currency}).`
  }

  if (description && description.length > 255) {
    errors.description = "Description cannot exceed 255 characters."
  }

  return {
    isValid: Object.keys(errors).length === 0,
    errors,
  }
}

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
      rawMessage.includes("Hibernate")

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
      if (lower.includes("currency mismatch")) {
        return "Withdrawal failed due to currency mismatch with source account."
      }
      if (lower.includes("amount")) {
        return "Withdrawal amount must be greater than zero."
      }
      if (lower.includes("idempotency-key")) {
        return "Withdrawal request is missing a valid idempotency identifier."
      }
      return "Invalid withdrawal request parameters. Please verify the entered details."
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

  return "An unexpected error occurred while processing the withdrawal. Please try again."
}

test("Withdrawal Validation: valid withdrawal input passes validation", () => {
  const sourceAccount = {
    accountId: "22222222-0000-0000-0000-000000000001",
    accountNumber: "ACCT-22222222",
    accountType: "USER_CHECKING",
    status: "ACTIVE",
    currency: "USD",
    balance: 500.0,
  }

  const result = validateWithdrawal({
    accountId: sourceAccount.accountId,
    amount: "150.00",
    currency: "USD",
    description: "ATM withdrawal memo",
    account: sourceAccount,
  })

  assert.equal(result.isValid, true)
  assert.deepEqual(result.errors, {})
})

test("Withdrawal Validation: rejects missing source account", () => {
  const result = validateWithdrawal({
    accountId: "",
    amount: "50.00",
    currency: "USD",
  })
  assert.equal(result.isValid, false)
  assert.ok(result.errors.accountId.includes("Source account is required"))
})

test("Withdrawal Validation: rejects invalid amount values (empty, zero, negative, precision > 4, non-numeric)", () => {
  assert.equal(
    validateWithdrawal({ accountId: "acc-1", amount: "", currency: "USD" }).errors.amount,
    "Withdrawal amount is required."
  )
  assert.equal(
    validateWithdrawal({ accountId: "acc-1", amount: "0", currency: "USD" }).errors.amount,
    "Withdrawal amount must be greater than zero."
  )
  assert.equal(
    validateWithdrawal({ accountId: "acc-1", amount: "-50.00", currency: "USD" }).errors.amount,
    "Please enter a valid numeric amount (maximum 4 decimal places)."
  )
  assert.equal(
    validateWithdrawal({ accountId: "acc-1", amount: "10.12345", currency: "USD" }).errors.amount,
    "Please enter a valid numeric amount (maximum 4 decimal places)."
  )
  assert.equal(
    validateWithdrawal({ accountId: "acc-1", amount: "abc", currency: "USD" }).errors.amount,
    "Please enter a valid numeric amount (maximum 4 decimal places)."
  )
})

test("Withdrawal Validation: rejects amount exceeding available source account balance", () => {
  const sourceAccount = {
    accountId: "source-1",
    currency: "USD",
    balance: 100.0,
    status: "ACTIVE",
  }

  const result = validateWithdrawal({
    accountId: "source-1",
    amount: "100.01",
    currency: "USD",
    account: sourceAccount,
  })

  assert.equal(result.isValid, false)
  assert.ok(result.errors.amount.includes("exceeds available balance"))
})

test("Withdrawal Validation: rejects inactive, frozen, or closed accounts", () => {
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

  const res1 = validateWithdrawal({
    accountId: "acc-frozen",
    amount: "50",
    currency: "USD",
    account: frozenAccount,
  })
  assert.equal(res1.isValid, false)
  assert.ok(res1.errors.accountId.includes("frozen"))

  const res2 = validateWithdrawal({
    accountId: "acc-closed",
    amount: "50",
    currency: "USD",
    account: closedAccount,
  })
  assert.equal(res2.isValid, false)
  assert.ok(res2.errors.accountId.includes("closed"))
})

test("Withdrawal Validation: rejects currency mismatch with source account", () => {
  const sourceAccount = {
    accountId: "acc-usd",
    currency: "USD",
    balance: 100,
    status: "ACTIVE",
  }

  const result = validateWithdrawal({
    accountId: "acc-usd",
    amount: "50",
    currency: "EUR",
    account: sourceAccount,
  })

  assert.equal(result.isValid, false)
  assert.ok(result.errors.currency.includes("does not match source account currency"))
})

test("Withdrawal Validation: rejects description exceeding 255 characters", () => {
  const longDesc = "a".repeat(256)
  const result = validateWithdrawal({
    accountId: "acc-1",
    amount: "50",
    currency: "USD",
    description: longDesc,
  })

  assert.equal(result.isValid, false)
  assert.equal(result.errors.description, "Description cannot exceed 255 characters.")
})

test("Withdrawal Idempotency: same key persists across submission retries, invalidated on edit", () => {
  let idempotencyKey = null

  const getOrGenerateKey = () => {
    if (!idempotencyKey) {
      idempotencyKey = `wdr-idemp-${Date.now()}-${Math.random().toString(36).substring(2, 8)}`
    }
    return idempotencyKey
  }

  const key1 = getOrGenerateKey()
  assert.ok(key1.startsWith("wdr-idemp-"))

  const retryKey = getOrGenerateKey()
  assert.equal(retryKey, key1, "Retry must reuse identical idempotency key")

  idempotencyKey = null
  const newKey = getOrGenerateKey()
  assert.notEqual(newKey, key1, "New operation must generate new idempotency key")
})

test("Withdrawal API Error Mapping: maps status codes to user-friendly messages", () => {
  // 422 Insufficient Balance
  assert.equal(
    getWithdrawalErrorMessage({
      status: 422,
      message: "Insufficient balance in source account: available 50, required 100",
    }),
    "Insufficient balance in the source account to complete this withdrawal."
  )

  // 422 Frozen Account
  assert.equal(
    getWithdrawalErrorMessage({
      status: 422,
      message: "Source account is FROZEN",
    }),
    "Withdrawal rejected: source account is frozen and cannot process withdrawals."
  )

  // 422 Closed Account
  assert.equal(
    getWithdrawalErrorMessage({
      status: 422,
      message: "Source account is CLOSED",
    }),
    "Withdrawal rejected: source account is closed."
  )

  // 400 Currency Mismatch
  assert.equal(
    getWithdrawalErrorMessage({
      status: 400,
      message: "Currency mismatch: withdrawal currency 'EUR' does not match source account currency 'USD'",
    }),
    "Withdrawal failed due to currency mismatch with source account."
  )

  // 400 System clearing rejected
  assert.equal(
    getWithdrawalErrorMessage({
      status: 400,
      message: "attempted to use SYSTEM_CLEARING account as source",
    }),
    "Withdrawals directly from system accounts are not permitted."
  )

  // 401 Unauthorized
  assert.equal(
    getWithdrawalErrorMessage({ status: 401, message: "Unauthorized" }),
    "Your session has expired. Please sign in again to continue."
  )

  // 403 Forbidden
  assert.equal(
    getWithdrawalErrorMessage({ status: 403, message: "Access denied" }),
    "Access denied: you do not have permission to withdraw funds from this account."
  )

  // 404 Account Not Found
  assert.equal(
    getWithdrawalErrorMessage({ status: 404, message: "Account not found: 2222" }),
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

test("Withdrawal Response Contract: matches backend TransactionResponseDto", () => {
  const mockResponse = {
    transactionId: "wdr-tx-98765",
    transactionType: "WITHDRAWAL",
    status: "COMPLETED",
    sourceAccountId: "22222222-0000-0000-0000-000000000001",
    destinationAccountId: "00000000-0000-0000-0000-000000000001",
    amount: 150.0,
    currency: "USD",
    description: "ATM cash payout",
    idempotencyKey: "wdr-key-987",
    initiatedByUserId: "user-123",
    createdAt: "2026-09-26T07:15:00Z",
    completedAt: "2026-09-26T07:15:01Z",
  }

  assert.equal(mockResponse.transactionType, "WITHDRAWAL")
  assert.equal(mockResponse.status, "COMPLETED")
  assert.equal(mockResponse.destinationAccountId, "00000000-0000-0000-0000-000000000001")
  assert.equal(mockResponse.amount, 150.0)
  assert.equal(mockResponse.currency, "USD")
  assert.ok(mockResponse.transactionId.length > 0)
  assert.ok(mockResponse.completedAt !== null)
})

test("Withdrawal Query Invalidation: invalidates accounts and source account detail", () => {
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

  const onWithdrawalSuccess = (sourceAccountId) => {
    mockQueryClient.invalidateQueries({ queryKey: ACCOUNT_KEYS.all })
    if (sourceAccountId) {
      mockQueryClient.invalidateQueries({ queryKey: ACCOUNT_KEYS.detail(sourceAccountId) })
    }
    mockQueryClient.invalidateQueries({ queryKey: ["transactions"] })
  }

  onWithdrawalSuccess("source-acc-111")

  assert.equal(invalidatedKeys.length, 3)
  assert.deepEqual(invalidatedKeys[0], ["accounts"])
  assert.deepEqual(invalidatedKeys[1], ["accounts", "detail", "source-acc-111"])
  assert.deepEqual(invalidatedKeys[2], ["transactions"])
})

// Withdrawal Workflow Currency State & Preview Logic Helper
const createWithdrawalState = (preselectedAccountId = "", checkingAccounts = []) => {
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

  // When currency is valid (INR matches account INR), balance preview is calculated
  assert.equal(workflow.getPostWithdrawalBalance(), 4000.0)

  // If currency is mismatched (e.g. USD with INR account), preview MUST be null (renders as '—')
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

  // Preselected accountId passed as prop from account detail page
  const workflow = createWithdrawalState("acc-inr-detail", accounts)

  assert.equal(workflow.values.accountId, "acc-inr-detail")
  assert.equal(workflow.getEffectiveCurrency(), "INR")

  workflow.setAmount("2500.00")
  assert.equal(workflow.getPostWithdrawalBalance(), 7500.0)

  const payload = workflow.getReviewPayload()
  assert.equal(payload.currency, "INR")
  assert.equal(payload.accountId, "acc-inr-detail")

  // Validates successfully with matching INR
  const validation = validateWithdrawal({
    accountId: payload.accountId,
    amount: payload.amount,
    currency: payload.currency,
    account: inrAccount,
  })
  assert.equal(validation.isValid, true)
})

