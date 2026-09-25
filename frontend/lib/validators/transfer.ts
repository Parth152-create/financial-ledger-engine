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

  // 4. Amount Validation
  const rawAmount = (values.amount || "").trim()
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
    } else if (sourceAccount && typeof sourceAccount.balance === "number" && numAmount > sourceAccount.balance) {
      errors.amount = `Transfer amount exceeds available balance (${sourceAccount.currency} ${sourceAccount.balance.toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}).`
    }
  }

  // 5. Currency Consistency
  const cleanCurrency = (values.currency || "").trim().toUpperCase()
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

  // 6. Description Limit
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
