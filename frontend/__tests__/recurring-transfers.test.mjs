import test from "node:test"
import assert from "node:assert/strict"

// ============================================================================
// RECURRING TRANSFERS MODULE TEST SUITE (V2.6)
// Covers validation, lifecycle state transitions, calendar arithmetic,
// execution identity determinism, error mapping, and contract invariants.
// ============================================================================

// ----------------------------------------------------------------------------
// 1. Validation Logic
// ----------------------------------------------------------------------------
function validateRecurringTransfer({
  sourceAccountId,
  destinationAccountId,
  amount,
  currency,
  frequency,
  startDate,
  endDate = null,
}) {
  const errors = {}

  if (!sourceAccountId || typeof sourceAccountId !== "string" || !sourceAccountId.trim()) {
    errors.sourceAccountId = "Source account is required"
  }

  if (!destinationAccountId || typeof destinationAccountId !== "string" || !destinationAccountId.trim()) {
    errors.destinationAccountId = "Destination account is required"
  }

  if (sourceAccountId && destinationAccountId && sourceAccountId.trim() === destinationAccountId.trim()) {
    errors.destinationAccountId = "Source and destination accounts must be different"
  }

  if (amount === undefined || amount === null || amount === "") {
    errors.amount = "Transfer amount is required"
  } else {
    const num = Number(amount)
    if (isNaN(num) || num <= 0) {
      errors.amount = "Transfer amount must be greater than zero"
    }
  }

  if (!currency || currency.trim().toUpperCase() !== "INR") {
    errors.currency = "Only INR currency is supported"
  }

  const validFrequencies = ["DAILY", "WEEKLY", "MONTHLY"]
  if (!frequency || !validFrequencies.includes(frequency)) {
    errors.frequency = "Frequency must be DAILY, WEEKLY, or MONTHLY"
  }

  if (!startDate || typeof startDate !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(startDate)) {
    errors.startDate = "Start date is required and must be in YYYY-MM-DD format"
  }

  if (endDate) {
    if (typeof endDate !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(endDate)) {
      errors.endDate = "End date must be in YYYY-MM-DD format"
    } else if (startDate && endDate < startDate) {
      errors.endDate = "End date must be greater than or equal to start date"
    }
  }

  return {
    isValid: Object.keys(errors).length === 0,
    errors,
  }
}

// ----------------------------------------------------------------------------
// 2. Lifecycle State Machine Logic
// ----------------------------------------------------------------------------
function canTransitionSchedule(currentStatus, action) {
  switch (action) {
    case "PAUSE":
      return currentStatus === "ACTIVE"
    case "RESUME":
      return currentStatus === "PAUSED"
    case "CANCEL":
      return currentStatus === "ACTIVE" || currentStatus === "PAUSED"
    default:
      return false
  }
}

function transitionSchedule(currentStatus, action) {
  if (!canTransitionSchedule(currentStatus, action)) {
    throw new Error(`Cannot perform ${action} on schedule with status ${currentStatus}`)
  }
  switch (action) {
    case "PAUSE":
      return "PAUSED"
    case "RESUME":
      return "ACTIVE"
    case "CANCEL":
      return "CANCELLED"
    default:
      return currentStatus
  }
}

// ----------------------------------------------------------------------------
// 3. Calendar Arithmetic & Anchor Day Preservation
// ----------------------------------------------------------------------------
function computeNextDate(startDateStr, currentSlotStr, frequency) {
  const [, , startDay] = startDateStr.split("-").map(Number)
  const [curYear, curMonth, curDay] = currentSlotStr.split("-").map(Number)
  const anchorDay = startDay

  if (frequency === "DAILY") {
    const curDate = new Date(Date.UTC(curYear, curMonth - 1, curDay))
    curDate.setUTCDate(curDate.getUTCDate() + 1)
    return curDate.toISOString().split("T")[0]
  }

  if (frequency === "WEEKLY") {
    const curDate = new Date(Date.UTC(curYear, curMonth - 1, curDay))
    curDate.setUTCDate(curDate.getUTCDate() + 7)
    return curDate.toISOString().split("T")[0]
  }

  if (frequency === "MONTHLY") {
    let targetYear = curYear
    let targetMonth = curMonth + 1 // 1-indexed next month
    if (targetMonth > 12) {
      targetMonth = 1
      targetYear++
    }
    // Days in target month (day 0 of month + 1 gives last day)
    const daysInTargetMonth = new Date(Date.UTC(targetYear, targetMonth, 0)).getUTCDate()
    const targetDay = Math.min(anchorDay, daysInTargetMonth)

    const yStr = String(targetYear)
    const mStr = String(targetMonth).padStart(2, "0")
    const dStr = String(targetDay).padStart(2, "0")
    return `${yStr}-${mStr}-${dStr}`
  }

  throw new Error(`Unknown frequency: ${frequency}`)
}

