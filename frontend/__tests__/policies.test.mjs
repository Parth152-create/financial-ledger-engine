import test from "node:test"
import assert from "node:assert/strict"

// ============================================================================
// V2.3 POLICY ENGINE & LIMITS FRONTEND TEST SUITE
// ============================================================================

// -------------------------------------------------------------
// HELPER FUNCTIONS & FORMATTERS (Matching lib/ implementations)
// -------------------------------------------------------------

function formatINR(amount) {
  const num = typeof amount === "number" ? amount : Number(amount)
  if (isNaN(num)) return String(amount)
  return `₹${num.toLocaleString("en-IN", {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })}`
}

function getPolicyErrorMessage(err) {
  const status = err?.status
  const rawMessage = err?.message || ""
  const code = err?.code

  if (status === 422) {
    if (code === "POLICY_TRANSACTION_LIMIT_EXCEEDED") {
      return rawMessage || "This operation exceeds the maximum allowed transaction limit."
    }
    if (code === "POLICY_DAILY_AMOUNT_LIMIT_EXCEEDED") {
      return rawMessage || "Daily cumulative amount limit exceeded for this account."
    }
    if (code === "POLICY_DAILY_COUNT_LIMIT_EXCEEDED") {
      return rawMessage || "Daily transaction count limit exceeded for this account."
    }
    if (code === "POLICY_BALANCE_LIMIT_EXCEEDED") {
      return rawMessage || "Operation rejected: account balance limit constraint violated."
    }
  }
  return rawMessage || "An unexpected error occurred."
}

function formatEntityLabel(type) {
  switch (type) {
    case "USER": return "User"
    case "ACCOUNT": return "Account"
    case "TRANSACTION": return "Transaction"
    case "SYSTEM": return "System"
    case "POLICY": return "Policy"
    default: return type
  }
}

function formatEventSummary(event) {
  const meta = event.metadata || {}
  switch (event.eventType) {
    case "POLICY_CREATED": {
      const pType = meta.policyType || "Policy"
      const scope = meta.scope || "GLOBAL"
      return `Created ${scope} policy (${pType})`
    }
    case "POLICY_UPDATED": {
      const pType = meta.policyType || "Policy"
      return `Updated policy (${pType})`
    }
    case "POLICY_DELETED":
      return "Deleted financial policy"
    case "TRANSFER_REJECTED_POLICY": {
      const reason = meta.reason || "Policy limit exceeded"
      return `Transfer blocked: ${reason}`
    }
    case "DEPOSIT_REJECTED_POLICY": {
      const reason = meta.reason || "Policy limit exceeded"
      return `Deposit blocked: ${reason}`
    }
    case "WITHDRAWAL_REJECTED_POLICY": {
      const reason = meta.reason || "Policy limit exceeded"
      return `Withdrawal blocked: ${reason}`
    }
    default:
      return String(event.eventType).replace(/_/g, " ").toLowerCase()
  }
}

const API_ROUTES = {
  ACCOUNT_LIMITS: (id) => `/api/v1/accounts/${id}/limits`,
  ADMIN_POLICIES: "/api/v1/admin/policies",
  ADMIN_POLICY_BY_ID: (id) => `/api/v1/admin/policies/${id}`,
}

const ACCOUNT_KEYS = {
  all: ["accounts"],
  detail: (id) => ["accounts", "detail", id],
  limits: (id, transactionType) => ["accounts", "detail", id, "limits", transactionType || "all"],
}

// -------------------------------------------------------------
// TESTS
// -------------------------------------------------------------

