import { ApiError } from "@/types/api"
import { formatINR } from "./currency"
import { maskAccountNumber } from "./ledger"

export { maskAccountNumber }

export interface VolumePoint {
  date: string
  volume: number
}

export interface ValuePoint {
  date: string
  value: number
  formattedValue?: string
}

export interface BalancePoint {
  date: string
  balance: number
  formattedBalance?: string
}

export interface SuccessPoint {
  date: string
  rate: number
  completed: number
  total: number
}

export interface CompositionData {
  transfers: number
  deposits: number
  withdrawals: number
  total: number
  transferPct: number
  depositPct: number
  withdrawalPct: number
}

/**
 * Format short date for chart x-axis (e.g. "Sep 24")
 */
export function formatShortDate(isoStr: string): string {
  try {
    const d = new Date(isoStr)
    if (isNaN(d.getTime())) return isoStr
    return d.toLocaleDateString("en-US", { month: "short", day: "numeric" })
  } catch {
    return isoStr
  }
}

/**
 * Decimal-safe addition of numeric amounts to avoid floating point issues.
 * Uses integer scaling to 4 decimal places.
 */
export function safeAddAmounts(a: number | string, b: number | string): number {
  const numA = typeof a === "number" ? a : parseFloat(String(a) || "0")
  const numB = typeof b === "number" ? b : parseFloat(String(b) || "0")
  const centsA = BigInt(Math.round((isNaN(numA) ? 0 : numA) * 10000))
  const centsB = BigInt(Math.round((isNaN(numB) ? 0 : numB) * 10000))
  return Number(centsA + centsB) / 10000
}

/**
 * Groups transactions by date bucket and returns volume data.
 */
export function calculateVolumePoints(
  transactions: Array<{ createdAt: string }>
): VolumePoint[] {
  if (!transactions || transactions.length === 0) return []

  const sorted = [...transactions].sort(
    (a, b) => new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime()
  )

  const map = new Map<string, number>()
  for (const tx of sorted) {
    const dateKey = formatShortDate(tx.createdAt)
    map.set(dateKey, (map.get(dateKey) || 0) + 1)
  }

  return Array.from(map.entries()).map(([date, volume]) => ({
    date,
    volume,
  }))
}

/**
 * Groups transactions by date bucket and sums monetary value in INR.
 */
export function calculateValuePoints(
  transactions: Array<{ createdAt: string; amount: number | string; currency?: string }>,
  targetCurrency: string = "INR"
): ValuePoint[] {
  if (!transactions || transactions.length === 0) return []

  // Currency safety check
  const invalidCurrency = transactions.some(
    (tx) => tx.currency && tx.currency !== targetCurrency
  )
  if (invalidCurrency) {
    throw new Error("Currency mismatch detected without FX conversion mechanism")
  }

  const sorted = [...transactions].sort(
    (a, b) => new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime()
  )

  const map = new Map<string, number>()
  for (const tx of sorted) {
    const dateKey = formatShortDate(tx.createdAt)
    const current = map.get(dateKey) || 0
    map.set(dateKey, safeAddAmounts(current, tx.amount))
  }

  return Array.from(map.entries()).map(([date, value]) => ({
    date,
    value: Math.round(value * 100) / 100,
    formattedValue: formatINR(value),
  }))
}

/**
 * Extracts balance trend points from authoritative statement entries.
 * Returns empty array if no entries exist (triggering Insufficient Data state).
 */
export function extractBalanceTrend(
  entries: Array<{ createdAt: string; balanceAfter: number | string }>
): BalancePoint[] {
  if (!entries || entries.length === 0) return []

  return entries.map((entry) => {
    const balanceNum = typeof entry.balanceAfter === "number" ? entry.balanceAfter : Number(entry.balanceAfter)
    return {
      date: formatShortDate(entry.createdAt),
      balance: balanceNum,
      formattedBalance: formatINR(balanceNum),
    }
  })
}

/**
 * Calculates success rate per date bucket and overall metrics.
 * Protects against division by zero.
 */
