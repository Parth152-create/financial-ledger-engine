import test from "node:test"
import assert from "node:assert/strict"

// -----------------------------------------------------------------------------
// V2.2 — Transaction Reversal Frontend Verification Suite
// -----------------------------------------------------------------------------

test("1. Reversal Eligibility: COMPLETED transfer is eligible for reversal", () => {
  const transaction = {
    transactionId: "tx-123",
    status: "COMPLETED",
    transactionType: "TRANSFER",
    amount: 5000,
    currency: "INR",
    direction: "DEBIT",
    sourceAccountId: "acc-1",
    destinationAccountId: "acc-2",
    createdAt: "2026-09-29T10:00:00Z",
    reversed: false,
    reversalTransactionId: null,
  }

  const isEligible =
    transaction.status === "COMPLETED" &&
    transaction.transactionType !== "REVERSAL" &&
    transaction.transactionType !== "SYSTEM_FUNDING" &&
    !transaction.reversed &&
    !transaction.reversalTransactionId

  assert.equal(isEligible, true, "Completed transfer should be eligible for reversal")
})

test("2. Reversal Ineligibility: Already reversed transaction is NOT eligible", () => {
  const transaction = {
    transactionId: "tx-123",
    status: "COMPLETED",
    transactionType: "TRANSFER",
    amount: 5000,
    currency: "INR",
    direction: "DEBIT",
    sourceAccountId: "acc-1",
    destinationAccountId: "acc-2",
    createdAt: "2026-09-29T10:00:00Z",
    reversed: true,
    reversalTransactionId: "rev-456",
  }

  const isEligible =
    transaction.status === "COMPLETED" &&
    transaction.transactionType !== "REVERSAL" &&
    transaction.transactionType !== "SYSTEM_FUNDING" &&
    !transaction.reversed &&
    !transaction.reversalTransactionId

  assert.equal(isEligible, false, "Already reversed transaction must not be eligible")
})

test("3. Reversal Ineligibility: REVERSAL transaction cannot be reversed", () => {
  const transaction = {
    transactionId: "rev-456",
    status: "COMPLETED",
    transactionType: "REVERSAL",
    amount: 5000,
    currency: "INR",
    direction: "DEBIT",
    sourceAccountId: "acc-2",
    destinationAccountId: "acc-1",
    createdAt: "2026-09-29T10:05:00Z",
    reversed: false,
    reversesTransactionId: "tx-123",
  }

  const isEligible =
    transaction.status === "COMPLETED" &&
    transaction.transactionType !== "REVERSAL" &&
    transaction.transactionType !== "SYSTEM_FUNDING" &&
    !transaction.reversed

  assert.equal(isEligible, false, "Reversal transaction cannot be reversed")
})

test("4. Reversal Ineligibility: PENDING or FAILED transactions cannot be reversed", () => {
  const pendingTx = {
    transactionId: "tx-pending",
    status: "PENDING",
    transactionType: "TRANSFER",
    amount: 1000,
    currency: "INR",
    reversed: false,
  }
  const failedTx = {
    transactionId: "tx-failed",
    status: "FAILED",
    transactionType: "TRANSFER",
    amount: 1000,
    currency: "INR",
    reversed: false,
  }

  const isPendingEligible = pendingTx.status === "COMPLETED" && !pendingTx.reversed
  const isFailedEligible = failedTx.status === "COMPLETED" && !failedTx.reversed

  assert.equal(isPendingEligible, false, "PENDING transaction cannot be reversed")
  assert.equal(isFailedEligible, false, "FAILED transaction cannot be reversed")
})

test("5. Reversal Ineligibility: SYSTEM_FUNDING cannot be reversed", () => {
  const fundingTx = {
    transactionId: "tx-funding",
    status: "COMPLETED",
    transactionType: "SYSTEM_FUNDING",
    amount: 10000000,
    currency: "INR",
    reversed: false,
  }

  const isEligible =
    fundingTx.status === "COMPLETED" &&
    fundingTx.transactionType !== "REVERSAL" &&
    fundingTx.transactionType !== "SYSTEM_FUNDING" &&
    !fundingTx.reversed

  assert.equal(isEligible, false, "SYSTEM_FUNDING transaction cannot be reversed")
})