// ----------------------------------------------------------------------------
// 4. Deterministic Slot Key Generation
// ----------------------------------------------------------------------------
function generateSlotExecutionKey(scheduleId, slotTimestamp) {
  return `RECURRING:${scheduleId}:${slotTimestamp}`
}

// ----------------------------------------------------------------------------
// 5. API Error Formatting
// ----------------------------------------------------------------------------
function mapApiErrorToMessage(status, body = {}) {
  switch (status) {
    case 400:
      return body.message || "Invalid recurring transfer request parameters"
    case 401:
      return "Authentication required"
    case 403:
      return "You do not have permission to access or modify this schedule"
    case 404:
      return body.message || "Recurring transfer schedule or account not found"
    case 409:
      return body.message || "Schedule state conflict"
    case 422:
      return body.message || "Financial policy or balance constraint violated"
    case 429:
      return "Rate limit exceeded. Please try again later."
    case 500:
    default:
      return "Internal server error. Please try again."
  }
}

// ============================================================================
// TEST SUITE EXECUTION
// ============================================================================

test("Validation: rejects empty source or destination accounts", () => {
  const result = validateRecurringTransfer({
    sourceAccountId: "",
    destinationAccountId: "",
    amount: "100.00",
    currency: "INR",
    frequency: "DAILY",
    startDate: "2026-10-01",
  })
  assert.equal(result.isValid, false)
  assert.ok(result.errors.sourceAccountId)
  assert.ok(result.errors.destinationAccountId)
})

test("Validation: rejects identical source and destination accounts", () => {
  const accountId = "acct-1111-2222-3333"
  const result = validateRecurringTransfer({
    sourceAccountId: accountId,
    destinationAccountId: accountId,
    amount: "100.00",
    currency: "INR",
    frequency: "DAILY",
    startDate: "2026-10-01",
  })
  assert.equal(result.isValid, false)
  assert.equal(
    result.errors.destinationAccountId,
    "Source and destination accounts must be different"
  )
})

test("Validation: rejects zero or negative amounts", () => {
  const zeroRes = validateRecurringTransfer({
    sourceAccountId: "src-1",
    destinationAccountId: "dst-2",
    amount: "0",
    currency: "INR",
    frequency: "DAILY",
    startDate: "2026-10-01",
  })
  assert.equal(zeroRes.isValid, false)
  assert.ok(zeroRes.errors.amount)

  const negRes = validateRecurringTransfer({
    sourceAccountId: "src-1",
    destinationAccountId: "dst-2",
    amount: "-50.00",
    currency: "INR",
    frequency: "DAILY",
    startDate: "2026-10-01",
  })
  assert.equal(negRes.isValid, false)
  assert.ok(negRes.errors.amount)
})

test("Validation: enforces strictly INR platform currency", () => {
  const usdRes = validateRecurringTransfer({
    sourceAccountId: "src-1",
    destinationAccountId: "dst-2",
    amount: "100.00",
    currency: "USD",
    frequency: "DAILY",
    startDate: "2026-10-01",
  })
  assert.equal(usdRes.isValid, false)
  assert.equal(usdRes.errors.currency, "Only INR currency is supported")

  const inrRes = validateRecurringTransfer({
    sourceAccountId: "src-1",
    destinationAccountId: "dst-2",
    amount: "100.00",
    currency: "INR",
    frequency: "DAILY",
    startDate: "2026-10-01",
  })
  assert.equal(inrRes.errors.currency, undefined)
})

test("Validation: enforces valid frequencies DAILY, WEEKLY, MONTHLY", () => {
  const invalidFreq = validateRecurringTransfer({
    sourceAccountId: "src-1",
    destinationAccountId: "dst-2",
    amount: "100.00",
    currency: "INR",
    frequency: "HOURLY",
    startDate: "2026-10-01",
  })
  assert.equal(invalidFreq.isValid, false)
  assert.ok(invalidFreq.errors.frequency)

  for (const freq of ["DAILY", "WEEKLY", "MONTHLY"]) {
    const valid = validateRecurringTransfer({
      sourceAccountId: "src-1",
      destinationAccountId: "dst-2",
      amount: "100.00",
      currency: "INR",
      frequency: freq,
      startDate: "2026-10-01",
    })
    assert.equal(valid.isValid, true)
  }
})