export function calculateSuccessRate(
  transactions: Array<{ createdAt: string; status: string }>
): {
  points: SuccessPoint[]
  overallRate: string
  completedCount: number
  failedCount: number
  pendingCount: number
  totalCount: number
} {
  if (!transactions || transactions.length === 0) {
    return {
      points: [],
      overallRate: "0.0",
      completedCount: 0,
      failedCount: 0,
      pendingCount: 0,
      totalCount: 0,
    }
  }

  const sorted = [...transactions].sort(
    (a, b) => new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime()
  )

  const map = new Map<string, { completed: number; failed: number; pending: number; total: number }>()

  let completedCount = 0
  let failedCount = 0
  let pendingCount = 0

  for (const tx of sorted) {
    const dateKey = formatShortDate(tx.createdAt)
    const current = map.get(dateKey) || { completed: 0, failed: 0, pending: 0, total: 0 }

    const isCompleted = tx.status === "COMPLETED"
    const isFailed = tx.status === "FAILED"
    const isPending = tx.status === "PENDING"

    if (isCompleted) completedCount++
    if (isFailed) failedCount++
    if (isPending) pendingCount++

    map.set(dateKey, {
      completed: current.completed + (isCompleted ? 1 : 0),
      failed: current.failed + (isFailed ? 1 : 0),
      pending: current.pending + (isPending ? 1 : 0),
      total: current.total + 1,
    })
  }

  const totalCount = transactions.length
  const overallRate = totalCount > 0 ? ((completedCount / totalCount) * 100).toFixed(1) : "0.0"

  const points: SuccessPoint[] = Array.from(map.entries()).map(([date, stats]) => ({
    date,
    rate: stats.total > 0 ? Math.round((stats.completed / stats.total) * 100) : 0,
    completed: stats.completed,
    total: stats.total,
  }))

  return {
    points,
    overallRate,
    completedCount,
    failedCount,
    pendingCount,
    totalCount,
  }
}

/**
 * Calculates transaction composition breakdown by type (TRANSFER, DEPOSIT, WITHDRAWAL).
 */
export function calculateComposition(
  transactions: Array<{ transactionType: string }>
): CompositionData {
  const total = transactions ? transactions.length : 0
  if (total === 0) {
    return {
      transfers: 0,
      deposits: 0,
      withdrawals: 0,
      total: 0,
      transferPct: 0,
      depositPct: 0,
      withdrawalPct: 0,
    }
  }

  const transfers = transactions.filter((t) => t.transactionType === "TRANSFER").length
  const deposits = transactions.filter((t) => t.transactionType === "DEPOSIT").length
  const withdrawals = transactions.filter((t) => t.transactionType === "WITHDRAWAL").length

  const transferPct = Math.round((transfers / total) * 100)
  const depositPct = Math.round((deposits / total) * 100)
  const withdrawalPct = Math.max(0, 100 - transferPct - depositPct)

  return {
    transfers,
    deposits,
    withdrawals,
    total,
    transferPct,
    depositPct,
    withdrawalPct,
  }
}

/**
 * Maps analytics API and runtime errors to user-friendly messages without leaking technical internals.
 */
export function getAnalyticsErrorMessage(error: unknown): string {
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
      return "Your session has expired. Please sign in again to continue."
    }

    if (status === 403) {
      return "Access denied: you do not have permission to view analytics for this account."
    }

    if (status === 404) {
      return "The requested account could not be found."
    }

    if (status === 429) {
      return "Too many requests. Please wait a moment before refreshing analytics."
    }

    if (
      status === 0 ||
      apiErr.error === "NetworkError" ||
      rawMessage.toLowerCase().includes("network") ||
      rawMessage.toLowerCase().includes("failed to fetch")
    ) {
      return "Network connection failed. Please check your internet connection and try again."
    }

    if (status >= 500 || containsTechnicalLeak) {
      return "A server error occurred while retrieving analytics data. Please try again shortly."
    }

    if (rawMessage.trim()) {
      return rawMessage
    }
  }

  if (error instanceof Error) {
    if (error.message.includes("NetworkError") || error.message.includes("Failed to fetch")) {
      return "Network connection failed. Please check your internet connection and try again."
    }
  }

  return "An unexpected error occurred while loading analytics data. Please try again."
}