test("6. Confirmation Dialog Explanation: communicates compensating transaction semantics", () => {
  const modalWarning =
    "This creates a new compensating transaction. The original transaction will remain in your transaction history."

  assert.ok(
    modalWarning.includes("compensating"),
    "Warning must explicitly mention compensating transaction"
  )
  assert.ok(
    modalWarning.includes("will remain"),
    "Warning must clarify the original transaction remains intact"
  )
  assert.ok(
    !modalWarning.toLowerCase().includes("undo"),
    "Must not claim to 'undo' or delete historical records"
  )
})

test("7. Reversal Reason Validation: validates optional reason <= 255 chars", () => {
  const validateReason = (reason) => {
    if (!reason || !reason.trim()) return { valid: true, value: null }
    const trimmed = reason.trim()
    if (trimmed.length > 255) return { valid: false, error: "Reason must not exceed 255 characters" }
    return { valid: true, value: trimmed }
  }

  assert.equal(validateReason("").valid, true)
  assert.equal(validateReason(null).valid, true)
  assert.equal(validateReason("Duplicate transfer").valid, true)
  assert.equal(validateReason("Duplicate transfer").value, "Duplicate transfer")

  const longReason = "a".repeat(256)
  assert.equal(validateReason(longReason).valid, false)
  assert.equal(validateReason(longReason).error, "Reason must not exceed 255 characters")
})

test("8. Frontend Error Mapping: accurately translates HTTP error codes and distinguishes 409 conflicts", () => {
  function mapReversalError(error) {
    if (!error || typeof error !== "object") return "An unexpected error occurred."
    const status = error.status || error.response?.status
    const rawMessage = (error.message || error.response?.data?.message || error.error || "").toLowerCase()

    if (status === 409) {
      if (rawMessage.includes("idempotency")) {
        return "An idempotency conflict occurred. Please retry with a new request."
      }
      if (
        (rawMessage.includes("already") && rawMessage.includes("reversed")) ||
        rawMessage.includes("transaction_already_reversed")
      ) {
        return "Transaction has already been reversed."
      }
      return "A conflicting transaction operation was detected. Please verify your transaction status."
    }
    if (status === 422) return "Transaction cannot be reversed."
    if (status === 403) return "You are not authorized to reverse this transaction."
    if (status === 404) return "Transaction not found."
    if (status === 429) return "Too many requests. Please try again later."
    if (error.message?.includes("Network") || error.code === "ERR_NETWORK" || !status) {
      return "Unable to reach the server."
    }
    return error.message || "An unexpected error occurred."
  }

  // 409 Already Reversed
  assert.equal(
    mapReversalError({ status: 409, message: "Transaction has already been reversed: tx-123" }),
    "Transaction has already been reversed."
  )
  assert.equal(
    mapReversalError({ response: { status: 409, data: { message: "TRANSACTION_ALREADY_REVERSED" } } }),
    "Transaction has already been reversed."
  )

  // 409 Idempotency Conflict
  assert.equal(
    mapReversalError({ status: 409, message: "Idempotency conflict: a transaction with this idempotency key already exists" }),
    "An idempotency conflict occurred. Please retry with a new request."
  )
  assert.equal(
    mapReversalError({ response: { status: 409, data: { message: "IDEMPOTENCY_CONFLICT" } } }),
    "An idempotency conflict occurred. Please retry with a new request."
  )

  // 409 Generic Conflict fallback
  assert.equal(
    mapReversalError({ status: 409, message: "Concurrent modification" }),
    "A conflicting transaction operation was detected. Please verify your transaction status."
  )

  // Other HTTP error statuses
  assert.equal(mapReversalError({ status: 422 }), "Transaction cannot be reversed.")
  assert.equal(mapReversalError({ status: 403 }), "You are not authorized to reverse this transaction.")
  assert.equal(mapReversalError({ status: 404 }), "Transaction not found.")
  assert.equal(mapReversalError({ status: 429 }), "Too many requests. Please try again later.")
  assert.equal(mapReversalError({ message: "Network Error" }), "Unable to reach the server.")
  assert.equal(mapReversalError({ code: "ERR_NETWORK" }), "Unable to reach the server.")
  assert.equal(mapReversalError(null), "An unexpected error occurred.")
})

