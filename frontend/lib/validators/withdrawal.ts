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

export function validateWithdrawalForm(
  values: WithdrawalFormValues,
  account?: Account | null
): WithdrawalValidationResult {
  const errors: WithdrawalValidationResult["errors"] = {}

  const cleanAccountId = (values.accountId || "").trim()
  if (!cleanAccountId) {
    errors.accountId = "Source account is required."
  } else if (account && account.status !== "ACTIVE") {
    errors.accountId = `Selected source account is ${account.status.toLowerCase()} and cannot process withdrawals.`
  }

  const rawAmount = (values.amount || "").trim()
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
      errors.amount = `Withdrawal amount exceeds available balance (${account.currency} ${account.balance.toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}).`
    }
  }

  const cleanCurrency = (values.currency || "").trim().toUpperCase()
  if (!cleanCurrency) {
    errors.currency = "Currency is required."
  } else if (!/^[A-Z]{3}$/.test(cleanCurrency)) {
    errors.currency = "Currency must be a 3-character ISO code."
  } else if (account && account.currency !== cleanCurrency) {
    errors.currency = `Withdrawal currency (${cleanCurrency}) does not match source account currency (${account.currency}).`
  }

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