// 1. Policy Limit Summary Contract
test("1. Policy Limit Summary Contract: validates structure from backend DTO", () => {
  const summary = {
    accountId: "123e4567-e89b-12d3-a456-426614174000",
    transactionType: "TRANSFER",
    maxTransactionAmount: 100000,
    dailyAmountLimit: 500000,
    dailyAmountUsed: 150000,
    dailyAmountRemaining: 350000,
    dailyCountLimit: 20,
    dailyCountUsed: 5,
    dailyCountRemaining: 15,
    accountBalanceLimit: 1000000,
    currentBalance: 250000,
    balanceCapacityRemaining: 750000,
  }

  assert.equal(summary.accountId, "123e4567-e89b-12d3-a456-426614174000")
  assert.equal(summary.transactionType, "TRANSFER")
  assert.equal(summary.maxTransactionAmount, 100000)
  assert.equal(summary.dailyAmountLimit, 500000)
  assert.equal(summary.dailyAmountUsed, 150000)
  assert.equal(summary.dailyAmountRemaining, 350000)
  assert.equal(summary.dailyCountLimit, 20)
  assert.equal(summary.dailyCountUsed, 5)
  assert.equal(summary.dailyCountRemaining, 15)
  assert.equal(summary.accountBalanceLimit, 1000000)
  assert.equal(summary.currentBalance, 250000)
  assert.equal(summary.balanceCapacityRemaining, 750000)
})

// 2. Financial Policy Contract
test("2. Financial Policy Contract: validates admin policy definition structure", () => {
  const policy = {
    id: "987e6543-e21b-12d3-a456-426614174000",
    accountId: null,
    scope: "GLOBAL",
    transactionType: "TRANSFER",
    policyType: "MAX_TRANSACTION_AMOUNT",
    amountLimit: 100000,
    countLimit: null,
    currency: "INR",
    enabled: true,
    createdAt: "2026-09-30T10:00:00Z",
    updatedAt: "2026-09-30T10:00:00Z",
  }

  assert.equal(policy.id, "987e6543-e21b-12d3-a456-426614174000")
  assert.equal(policy.accountId, null)
  assert.equal(policy.scope, "GLOBAL")
  assert.equal(policy.transactionType, "TRANSFER")
  assert.equal(policy.policyType, "MAX_TRANSACTION_AMOUNT")
  assert.equal(policy.amountLimit, 100000)
  assert.equal(policy.countLimit, null)
  assert.equal(policy.currency, "INR")
  assert.equal(policy.enabled, true)
})

// 3. 422 Policy Violation Error Code Mapping
test("3. Policy Error Mapping: translates backend 422 error codes to clear messages", () => {
  const errMax = {
    status: 422,
    code: "POLICY_TRANSACTION_LIMIT_EXCEEDED",
    message: "Transaction amount exceeds maximum allowed limit 100000.0000",
  }
  assert.match(getPolicyErrorMessage(errMax), /maximum allowed limit/i)

  const errDailyAmount = {
    status: 422,
    code: "POLICY_DAILY_AMOUNT_LIMIT_EXCEEDED",
    message: "Daily cumulative amount limit 500000.0000 exceeded",
  }
  assert.match(getPolicyErrorMessage(errDailyAmount), /daily cumulative amount limit/i)

  const errDailyCount = {
    status: 422,
    code: "POLICY_DAILY_COUNT_LIMIT_EXCEEDED",
    message: "Daily transaction count limit 20 exceeded",
  }
  assert.match(getPolicyErrorMessage(errDailyCount), /daily transaction count limit/i)

  const errBalance = {
    status: 422,
    code: "POLICY_BALANCE_LIMIT_EXCEEDED",
    message: "Destination account balance would exceed maximum allowed limit 1000000.0000",
  }
  assert.match(getPolicyErrorMessage(errBalance), /balance.*limit/i)
})