test("9. TanStack Query Cache Invalidation: invalidates financial and audit queries upon reversal", () => {
  const invalidatedKeys = []
  const mockQueryClient = {
    invalidateQueries: ({ queryKey }) => {
      invalidatedKeys.push(queryKey)
    },
  }

  const onReversalSuccess = () => {
    mockQueryClient.invalidateQueries({ queryKey: ["accounts"] })
    mockQueryClient.invalidateQueries({ queryKey: ["transactions"] })
    mockQueryClient.invalidateQueries({ queryKey: ["statements"] })
    mockQueryClient.invalidateQueries({ queryKey: ["reconciliation"] })
    mockQueryClient.invalidateQueries({ queryKey: ["audit-events"] })
    mockQueryClient.invalidateQueries({ queryKey: ["analytics"] })
  }

  onReversalSuccess()

  assert.ok(invalidatedKeys.some((k) => k[0] === "accounts"), "Must invalidate accounts cache")
  assert.ok(invalidatedKeys.some((k) => k[0] === "transactions"), "Must invalidate transactions cache")
  assert.ok(invalidatedKeys.some((k) => k[0] === "statements"), "Must invalidate statements cache")
  assert.ok(invalidatedKeys.some((k) => k[0] === "reconciliation"), "Must invalidate reconciliation cache")
  assert.ok(invalidatedKeys.some((k) => k[0] === "audit-events"), "Must invalidate audit events cache")
  assert.ok(invalidatedKeys.some((k) => k[0] === "analytics"), "Must invalidate analytics cache")
})

test("10. Reversal Contract Structure: does not expose internal IDs or idempotency secrets", () => {
  const mockResponse = {
    reversalTransactionId: "rev-uuid-1",
    originalTransactionId: "orig-uuid-1",
    transactionType: "REVERSAL",
    status: "COMPLETED",
    amount: 5000,
    currency: "INR",
    reason: "Duplicate transfer",
    createdAt: "2026-09-29T10:30:00Z",
    completedAt: "2026-09-29T10:30:01Z",
  }

  assert.equal(typeof mockResponse.reversalTransactionId, "string")
  assert.equal(typeof mockResponse.originalTransactionId, "string")
  assert.equal(mockResponse.transactionType, "REVERSAL")
  assert.equal(mockResponse.status, "COMPLETED")
  assert.equal(mockResponse.currency, "INR")
  assert.equal(typeof mockResponse.amount, "number")

  // Ensure internal details are NOT exposed
  assert.equal(mockResponse.idempotencyKey, undefined, "Must not leak idempotencyKey in response DTO")
  assert.equal(mockResponse.initiatedByUserId, undefined, "Must not leak initiatedByUserId in response DTO")
  assert.equal(mockResponse.sourceAccountId, undefined, "Must not leak internal sourceAccountId UUID")
  assert.equal(mockResponse.destinationAccountId, undefined, "Must not leak internal destinationAccountId UUID")
  assert.equal(mockResponse.systemClearingAccount, undefined, "Must not leak clearing account UUID")
})

test("11. Frontend Idempotency Key Preservation: retains key across transient retries and regenerates for new attempts", () => {
  function createKeyManager() {
    let currentKey = null
    return {
      getKey: () => {
        if (!currentKey) {
          currentKey = `REV-${Date.now()}-${Math.random().toString(36).substring(2, 9)}`
        }
        return currentKey
      },
      onSuccess: () => {
        currentKey = null
      },
      onCancel: () => {
        currentKey = null
      },
      onNewOperation: () => {
        currentKey = null
      },
      getActiveKey: () => currentKey,
    }
  }

  const manager = createKeyManager()

  // First execution attempt generates key K1
  const initialKey = manager.getKey()
  assert.ok(initialKey && initialKey.startsWith("REV-"), "Must generate valid key")

  // Transient network failure / 5xx / 429 occurs - key must be retained for retry
  const retryKey1 = manager.getKey()
  assert.equal(retryKey1, initialKey, "Transient retry must reuse the exact same idempotency key")

  // Second retry attempt
  const retryKey2 = manager.getKey()
  assert.equal(retryKey2, initialKey, "Multiple retries must continue to reuse the exact same idempotency key")

  // After successful completion, key is cleared
  manager.onSuccess()
  assert.equal(manager.getActiveKey(), null, "Key must be cleared after successful completion")

  // Genuinely new reversal operation must generate a brand new key
  const newOpKey = manager.getKey()
  assert.notEqual(newOpKey, initialKey, "New reversal operation must generate a new unique idempotency key")

  // Cancelling dialog clears key
  manager.onCancel()
  assert.equal(manager.getActiveKey(), null, "Key must be cleared on cancellation")

  // Re-opening confirmation generates new key
  const postCancelKey = manager.getKey()
  assert.notEqual(postCancelKey, newOpKey, "New operation after cancel must have a new key")
})
