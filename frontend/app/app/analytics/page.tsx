"use client"

import * as React from "react"
import { useSearchParams } from "next/navigation"
import {
  RotateCw,
  AlertCircle,
  Building2,
  Database,
} from "lucide-react"
import { Button } from "@/components/ui/button"
import {
  AnalyticsChartCard,
  type AnalyticsMetric,
} from "@/components/charts/analytics-chart-card"
import { TransactionCompositionCard } from "@/components/analytics/transaction-composition-card"
import { useAccounts } from "@/hooks/api/use-accounts"
import { useAccountTransactions } from "@/hooks/api/use-transactions"
import { useAccountStatement } from "@/hooks/api/use-statements"
import {
  calculateVolumePoints,
  calculateValuePoints,
  extractBalanceTrend,
  calculateSuccessRate,
  calculateComposition,
  safeAddAmounts,
  getAnalyticsErrorMessage,
  maskAccountNumber,
} from "@/lib/formatters/analytics"
import { cn } from "@/lib/utils"

type TimeRange = "24h" | "7d" | "30d" | "retrieved"

function AnalyticsSkeleton() {
  return (
    <div className="space-y-6 select-none font-sans animate-pulse">
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 pb-3 border-b border-border/70">
        <div className="space-y-1.5">
          <div className="h-7 w-36 bg-muted rounded-xs" />
          <div className="h-4 w-64 bg-muted/60 rounded-xs" />
        </div>
        <div className="flex items-center gap-2">
          <div className="h-8 w-44 bg-muted rounded-xs" />
          <div className="h-8 w-60 bg-muted/70 rounded-xs" />
          <div className="h-8 w-8 bg-muted rounded-xs" />
        </div>
      </div>
      <div className="h-96 border border-border/70 rounded-sm bg-card" />
      <div className="h-64 border border-border/70 rounded-sm bg-card" />
    </div>
  )
}