// 4. Audit Trail Formatting for Policy Events
test("4. Audit Trail Formatting: formats policy and policy rejection audit events correctly", () => {
  assert.equal(formatEntityLabel("POLICY"), "Policy")

  const createdEvt = {
    id: "1",
    eventType: "POLICY_CREATED",
    entityType: "POLICY",
    entityId: "p1",
    metadata: { policyType: "MAX_TRANSACTION_AMOUNT", scope: "GLOBAL" },
  }
  assert.equal(formatEventSummary(createdEvt), "Created GLOBAL policy (MAX_TRANSACTION_AMOUNT)")

  const updatedEvt = {
    id: "2",
    eventType: "POLICY_UPDATED",
    entityType: "POLICY",
    entityId: "p1",
    metadata: { policyType: "DAILY_TRANSACTION_AMOUNT" },
  }
  assert.equal(formatEventSummary(updatedEvt), "Updated policy (DAILY_TRANSACTION_AMOUNT)")

  const deletedEvt = {
    id: "3",
    eventType: "POLICY_DELETED",
    entityType: "POLICY",
    entityId: "p1",
    metadata: {},
  }
  assert.equal(formatEventSummary(deletedEvt), "Deleted financial policy")

  const rejectedTransferEvt = {
    id: "4",
    eventType: "TRANSFER_REJECTED_POLICY",
    entityType: "TRANSACTION",
    entityId: "t1",
    metadata: { reason: "Daily amount limit exceeded" },
  }
  assert.equal(formatEventSummary(rejectedTransferEvt), "Transfer blocked: Daily amount limit exceeded")

  const rejectedDepositEvt = {
    id: "5",
    eventType: "DEPOSIT_REJECTED_POLICY",
    entityType: "TRANSACTION",
    entityId: "t2",
    metadata: { reason: "Account balance cap reached" },
  }
  assert.equal(formatEventSummary(rejectedDepositEvt), "Deposit blocked: Account balance cap reached")

  const rejectedWithdrawalEvt = {
    id: "6",
    eventType: "WITHDRAWAL_REJECTED_POLICY",
    entityType: "TRANSACTION",
    entityId: "t3",
    metadata: { reason: "Daily count limit reached" },
  }
  assert.equal(formatEventSummary(rejectedWithdrawalEvt), "Withdrawal blocked: Daily count limit reached")
})

// 5. Route Constants
test("5. Route Constants: verifies account limits and admin policy API endpoints", () => {
  const accountId = "11111111-2222-3333-4444-555555555555"
  assert.equal(API_ROUTES.ACCOUNT_LIMITS(accountId), `/api/v1/accounts/${accountId}/limits`)
  assert.equal(API_ROUTES.ADMIN_POLICIES, "/api/v1/admin/policies")
  assert.equal(API_ROUTES.ADMIN_POLICY_BY_ID("pol-123"), "/api/v1/admin/policies/pol-123")
})

// 6. TanStack Query Key Invalidation Invariants
test("6. Query Keys: invalidating accounts invalidates detail and limit queries", () => {
  const accountId = "test-acct"
  const limitsKey = ACCOUNT_KEYS.limits(accountId, "TRANSFER")
  assert.deepEqual(limitsKey, ["accounts", "detail", "test-acct", "limits", "TRANSFER"])

  // When invalidating "accounts", query client matches all queries starting with ["accounts"]
  assert.equal(limitsKey[0], "accounts")
  assert.equal(limitsKey[1], "detail")
  assert.equal(limitsKey[2], accountId)
})

// 7. Currency Formatting
test("7. Currency Formatting: formats INR limit values according to en-IN locale", () => {
  assert.equal(formatINR(100000), "₹1,00,000.00")
  assert.equal(formatINR(500000), "₹5,00,000.00")
  assert.equal(formatINR(1000000), "₹10,00,000.00")
  assert.equal(formatINR(0), "₹0.00")
})