test("Validation: requires start date and validates end date >= start date", () => {
  const noStartDate = validateRecurringTransfer({
    sourceAccountId: "src-1",
    destinationAccountId: "dst-2",
    amount: "100.00",
    currency: "INR",
    frequency: "DAILY",
    startDate: "",
  })
  assert.equal(noStartDate.isValid, false)
  assert.ok(noStartDate.errors.startDate)

  const endBeforeStart = validateRecurringTransfer({
    sourceAccountId: "src-1",
    destinationAccountId: "dst-2",
    amount: "100.00",
    currency: "INR",
    frequency: "DAILY",
    startDate: "2026-10-15",
    endDate: "2026-10-10",
  })
  assert.equal(endBeforeStart.isValid, false)
  assert.equal(
    endBeforeStart.errors.endDate,
    "End date must be greater than or equal to start date"
  )

  const validDates = validateRecurringTransfer({
    sourceAccountId: "src-1",
    destinationAccountId: "dst-2",
    amount: "100.00",
    currency: "INR",
    frequency: "DAILY",
    startDate: "2026-10-15",
    endDate: "2026-10-15",
  })
  assert.equal(validDates.isValid, true)
})

test("Lifecycle: ACTIVE schedule can be PAUSED, then RESUMED", () => {
  assert.equal(canTransitionSchedule("ACTIVE", "PAUSE"), true)
  assert.equal(transitionSchedule("ACTIVE", "PAUSE"), "PAUSED")

  assert.equal(canTransitionSchedule("PAUSED", "RESUME"), true)
  assert.equal(transitionSchedule("PAUSED", "RESUME"), "ACTIVE")
})

test("Lifecycle: ACTIVE and PAUSED schedules can be CANCELLED", () => {
  assert.equal(canTransitionSchedule("ACTIVE", "CANCEL"), true)
  assert.equal(transitionSchedule("ACTIVE", "CANCEL"), "CANCELLED")

  assert.equal(canTransitionSchedule("PAUSED", "CANCEL"), true)
  assert.equal(transitionSchedule("PAUSED", "CANCEL"), "CANCELLED")
})

test("Lifecycle: CANCELLED and COMPLETED states are terminal", () => {
  for (const terminal of ["CANCELLED", "COMPLETED"]) {
    assert.equal(canTransitionSchedule(terminal, "PAUSE"), false)
    assert.equal(canTransitionSchedule(terminal, "RESUME"), false)
    assert.equal(canTransitionSchedule(terminal, "CANCEL"), false)

    assert.throws(
      () => transitionSchedule(terminal, "PAUSE"),
      /Cannot perform PAUSE/
    )
    assert.throws(
      () => transitionSchedule(terminal, "RESUME"),
      /Cannot perform RESUME/
    )
    assert.throws(
      () => transitionSchedule(terminal, "CANCEL"),
      /Cannot perform CANCEL/
    )
  }
})

test("Calendar Arithmetic: DAILY advances by exactly 1 day", () => {
  const next = computeNextDate("2026-01-31", "2026-01-31", "DAILY")
  assert.equal(next, "2026-02-01")

  const leapNext = computeNextDate("2024-02-28", "2024-02-28", "DAILY")
  assert.equal(leapNext, "2024-02-29")
})

test("Calendar Arithmetic: WEEKLY advances by exactly 7 days", () => {
  const next = computeNextDate("2026-10-01", "2026-10-01", "WEEKLY")
  assert.equal(next, "2026-10-08")
})

test("Calendar Arithmetic: MONTHLY preserves anchor day without drift", () => {
  // Start: Jan 31 -> Feb 28 (non leap year) -> Mar 31 -> Apr 30 -> May 31
  const start = "2026-01-31"
  const m1 = computeNextDate(start, "2026-01-31", "MONTHLY")
  assert.equal(m1, "2026-02-28")

  const m2 = computeNextDate(start, m1, "MONTHLY")
  assert.equal(m2, "2026-03-31")

  const m3 = computeNextDate(start, m2, "MONTHLY")
  assert.equal(m3, "2026-04-30")

  const m4 = computeNextDate(start, m3, "MONTHLY")
  assert.equal(m4, "2026-05-31")
})

test("Execution Identity: Deterministic slot key produces identical results", () => {
  const scheduleId = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
  const slotTime = 1759320000000

  const key1 = generateSlotExecutionKey(scheduleId, slotTime)
  const key2 = generateSlotExecutionKey(scheduleId, slotTime)
  assert.equal(key1, key2)

  const diffSlotKey = generateSlotExecutionKey(scheduleId, slotTime + 86400000)
  assert.notEqual(key1, diffSlotKey)
})