function AnalyticsContent() {
  const searchParams = useSearchParams()
  const paramAccountId = searchParams.get("accountId")

  const {
    data: accounts,
    isLoading: isLoadingAccounts,
    isError: isErrorAccounts,
    error: accountsError,
    refetch: refetchAccounts,
    isFetching: isFetchingAccounts,
  } = useAccounts()

  // Filter strictly to eligible USER_CHECKING accounts
  const checkingAccounts = React.useMemo(() => {
    if (!accounts) return []
    return accounts.filter((acc) => acc.accountType === "USER_CHECKING")
  }, [accounts])

  const [selectedAccountId, setSelectedAccountId] = React.useState<string>("")
  const [timeRange, setTimeRange] = React.useState<TimeRange>("30d")
  const [activeMetric, setActiveMetric] = React.useState<AnalyticsMetric>("volume")

  // Resolve active account ID: query parameter first, then state, then first checking account
  const activeAccountId = React.useMemo(() => {
    if (paramAccountId && checkingAccounts.some((a) => a.accountId === paramAccountId)) {
      return paramAccountId
    }
    if (selectedAccountId && checkingAccounts.some((a) => a.accountId === selectedAccountId)) {
      return selectedAccountId
    }
    return checkingAccounts[0]?.accountId ?? ""
  }, [paramAccountId, selectedAccountId, checkingAccounts])

  const currentAccount = React.useMemo(() => {
    return checkingAccounts.find((a) => a.accountId === activeAccountId)
  }, [checkingAccounts, activeAccountId])

  const currency = currentAccount?.currency || "INR"

  // Derive "from" timestamp based on timeRange
  const fromTimestamp = React.useMemo(() => {
    if (timeRange === "retrieved") return undefined
    const now = new Date()
    if (timeRange === "24h") {
      now.setHours(now.getHours() - 24)
    } else if (timeRange === "7d") {
      now.setDate(now.getDate() - 7)
    } else if (timeRange === "30d") {
      now.setDate(now.getDate() - 30)
    }
    return now.toISOString()
  }, [timeRange])

  // Fetch transactions and statement for the selected account
  const {
    data: txData,
    isLoading: isLoadingTx,
    isError: isErrorTx,
    error: txError,
    refetch: refetchTx,
    isFetching: isFetchingTx,
  } = useAccountTransactions(activeAccountId, {
    from: fromTimestamp,
    size: 100,
  })

  const {
    data: statementData,
    isLoading: isLoadingStatement,
    isError: isErrorStatement,
    error: statementError,
    refetch: refetchStatement,
    isFetching: isFetchingStatement,
  } = useAccountStatement(activeAccountId, {
    from: fromTimestamp,
    size: 100,
  })

  const handleAccountChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    const newId = e.target.value
    setSelectedAccountId(newId)
    const url = new URL(window.location.href)
    url.searchParams.set("accountId", newId)
    window.history.replaceState(null, "", url.toString())
  }

  const isRefreshing = isFetchingAccounts || isFetchingTx || isFetchingStatement

  const handleRefresh = () => {
    refetchAccounts()
    if (activeAccountId) {
      refetchTx()
      refetchStatement()
    }
  }

  // Derive Metric Datasets deterministically from retrieved authorized data
  const transactions = React.useMemo(() => {
    return txData?.content ? [...txData.content] : []
  }, [txData])

  const statementEntries = React.useMemo(() => {
    return statementData?.entries ? [...statementData.entries] : []
  }, [statementData])

  // 1. Transaction Volume Points
  const volumeData = React.useMemo(() => {
    return calculateVolumePoints(transactions)
  }, [transactions])

  // 2. Transaction Value Points
  const valueData = React.useMemo(() => {
    try {
      return calculateValuePoints(transactions, currency)
    } catch {
      return []
    }
  }, [transactions, currency])

  // 3. Balance Trend Points (authoritative from statement entries)
  const balanceData = React.useMemo(() => {
    return extractBalanceTrend(statementEntries)
  }, [statementEntries])

  // 4. Success Rate Points
  const {
    points: successData,
    overallRate: overallSuccessRate,
    completedCount,
    totalCount: totalTransactions,
  } = React.useMemo(() => {
    return calculateSuccessRate(transactions)
  }, [transactions])

  // Summary Metrics for supporting text
  const totalVolume = transactions.length
  const totalValue = React.useMemo(() => {
    return transactions.reduce((sum, tx) => safeAddAmounts(sum, tx.amount || 0), 0)
  }, [transactions])

  // Transaction Composition Breakdown
  const composition = React.useMemo(() => {
    return calculateComposition(transactions)
  }, [transactions])

  const isLoadingData = (isLoadingTx || isLoadingStatement) && !txData && !statementData

  return (
    <div className="space-y-6 select-none font-sans">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 pb-3 border-b border-border/70">
        <div>
          <h1 className="text-[26px] font-semibold tracking-tight text-foreground font-sans leading-tight">
            Analytics
          </h1>
          <p className="text-[14px] text-muted-foreground font-sans mt-0.5">
            Financial activity and account trends
          </p>
        </div>

        {/* Compact Controls */}
        <div className="flex flex-wrap items-center gap-2">
          {checkingAccounts.length > 0 && (
            <div className="flex items-center gap-1.5">
              <span className="text-xs text-muted-foreground hidden sm:inline">Account:</span>
              <select
                value={activeAccountId}
                onChange={handleAccountChange}
                aria-label="Select account for analytics"
                className="h-8 rounded-xs border border-border bg-card px-2 text-xs text-foreground font-mono focus:outline-hidden focus:ring-1 focus:ring-ring"
              >
                {checkingAccounts.map((acc) => (
                  <option key={acc.accountId} value={acc.accountId}>
                    Checking {maskAccountNumber(acc.accountNumber)} ({acc.currency})
                  </option>
                ))}
              </select>
            </div>
          )}

          {/* Time Range Selector */}
          <div
            className="flex items-center gap-1 border border-border/70 rounded-sm p-0.5 bg-muted/20"
            role="group"
            aria-label="Data range selection"
          >
            {(["24h", "7d", "30d", "retrieved"] as TimeRange[]).map((range) => (
              <button
                key={range}
                type="button"
                onClick={() => setTimeRange(range)}
                className={cn(
                  "px-2.5 py-1 text-xs rounded-xs font-medium transition-colors focus-visible:ring-1 focus-visible:ring-ring outline-none",
                  timeRange === range
                    ? "bg-foreground text-background shadow-2xs font-semibold"
                    : "text-muted-foreground hover:text-foreground hover:bg-muted/50"
                )}
              >
                {range === "24h"
                  ? "24 Hours"
                  : range === "7d"
                  ? "7 Days"
                  : range === "30d"
                  ? "30 Days"
                  : "Retrieved"}
              </button>
            ))}
          </div>

          {/* Refresh Button */}
          <Button
            variant="outline"
            size="xs"
            onClick={handleRefresh}
            disabled={isRefreshing}
            aria-label="Refresh Analytics"
            title="Refresh Analytics"
            className="h-8 px-2 text-muted-foreground hover:text-foreground"
          >
            <RotateCw className={cn("size-3.5", isRefreshing && "animate-spin")} />
          </Button>
        </div>
      </div>

      {/* Dataset Scope Notice */}
      <div className="flex items-center gap-2 p-2.5 rounded-sm border border-border/60 bg-muted/20 text-xs text-muted-foreground">
        <Database className="size-3.5 text-primary shrink-0" />
        <span>
          Based on retrieved transactions for the selected account instrument (up to 100 records). Metrics are deterministically calculated over authorized ledger data.
        </span>
      </div>

      {/* Content States */}
      {isLoadingAccounts ? (
        <div className="rounded-sm border border-border bg-card p-6 animate-pulse space-y-3">
          <div className="h-4 w-48 bg-muted rounded-xs" />
          <div className="h-3 w-72 bg-muted/60 rounded-xs" />
        </div>
      ) : isErrorAccounts ? (
        <div className="p-8 text-center space-y-2 bg-card border border-destructive/20 rounded-sm">
          <AlertCircle className="size-6 text-destructive mx-auto" />
          <p className="text-sm font-semibold text-foreground">Failed to load accounts</p>
          <p className="text-xs text-muted-foreground">
            {getAnalyticsErrorMessage(accountsError)}
          </p>
          <Button variant="outline" size="xs" onClick={() => refetchAccounts()} className="mt-2">
            Retry
          </Button>
        </div>
      ) : checkingAccounts.length === 0 ? (
        <div className="p-12 text-center space-y-3 bg-card border border-border/70 rounded-sm">
          <Building2 className="size-8 text-muted-foreground/40 mx-auto" />
          <p className="text-base font-semibold text-foreground">No Checking Accounts Available</p>
          <p className="text-xs text-muted-foreground max-w-sm mx-auto">
            Create a checking account to record financial transactions and view operational metrics.
          </p>
        </div>
      ) : isErrorTx || isErrorStatement ? (
        <div className="p-8 text-center space-y-2 bg-card border border-destructive/20 rounded-sm">
          <AlertCircle className="size-6 text-destructive mx-auto" />
          <p className="text-sm font-semibold text-foreground">
            Failed to load transaction analytics
          </p>
          <p className="text-xs text-muted-foreground">
            {getAnalyticsErrorMessage(txError || statementError)}
          </p>
          <Button variant="outline" size="xs" onClick={handleRefresh} className="mt-2">
            Retry
          </Button>
        </div>
      ) : (
        <div className="space-y-6">
          {/* Primary Reusable Analytics Chart Card */}
          <AnalyticsChartCard
            activeMetric={activeMetric}
            onMetricChange={setActiveMetric}
            volumeData={volumeData}
            valueData={valueData}
            balanceData={balanceData}
            successData={successData}
            currency={currency}
            isLoading={isLoadingData}
            totalVolume={totalVolume}
            totalValue={totalValue}
            overallSuccessRate={overallSuccessRate}
            completedCount={completedCount}
            totalTransactions={totalTransactions}
          />

          {/* Transaction Composition Card (Donut / Pie Chart) */}
          <TransactionCompositionCard
            transfers={composition.transfers}
            deposits={composition.deposits}
            withdrawals={composition.withdrawals}
            total={composition.total}
            isLoading={isLoadingData}
          />
        </div>
      )}
    </div>
  )
}

export default function AnalyticsPage() {
  return (
    <React.Suspense fallback={<AnalyticsSkeleton />}>
      <AnalyticsContent />
    </React.Suspense>
  )
}
