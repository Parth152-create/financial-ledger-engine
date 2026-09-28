import type { Account } from "@/types/account"
import { ApiError } from "@/types/api"

export const SYSTEM_CLEARING_ID = "00000000-0000-0000-0000-000000000001"

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
 * Resolves account IDs into human-readable ledger labels, avoiding raw system UUID leakage.
 */
export function formatAccountFlowLabel(
  accountId?: string | null,
  currentAccountId?: string,
  accounts?: Account[]
): string {
  if (!accountId) return "Platform Clearing"
  if (accountId === SYSTEM_CLEARING_ID) return "Platform Clearing"

  const matched = accounts?.find((a) => a.accountId === accountId)

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

/**
 * Maps ledger API and runtime errors to user-friendly messages without leaking technical internals.
 */
export function getLedgerErrorMessage(error: unknown): string {
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
      return "Access denied: you do not have permission to view ledger records for this account."
    }

    if (status === 404) {
      return "The requested account could not be found in the ledger."
    }

    if (status === 429) {
      return "Too many requests. Please wait a moment before trying again."
    }

    if (status === 0 || apiErr.error === "NetworkError" || rawMessage.toLowerCase().includes("network")) {
      return "Network connection failed. Please check your internet connection and try again."
    }

    if (status >= 500) {
      return "A server error occurred while retrieving ledger records. Please try again shortly."
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

  return "An unexpected error occurred while loading ledger records. Please try again."
}