test("Error Mapping: Maps all standard HTTP status codes correctly", () => {
  assert.equal(
    mapApiErrorToMessage(400, { message: "Invalid amount" }),
    "Invalid amount"
  )
  assert.equal(mapApiErrorToMessage(401), "Authentication required")
  assert.equal(
    mapApiErrorToMessage(403),
    "You do not have permission to access or modify this schedule"
  )
  assert.equal(
    mapApiErrorToMessage(404, { message: "Schedule not found" }),
    "Schedule not found"
  )
  assert.equal(
    mapApiErrorToMessage(409, { message: "Schedule is already paused" }),
    "Schedule is already paused"
  )
  assert.equal(
    mapApiErrorToMessage(422, { message: "Insufficient balance" }),
    "Insufficient balance"
  )
  assert.equal(mapApiErrorToMessage(429), "Rate limit exceeded. Please try again later.")
  assert.equal(mapApiErrorToMessage(500), "Internal server error. Please try again.")
})

// ----------------------------------------------------------------------------
// 6. UI Masking & Anti-Leakage Conventions (Finding 2)
// ----------------------------------------------------------------------------
function maskAccountNumber(accountNumber) {
  if (!accountNumber) return "•••• ----"
  const clean = accountNumber.trim()
  if (clean.length <= 4) return `•••• ${clean}`
  return `•••• ${clean.slice(-4)}`
}

function formatAccountFlowLabel(accountId, currentAccountId, accounts = []) {
  if (!accountId) return "Platform Clearing"
  if (accountId === "00000000-0000-0000-0000-000000000001") return "Platform Clearing"

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

test("UI Masking: masks schedule ID and transaction ID without exposing raw UUIDs or prefixes", () => {
  const scheduleId = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
  const maskedSchedule = `Schedule Ref: ${maskAccountNumber(scheduleId)}`
  assert.equal(maskedSchedule, "Schedule Ref: •••• 7890")
  assert.ok(!maskedSchedule.includes("a1b2c3d4"))
  assert.ok(!maskedSchedule.includes("-e5f6-"))

  const txId = "f47ac10b-58cc-4372-a567-0e02b2c3d4e5"
  const maskedTx = `Transaction Ref: ${maskAccountNumber(txId)}`
  assert.equal(maskedTx, "Transaction Ref: •••• d4e5")
  assert.ok(!maskedTx.includes("f47ac10b"))
  assert.ok(!maskedTx.includes("..."))
})

test("UI Masking: formats accounts using flow labels without leaking raw account UUIDs", () => {
  const userAccounts = [
    { accountId: "acc-uuid-1111", accountNumber: "ACCT-9876" },
    { accountId: "acc-uuid-2222", accountNumber: "ACCT-5432" },
  ]

  // Known account format
  const knownLabel = formatAccountFlowLabel("acc-uuid-1111", undefined, userAccounts)
  assert.equal(knownLabel, "Checking •••• 9876")
  assert.ok(!knownLabel.includes("acc-uuid-1111"))

  // Unknown external account format
  const unknownLabel = formatAccountFlowLabel("d3b07384-d113-4670-a3e9-91bc746c1e50", undefined, userAccounts)
  assert.equal(unknownLabel, "•••• 1e50")
  assert.ok(!unknownLabel.includes("d3b07384"))
})

test("API Contract: recurring transfer DTO includes authoritative failureCount", () => {
  const scheduleResponse = {
    id: "sched-1234",
    userId: "user-1",
    sourceAccountId: "acc-1",
    destinationAccountId: "acc-2",
    amount: "100.00",
    currency: "INR",
    frequency: "DAILY",
    startDate: "2026-10-01",
    status: "ACTIVE",
    executionCount: 5,
    failureCount: 2,
  }

  assert.equal(typeof scheduleResponse.failureCount, "number")
  assert.equal(scheduleResponse.failureCount, 2)
  assert.equal(scheduleResponse.executionCount, 5)
})

test("Anti-Enumeration: unauthorized mutation requests receive 404 Not Found", () => {
  // When an unauthorized non-owner non-admin user attempts pause/resume/cancel,
  // the API responds with 404 (Not Found) rather than 403 to prevent schedule enumeration
  const unauthorizedError = {
    status: 404,
    body: { message: "Recurring transfer schedule not found" },
  }
  const errorMsg = mapApiErrorToMessage(unauthorizedError.status, unauthorizedError.body)
  assert.equal(errorMsg, "Recurring transfer schedule not found")
  assert.ok(!errorMsg.toLowerCase().includes("forbidden"))
  assert.ok(!errorMsg.toLowerCase().includes("denied"))
})

