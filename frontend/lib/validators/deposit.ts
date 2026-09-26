import type { Account } from "@/types/account"
import { ApiError } from "@/types/api"

export interface DepositFormValues {
  accountId: string
  amount: string
  currency: string
  description: string
}

export interface DepositValidationResult {
  isValid: boolean
  errors: {
    accountId?: string
    amount?: string
    currency?: string
    description?: string
    general?: string
  }
}

export function validateDepositForm(
  values: DepositFormValues,
  account?: Account | null
): DepositValidationResult {
  const errors: DepositValidationResult["errors"] = {}

  const cleanAccountId = (values.accountId || "").trim()
  if (!cleanAccountId) {
    errors.accountId = "Destination account is required."
  } else if (account && account.status !== "ACTIVE") {
    errors.accountId = `Selected destination account is ${account.status.toLowerCase()} and cannot receive deposits.`
  }

  const rawAmount = (values.amount || "").trim()
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

  const cleanCurrency = (values.currency || "").trim().toUpperCase()
  if (!cleanCurrency) {
    errors.currency = "Currency is required."
  } else if (!/^[A-Z]{3}$/.test(cleanCurrency)) {
    errors.currency = "Currency must be a 3-character ISO code."
  } else if (account && account.currency !== cleanCurrency) {
    errors.currency = `Deposit currency (${cleanCurrency}) does not match destination account currency (${account.currency}).`
  }

  if (values.description && values.description.length > 255) {
    errors.description = "Description cannot exceed 255 characters."
  }

  return {
    isValid: Object.keys(errors).length === 0,
    errors,
  }
}

export function getDepositErrorMessage(error: unknown): string {
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

    if (status === 0 || apiErr.error === "NetworkError" || rawMessage.toLowerCase().includes("network")) {
      return "Network connection failed. Please check your internet connection and try again."
    }

    if (status >= 500) {
      return "A server error occurred while processing the deposit. Please try again shortly."
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

  return "An unexpected error occurred while processing the deposit. Please try again."
}