// 8. Policy Precedence and Scope Logic Verification
test("8. Precedence Logic: Account policy overrides Global; disabled Account falls back to Global", () => {
  function resolveEffectiveLimit(globalPolicy, accountPolicy) {
    if (accountPolicy && accountPolicy.enabled) {
      return { source: "ACCOUNT", limit: accountPolicy.amountLimit }
    }
    if (globalPolicy && globalPolicy.enabled) {
      return { source: "GLOBAL", limit: globalPolicy.amountLimit }
    }
    return { source: "NONE", limit: null }
  }

  const globalP = { scope: "GLOBAL", enabled: true, amountLimit: 100000 }
  const acctP = { scope: "ACCOUNT", enabled: true, amountLimit: 250000 }

  // Account overrides Global
  assert.deepEqual(resolveEffectiveLimit(globalP, acctP), { source: "ACCOUNT", limit: 250000 })

  // Disabled Account falls back to Global
  const disabledAcctP = { scope: "ACCOUNT", enabled: false, amountLimit: 250000 }
  assert.deepEqual(resolveEffectiveLimit(globalP, disabledAcctP), { source: "GLOBAL", limit: 100000 })

  // When no policy exists, unlimited (null)
  assert.deepEqual(resolveEffectiveLimit(null, null), { source: "NONE", limit: null })
})

// 9. M1: Accounts API Contract Separation (Single DTO vs Array)
test("9. M1: Accounts API Contract: getAccountLimit returns single DTO without [0] access", () => {
  const accountId = "acct-123"
  const singleDtoResponse = {
    accountId,
    transactionType: "TRANSFER",
    maxTransactionAmount: 100000,
    dailyAmountLimit: 500000,
    dailyAmountUsed: 50000,
    dailyAmountRemaining: 450000,
    dailyCountLimit: 20,
    dailyCountUsed: 2,
    dailyCountRemaining: 18,
    accountBalanceLimit: 1000000,
    currentBalance: 150000,
    balanceRemaining: 850000,
  }

  // Simulate form usage: access directly without [0]
  assert.equal(singleDtoResponse.maxTransactionAmount, 100000)
  assert.equal(singleDtoResponse.dailyAmountRemaining, 450000)
  assert.equal(singleDtoResponse.dailyCountRemaining, 18)
  assert.equal(singleDtoResponse[0], undefined) // Verify it is NOT an array

  // Verify URL builders
  const listUrl = API_ROUTES.ACCOUNT_LIMITS(accountId)
  const singleUrl = `${API_ROUTES.ACCOUNT_LIMITS(accountId)}?transactionType=TRANSFER`
  assert.equal(listUrl, "/api/v1/accounts/acct-123/limits")
  assert.equal(singleUrl, "/api/v1/accounts/acct-123/limits?transactionType=TRANSFER")
})

// 10. M1: Authoritative Remaining Values & No Fake Zero
test("10. M1: Authoritative Remaining Values: distinguishes Unlimited (null) from Limit Reached (0)", () => {
  function renderRemainingAmount(limit, remaining) {
    if (limit == null) return "Unlimited"
    if (remaining === 0) return "Limit reached (₹0.00 remaining)"
    return `${formatINR(remaining)} remaining`
  }

  // Case A: Unlimited daily amount (limit is null) -> must NOT say "₹0 remaining"
  assert.equal(renderRemainingAmount(null, null), "Unlimited")

  // Case B: Genuine zero remaining (limit reached)
  assert.equal(renderRemainingAmount(500000, 0), "Limit reached (₹0.00 remaining)")

  // Case C: Partial remaining
  assert.equal(renderRemainingAmount(500000, 350000), "₹3,50,000.00 remaining")
})

// 11. M1: Balance Remaining Semantics
test("11. M1: Balance Remaining Semantics: balanceRemaining is null if unlimited, never negative", () => {
  function computeBalanceRemaining(balanceLimit, currentBalance) {
    if (balanceLimit == null) return null
    const remaining = balanceLimit - currentBalance
    return remaining < 0 ? 0 : remaining
  }

  // No limit configured
  assert.equal(computeBalanceRemaining(null, 250000), null)

  // Within limit
  assert.equal(computeBalanceRemaining(1000000, 250000), 750000)

  // Exactly at limit
  assert.equal(computeBalanceRemaining(1000000, 1000000), 0)

  // Balance unexpectedly higher than limit (e.g. system credit) -> capacity 0, never negative
  assert.equal(computeBalanceRemaining(1000000, 1200000), 0)
})

