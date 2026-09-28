import type { Account } from "@/types/account"
import { ApiError } from "@/types/api"

export interface TransferFormValues {
  sourceAccountId: string
  destinationAccountId: string
  amount: string
  currency: string
  description: string
}

export interface TransferValidationResult {
  isValid: boolean
  errors: {
    sourceAccountId?: string
    destinationAccountId?: string
    amount?: string
    currency?: string
    description?: string
    general?: string
  }
}

/**
 * Mask account number for recognition without exposing internal identifiers or full numbers.
 * Example: ACCT-111122223333 -> •••• 3333
 */
export function maskAccountNumber(accountNumber?: string | null): string {
  if (!accountNumber) return "•••• ----"
  const clean = accountNumber.trim()
  if (clean.length <= 4) return `•••• ${clean}`
  return `•••• ${clean.slice(-4)}`
}

/**
 * Parses a decimal string into a scaled BigInt to avoid JavaScript floating-point inaccuracies.
 * Supports up to `scale` fraction digits (default 4 matching backend NUMERIC(19,4)).
 */
export function parseDecimalToScaledBigInt(value: string, scale = 4): bigint | null {
  const trimmed = value.trim()
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

/**
 * Calculates estimated source balance after transfer using decimal-safe BigInt arithmetic.
 */
export function calculateEstimatedBalanceAfter(
  currentBalance: number | string,
  amount: string
): { formatted: string; isNegative: boolean; balanceStr: string } | null {
  const balanceStr = typeof currentBalance === "number" ? currentBalance.toFixed(4) : currentBalance
  const balanceBigInt = parseDecimalToScaledBigInt(balanceStr, 4)
  const amountBigInt = parseDecimalToScaledBigInt(amount, 4)
  if (balanceBigInt === null || amountBigInt === null) return null

  const diff = balanceBigInt - amountBigInt
  const isNegative = diff < BigInt(0)
  const absDiff = isNegative ? -diff : diff
  const whole = absDiff / BigInt(10000)
  const frac = (absDiff % BigInt(10000)).toString().padStart(4, "0").slice(0, 2)
  const formatted = `${isNegative ? "-" : ""}₹${whole.toLocaleString("en-IN")}.${frac}`
  return {
    formatted,
    isNegative,
    balanceStr: `${isNegative ? "-" : ""}${whole}.${frac}`,
  }
}

export function validateTransferForm(
  values: TransferFormValues,
  sourceAccount?: Account | null,
  destinationAccount?: Account | null
): TransferValidationResult {
  const errors: TransferValidationResult["errors"] = {}

  // 1. Source Account
  const cleanSourceId = (values.sourceAccountId || "").trim()
  if (!cleanSourceId) {
    errors.sourceAccountId = "Source account is required."
  } else if (sourceAccount && sourceAccount.status !== "ACTIVE") {
    errors.sourceAccountId = `Selected source account is ${sourceAccount.status.toLowerCase()} and cannot initiate transfers.`
  }

  // 2. Destination Account
  const cleanDestId = (values.destinationAccountId || "").trim()
  if (!cleanDestId) {
    errors.destinationAccountId = "Destination account is required."
  } else if (destinationAccount && destinationAccount.status !== "ACTIVE") {
    errors.destinationAccountId = `Selected destination account is ${destinationAccount.status.toLowerCase()} and cannot receive transfers.`
  }

  // 3. Same Account Check
  if (cleanSourceId && cleanDestId && cleanSourceId === cleanDestId) {
    errors.destinationAccountId = "Source and destination accounts must be different."
  }

  // 4. Amount Validation (Decimal-safe string & BigInt check)
  const rawAmount = (values.amount || "").trim()
  if (!rawAmount) {
    errors.amount = "Transfer amount is required."
  } else if (!/^\d+(\.\d{1,4})?$/.test(rawAmount)) {
    errors.amount = "Please enter a valid numeric amount (maximum 4 decimal places)."
  } else {
    const amountBigInt = parseDecimalToScaledBigInt(rawAmount, 4)
    if (amountBigInt === null || amountBigInt <= BigInt(0)) {
      errors.amount = "Transfer amount must be greater than zero."
    } else if (amountBigInt >= BigInt("10000000000000000000")) {
      // 15 integer digits + 4 fraction digits = 1e19 limit
      errors.amount = "Transfer amount exceeds maximum supported limit."
    } else if (sourceAccount && typeof sourceAccount.balance === "number") {
      const sourceBalanceBigInt = parseDecimalToScaledBigInt(sourceAccount.balance.toFixed(4), 4)
      if (sourceBalanceBigInt !== null && amountBigInt > sourceBalanceBigInt) {
        errors.amount = `Transfer amount exceeds available balance (${sourceAccount.currency} ${sourceAccount.balance.toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}).`
      }
    }
  }

  // 5. Currency Consistency
  const cleanCurrency = (values.currency || "INR").trim().toUpperCase()
  if (!cleanCurrency) {
    errors.currency = "Currency is required."
  } else if (!/^[A-Z]{3}$/.test(cleanCurrency)) {
    errors.currency = "Currency must be a 3-character ISO code."
  } else if (sourceAccount && sourceAccount.currency !== cleanCurrency) {
    errors.currency = `Transfer currency (${cleanCurrency}) does not match source account currency (${sourceAccount.currency}).`
  }

  if (sourceAccount && destinationAccount && sourceAccount.currency !== destinationAccount.currency) {
    errors.currency = `Currency mismatch: source account is ${sourceAccount.currency} but destination account is ${destinationAccount.currency}. Transfers require matching currencies.`
  }

  // 6. Description Limit (Backend max 255 chars)
  if (values.description && values.description.length > 255) {
    errors.description = "Description cannot exceed 255 characters."
  }

  return {
    isValid: Object.keys(errors).length === 0,
    errors,
  }
}

/**
 * Maps API and runtime errors to user-friendly messages without leaking stack traces or internal backend details.
 */
export function getTransferErrorMessage(error: unknown): string {
  if (error instanceof ApiError || (typeof error === "object" && error !== null && "status" in error)) {
    const apiErr = error as ApiError
    const status = apiErr.status
    const rawMessage = apiErr.message || apiErr.error || ""

    // Check for technical leakage
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
      if (lower.includes("user_checking") || lower.includes("ineligible type") || lower.includes("account type")) {
        return "Transfers are only permitted between standard checking accounts."
      }
      if (lower.includes("currency mismatch") || lower.includes("only inr")) {
        return "Transfer failed due to currency mismatch between source and destination accounts."
      }
      if (lower.includes("amount")) {
        return "Transfer amount must be greater than zero."
      }
      if (lower.includes("idempotency-key") || lower.includes("idempotency key")) {
        return "Transfer request is missing a valid idempotency identifier."
      }
      return "Invalid transfer request parameters. Please verify the entered details."
    }

    if (status === 429) {
      return "Too many requests. Please wait a moment before trying again."
    }

    if (status === 0 || apiErr.error === "NetworkError" || rawMessage.toLowerCase().includes("network")) {
      return "Network connection failed. Please check your internet connection and try again."
    }

    if (status >= 500) {
      return "A server error occurred while processing the transfer. Please try again shortly."
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

  return "An unexpected error occurred while processing the transfer. Please try again."
}

/**
 * Determines whether an error is transient (network failure, rate limit, server error),
 * indicating that a retry with the identical idempotency key is safe and appropriate.
 */
export function isTransientError(error: unknown): boolean {
  if (!error) return false
  if (error instanceof ApiError || (typeof error === "object" && error !== null && "status" in error)) {
    const apiErr = error as ApiError
    const status = apiErr.status
    if (status === 0 || apiErr.error === "NetworkError") return true
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
