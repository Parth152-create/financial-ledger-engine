import type { ReconciliationResult } from "@/types/reconciliation"
import { ApiError } from "@/types/api"
import { maskAccountNumber, SYSTEM_CLEARING_ID } from "./ledger"

export { maskAccountNumber, SYSTEM_CLEARING_ID }

/**
 * Calculates aggregate sums from reconciliation results.
 */
export function calculateReconciliationAggregates(results: ReconciliationResult[]) {
  const totalSnapshot = results.reduce(
    (sum, r) => sum + Number(r.snapshotBalance || 0),
    0
  )
  const totalLedger = results.reduce(
    (sum, r) => sum + Number(r.ledgerBalance || 0),
    0
  )
  const totalDifference = results.reduce(
    (sum, r) => sum + Math.abs(Number(r.difference || 0)),
    0
  )
  const totalCredits = results.reduce(
    (sum, r) => sum + Number(r.totalCredits || 0),
    0
  )
  const totalDebits = results.reduce(
    (sum, r) => sum + Number(r.totalDebits || 0),
    0
  )

  return {
    totalSnapshot,
    totalLedger,
    totalDifference,
    totalCredits,
    totalDebits,
  }
}

/**
 * Maps reconciliation API and runtime errors to user-friendly messages without leaking technical internals.
 */
export function getReconciliationErrorMessage(error: unknown): string {
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
      rawMessage.includes("postgres") ||
      rawMessage.includes("deadlock")

    if (status === 401) {
      return "Authentication required. Please sign in again."
    }

    if (status === 403) {
      return "Unauthorized reconciliation attempt: you do not have permission to audit this account."
    }

    if (status === 404) {
      return "Target account not found."
    }

    if (status === 429) {
      return "Too many requests. Please wait a moment before running reconciliation again."
    }

    if (status === 0 || apiErr.error === "NetworkError" || rawMessage.toLowerCase().includes("network")) {
      return "Network connection failed. Please check your internet connection and try again."
    }

    if (status >= 500 || containsTechnicalLeak) {
      return "An unexpected server error occurred during reconciliation audit. Please try again later."
    }

    if (rawMessage.trim()) {
      return rawMessage
    }
  }

  if (error instanceof Error) {
    const msg = error.message
    if (msg.includes("NetworkError") || msg.includes("Failed to fetch")) {
      return "Network connection failed. Please check your internet connection and try again."
    }
  }

  return "An unexpected error occurred while communicating with the reconciliation service."
}