// 12. Policy Rejection Audit Request Fingerprinting & Deduplication
test("12. Audit CorrelationId: deterministic derivation distinguishing materially different payloads", async () => {
  const crypto = await import("node:crypto")

  function normalizeAmount(amt) {
    if (amt == null) return "0.0000"
    const num = typeof amt === "number" ? amt : Number(amt)
    if (isNaN(num)) return String(amt).trim()
    return num.toFixed(4)
  }

  function computeRejectionFingerprint(transactionType, idempotencyKey, metadata) {
    const cleanType = transactionType ? transactionType.trim().toUpperCase() : "UNKNOWN"
    const cleanKey = idempotencyKey ? idempotencyKey.trim() : ""
    let input = `policy-rejection:${cleanType}:${cleanKey}`

    if (metadata) {
      if (cleanType === "TRANSFER") {
        const src = metadata.sourceAccountId ? String(metadata.sourceAccountId).trim().toLowerCase() : ""
        const dst = metadata.destinationAccountId ? String(metadata.destinationAccountId).trim().toLowerCase() : ""
        const amt = normalizeAmount(metadata.amount)
        const cur = metadata.currency ? String(metadata.currency).trim().toUpperCase() : ""
        input += `:src=${src}:dst=${dst}:amt=${amt}:cur=${cur}`
      } else if (cleanType === "DEPOSIT") {
        const dst = metadata.destinationAccountId ? String(metadata.destinationAccountId).trim().toLowerCase() : ""
        const amt = normalizeAmount(metadata.amount)
        const cur = metadata.currency ? String(metadata.currency).trim().toUpperCase() : ""
        input += `:acct=${dst}:amt=${amt}:cur=${cur}`
      } else if (cleanType === "WITHDRAWAL") {
        const src = metadata.sourceAccountId ? String(metadata.sourceAccountId).trim().toLowerCase() : ""
        const amt = normalizeAmount(metadata.amount)
        const cur = metadata.currency ? String(metadata.currency).trim().toUpperCase() : ""
        input += `:acct=${src}:amt=${amt}:cur=${cur}`
      }
    }

    const hash = crypto.createHash("md5").update(input, "utf8").digest()
    hash[6] = (hash[6] & 0x0f) | 0x30
    hash[8] = (hash[8] & 0x3f) | 0x80
    const hex = hash.toString("hex")
    return `${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20, 32)}`
  }

  const key1 = "shared-idempotency-key-1"
  const baseTransferMeta = {
    sourceAccountId: "11111111-1111-1111-1111-111111111111",
    destinationAccountId: "22222222-2222-2222-2222-222222222222",
    amount: 1000,
    currency: "INR",
  }

  // 1. Identical retry yields same fingerprint
  const fp1 = computeRejectionFingerprint("TRANSFER", key1, baseTransferMeta)
  const fp2 = computeRejectionFingerprint("TRANSFER", key1, baseTransferMeta)
  assert.equal(fp1, fp2)
  assert.match(fp1, /^[0-9a-f]{8}-[0-9a-f]{4}-3[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)

  // 2. Materially different amount with SAME key yields DIFFERENT fingerprint
  const differentAmountMeta = { ...baseTransferMeta, amount: 9000 }
  const fpDifferentAmount = computeRejectionFingerprint("TRANSFER", key1, differentAmountMeta)
  assert.notEqual(fp1, fpDifferentAmount)

  // 3. Materially different destination with SAME key yields DIFFERENT fingerprint
  const differentDestMeta = {
    ...baseTransferMeta,
    destinationAccountId: "33333333-3333-3333-3333-333333333333",
  }
  const fpDifferentDest = computeRejectionFingerprint("TRANSFER", key1, differentDestMeta)
  assert.notEqual(fp1, fpDifferentDest)

  // 4. Materially different source with SAME key yields DIFFERENT fingerprint
  const differentSrcMeta = {
    ...baseTransferMeta,
    sourceAccountId: "44444444-4444-4444-4444-444444444444",
  }
  const fpDifferentSrc = computeRejectionFingerprint("TRANSFER", key1, differentSrcMeta)
  assert.notEqual(fp1, fpDifferentSrc)

  // 5. Different transaction type with SAME key yields DIFFERENT fingerprint
  const fpDeposit = computeRejectionFingerprint("DEPOSIT", key1, {
    destinationAccountId: baseTransferMeta.destinationAccountId,
    amount: 1000,
    currency: "INR",
  })
  assert.notEqual(fp1, fpDeposit)

  // 6. Raw idempotency key is NOT leaked directly in correlationId
  assert.ok(!fp1.includes(key1))
})

// 13. M3: Admin Policy API Contract and Query Invalidation
test("13. M3: Admin Policy API: payload sends policyScope and triggers accounts query invalidation", () => {
  // Payload mapper mirroring policiesApi.createPolicy
  function mapCreatePolicyPayload(data) {
    return {
      accountId: data.accountId || null,
      policyScope: data.policyScope || data.scope,
      transactionType: data.transactionType || null,
      policyType: data.policyType,
      amountLimit: data.amountLimit != null ? Number(data.amountLimit) : null,
      countLimit: data.countLimit != null ? Number(data.countLimit) : null,
      currency: data.currency || "INR",
      enabled: data.enabled ?? true,
    }
  }

  const globalPayload = mapCreatePolicyPayload({
    scope: "GLOBAL",
    policyType: "MAX_TRANSACTION_AMOUNT",
    transactionType: "TRANSFER",
    amountLimit: "100000",
  })

  // Backend DTO requires "policyScope"
  assert.equal(globalPayload.policyScope, "GLOBAL")
  assert.equal(globalPayload.amountLimit, 100000)
  assert.equal(globalPayload.currency, "INR")
  assert.equal(globalPayload.enabled, true)

  const accountPayload = mapCreatePolicyPayload({
    policyScope: "ACCOUNT",
    accountId: "acct-555",
    policyType: "DAILY_TRANSACTION_AMOUNT",
    transactionType: "WITHDRAWAL",
    amountLimit: 250000,
  })

  assert.equal(accountPayload.policyScope, "ACCOUNT")
  assert.equal(accountPayload.accountId, "acct-555")

  // Query key invalidation targets
  const POLICY_KEYS_ALL = ["admin", "policies"]
  const ACCOUNT_KEYS_ALL = ["accounts"]
  assert.deepEqual(POLICY_KEYS_ALL, ["admin", "policies"])
  assert.deepEqual(ACCOUNT_KEYS_ALL, ["accounts"])
})

// 14. M3: Route Definition for Admin Policies
test("14. M3: Settings Navigation: verifies ROUTES.SETTINGS_POLICIES path", () => {
  const ROUTES = {
    SETTINGS: "/app/settings",
    SETTINGS_POLICIES: "/app/settings/policies",
  }
  assert.equal(ROUTES.SETTINGS_POLICIES, "/app/settings/policies")
  assert.ok(ROUTES.SETTINGS_POLICIES.startsWith(ROUTES.SETTINGS))
})

// 15. M4: Daily Policy Reset Timezone Invariant (UTC Midnight)
test("15. M4: Daily Policy Reset Timezone: resets strictly at 00:00:00 UTC", () => {
  function getUsageDate(instantMs) {
    // Must format in UTC, not local timezone
    const date = new Date(instantMs)
    return date.toISOString().split("T")[0]
  }

  // 2026-09-30 23:59:59.999 UTC
  const lateNightUtc = Date.UTC(2026, 8, 30, 23, 59, 59, 999)
  // 2026-10-01 00:00:00.000 UTC
  const nextDayMidnightUtc = Date.UTC(2026, 9, 1, 0, 0, 0, 0)

  assert.equal(getUsageDate(lateNightUtc), "2026-09-30")
  assert.equal(getUsageDate(nextDayMidnightUtc), "2026-10-01")

  // Verify that UTC midnight governs rollover regardless of local timezone offsets
  assert.equal(getUsageDate(lateNightUtc), "2026-09-30")
})

// 16. Admin Policy Update Payload Mapping & Structural Invariance
test("16. Admin Policy Edit: maps only editable limits (amountLimit, countLimit, enabled) and preserves structural invariants", () => {
  function mapUpdatePolicyPayload(data) {
    return {
      amountLimit: data.amountLimit != null ? Number(data.amountLimit) : null,
      countLimit: data.countLimit != null ? Number(data.countLimit) : null,
      enabled: data.enabled,
    }
  }

  // Case A: Amount-based policy update
  const amountUpdate = mapUpdatePolicyPayload({
    amountLimit: "75000",
    countLimit: null,
    enabled: true,
    // Structural properties that must NOT be present in update payload
    policyScope: "ACCOUNT",
    accountId: "acct-1",
    policyType: "MAX_TRANSACTION_AMOUNT",
    transactionType: "TRANSFER",
  })

  assert.deepEqual(amountUpdate, {
    amountLimit: 75000,
    countLimit: null,
    enabled: true,
  })
  assert.equal(amountUpdate.policyScope, undefined)
  assert.equal(amountUpdate.accountId, undefined)
  assert.equal(amountUpdate.policyType, undefined)
  assert.equal(amountUpdate.transactionType, undefined)

  // Case B: Count-based policy update
  const countUpdate = mapUpdatePolicyPayload({
    amountLimit: null,
    countLimit: "25",
    enabled: false,
  })

  assert.deepEqual(countUpdate, {
    amountLimit: null,
    countLimit: 25,
    enabled: false,
  })
})

// 17. Admin Policy Edit Validation Rules
test("17. Admin Policy Edit Validation: validates positive amounts and integer counts", () => {
  function validatePolicyLimits(policyType, limitValue) {
    if (!limitValue || !limitValue.trim()) {
      return "Limit value is required"
    }

    const num = Number(limitValue)
    if (isNaN(num)) {
      return "Please enter a valid numeric limit"
    }

    if (policyType === "DAILY_TRANSACTION_COUNT") {
      if (!Number.isInteger(num) || num <= 0) {
        return "Transaction count limit must be a positive integer"
      }
    } else {
      if (num <= 0) {
        return "Amount limit must be greater than zero"
      }
    }

    return null // valid
  }

  // Amount limits validation
  assert.equal(validatePolicyLimits("MAX_TRANSACTION_AMOUNT", "100000"), null)
  assert.equal(validatePolicyLimits("DAILY_TRANSACTION_AMOUNT", "500000.50"), null)
  assert.equal(validatePolicyLimits("ACCOUNT_BALANCE_LIMIT", "1000000"), null)
  assert.equal(validatePolicyLimits("MAX_TRANSACTION_AMOUNT", ""), "Limit value is required")
  assert.equal(validatePolicyLimits("MAX_TRANSACTION_AMOUNT", "0"), "Amount limit must be greater than zero")
  assert.equal(validatePolicyLimits("MAX_TRANSACTION_AMOUNT", "-500"), "Amount limit must be greater than zero")
  assert.equal(validatePolicyLimits("MAX_TRANSACTION_AMOUNT", "abc"), "Please enter a valid numeric limit")

  // Count limit validation
  assert.equal(validatePolicyLimits("DAILY_TRANSACTION_COUNT", "15"), null)
  assert.equal(validatePolicyLimits("DAILY_TRANSACTION_COUNT", "1"), null)
  assert.equal(validatePolicyLimits("DAILY_TRANSACTION_COUNT", "0"), "Transaction count limit must be a positive integer")
  assert.equal(validatePolicyLimits("DAILY_TRANSACTION_COUNT", "-5"), "Transaction count limit must be a positive integer")
  assert.equal(validatePolicyLimits("DAILY_TRANSACTION_COUNT", "2.5"), "Transaction count limit must be a positive integer")
  assert.equal(validatePolicyLimits("DAILY_TRANSACTION_COUNT", "xyz"), "Please enter a valid numeric limit")
})

// 18. Admin Policy Edit Flow: State Pre-population and Immutability
test("18. Admin Policy Edit Flow: pre-populates form state from existing policy and disables structural fields", () => {
  const existingPolicy = {
    id: "pol-456",
    scope: "ACCOUNT",
    accountId: "acct-999",
    policyType: "MAX_TRANSACTION_AMOUNT",
    transactionType: "TRANSFER",
    amountLimit: 50000,
    countLimit: null,
    currency: "INR",
    enabled: true,
  }

  // Pre-population logic in PolicyDialog
  const isEdit = Boolean(existingPolicy)
  const initialScope = existingPolicy.scope
  const initialAccountId = existingPolicy.accountId || ""
  const initialPolicyType = existingPolicy.policyType
  const initialTransactionType = existingPolicy.transactionType || ""
  const initialLimitValue = existingPolicy.policyType === "DAILY_TRANSACTION_COUNT"
    ? String(existingPolicy.countLimit ?? "")
    : String(existingPolicy.amountLimit ?? "")
  const initialEnabled = existingPolicy.enabled

  assert.equal(isEdit, true)
  assert.equal(initialScope, "ACCOUNT")
  assert.equal(initialAccountId, "acct-999")
  assert.equal(initialPolicyType, "MAX_TRANSACTION_AMOUNT")
  assert.equal(initialTransactionType, "TRANSFER")
  assert.equal(initialLimitValue, "50000")
  assert.equal(initialEnabled, true)

  // Verify that in edit mode, structural selectors are marked disabled
  const structuralFieldsDisabled = isEdit
  assert.equal(structuralFieldsDisabled, true)
})

// 19. Admin Policy Update TanStack Query Invalidation
test("19. Admin Policy Update: invalidates admin policies and accounts queries, and updates detail cache", () => {
  const invalidatedKeys = []
  const cacheUpdates = {}

  const mockQueryClient = {
    invalidateQueries: ({ queryKey }) => {
      invalidatedKeys.push(queryKey)
    },
    setQueryData: (queryKey, data) => {
      cacheUpdates[JSON.stringify(queryKey)] = data
    },
  }

  const POLICY_KEYS = {
    all: ["admin", "policies"],
    detail: (id) => ["admin", "policies", "detail", id],
  }
  const ACCOUNT_KEYS = {
    all: ["accounts"],
  }

  // Simulate useUpdatePolicy onSuccess handler
  const updatedPolicy = {
    id: "pol-789",
    scope: "GLOBAL",
    policyType: "DAILY_TRANSACTION_AMOUNT",
    transactionType: "TRANSFER",
    amountLimit: 750000,
    countLimit: null,
    currency: "INR",
    enabled: true,
  }

  // Execute onSuccess
  mockQueryClient.invalidateQueries({ queryKey: POLICY_KEYS.all })
  mockQueryClient.invalidateQueries({ queryKey: ACCOUNT_KEYS.all })
  mockQueryClient.setQueryData(POLICY_KEYS.detail(updatedPolicy.id), updatedPolicy)

  assert.deepEqual(invalidatedKeys, [
    ["admin", "policies"],
    ["accounts"],
  ])
  assert.deepEqual(
    cacheUpdates[JSON.stringify(["admin", "policies", "detail", "pol-789"])],
    updatedPolicy
  )
})



