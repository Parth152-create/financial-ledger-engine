import type { Account } from "@/types/account"
import { ApiError } from "@/types/api"

export interface WithdrawalFormValues {
  accountId: string
  amount: string
  currency: string
  description: string
}

export interface WithdrawalValidationResult {
  isValid: boolean
  errors: {
    accountId?: string
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
 * Calculates estimated source balance after withdrawal using decimal-safe BigInt arithmetic.
 */
export function calculateEstimatedWithdrawalBalanceAfter(
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

export function validateWithdrawalForm(
  values: WithdrawalFormValues,
  account?: Account | null
): WithdrawalValidationResult {
  const errors: WithdrawalValidationResult["errors"] = {}

  // 1. Source Account
  const cleanAccountId = (values.accountId || "").trim()
  if (!cleanAccountId) {
    errors.accountId = "Source account is required."
  } else if (account && account.status !== "ACTIVE") {
    errors.accountId = `Selected source account is ${account.status.toLowerCase()} and cannot process withdrawals.`
  }

  // 2. Amount Validation (Decimal-safe BigInt check)
  const rawAmount = (values.amount || "").trim()
  if (!rawAmount) {
    errors.amount = "Withdrawal amount is required."
  } else if (!/^\d+(\.\d{1,4})?$/.test(rawAmount)) {
    errors.amount = "Please enter a valid numeric amount (maximum 4 decimal places)."
  } else {
    const amountBigInt = parseDecimalToScaledBigInt(rawAmount, 4)
    if (amountBigInt === null || amountBigInt <= BigInt(0)) {
      errors.amount = "Withdrawal amount must be greater than zero."
    } else if (amountBigInt >= BigInt("10000000000000000000")) {
      // 15 integer digits + 4 fraction digits limit
      errors.amount = "Withdrawal amount exceeds maximum supported limit."
    } else if (account && typeof account.balance === "number") {
      const sourceBalanceBigInt = parseDecimalToScaledBigInt(account.balance.toFixed(4), 4)
      if (sourceBalanceBigInt !== null && amountBigInt > sourceBalanceBigInt) {
        errors.amount = `Withdrawal amount exceeds available balance (${account.currency} ${account.balance.toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}).`
      }
    }
  }

  // 3. Currency Validation
  const cleanCurrency = (values.currency || "INR").trim().toUpperCase()
  if (!cleanCurrency) {
    errors.currency = "Currency is required."
  } else if (!/^[A-Z]{3}$/.test(cleanCurrency)) {
    errors.currency = "Currency must be a 3-character ISO code."
  } else if (account && account.currency !== cleanCurrency) {
    errors.currency = `Withdrawal currency (${cleanCurrency}) does not match source account currency (${account.currency}).`
  }

  // 4. Description Validation (Max 255 chars)
  if (values.description && values.description.length > 255) {
    errors.description = "Description cannot exceed 255 characters."
  }

  return {
    isValid: Object.keys(errors).length === 0,
    errors,
  }
}

export function getWithdrawalErrorMessage(error: unknown): string {
  if (error instanceof ApiError || (typeof error === "object" && error !== null && "status" in error)) {
    const apiErr = error as ApiError
    const status = apiErr.status
    const rawMessage = apiErr.message || apiErr.error || ""

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

    if (status === 0 || apiErr.error === "NetworkError" || rawMessage.toLowerCase().includes("network")) {
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
