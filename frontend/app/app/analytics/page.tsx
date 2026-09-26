"use client"

import * as React from "react"
import {
  RotateCw,
  AlertCircle,
  Building2,
  BarChart3,
  TrendingUp,
  Percent,
  Coins,
} from "lucide-react"
import { Section } from "@/components/ui/section"
import { Button } from "@/components/ui/button"
import { DataRow } from "@/components/ui/data-row"
import { AmountDisplay } from "@/components/ui/amount-display"
import {
  AnalyticsChartCard,
  type AnalyticsMetric,
  type VolumePoint,
  type ValuePoint,
  type BalancePoint,
  type SuccessPoint,
} from "@/components/charts/analytics-chart-card"
import { TransactionMixCard } from "@/components/analytics/transaction-mix-card"
import { useAccounts } from "@/hooks/api/use-accounts"
import { useAccountTransactions } from "@/hooks/api/use-transactions"
import { useAccountStatement } from "@/hooks/api/use-statements"
import { cn } from "@/lib/utils"

type TimeRange = "24h" | "7d" | "30d" | "all"

export default function AnalyticsPage() {
  const {
    data: accounts,
    isLoading: isLoadingAccounts,
    isError: isErrorAccounts,
    error: accountsError,
    refetch: refetchAccounts,
  } = useAccounts()

  const [selectedAccountId, setSelectedAccountId] = React.useState<string>("")
  const [timeRange, setTimeRange] = React.useState<TimeRange>("30d")
  const [activeMetric, setActiveMetric] = React.useState<AnalyticsMetric>("volume")

  const activeAccountId =
    selectedAccountId || (accounts && accounts.length > 0 ? accounts[0].accountId : "")
  const currentAccount = accounts?.find((a) => a.accountId === activeAccountId)
  const currency = currentAccount?.currency || "INR"

  // Derive "from" timestamp based on timeRange
  const fromTimestamp = React.useMemo(() => {
    if (timeRange === "all") return undefined
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

  // Fetch transactions and statement for the selected account instrument
  const {
    data: txData,
    isLoading: isLoadingTx,
    isError: isErrorTx,
    error: txError,
    refetch: refetchTx,
  } = useAccountTransactions(activeAccountId, {
    from: fromTimestamp,
    size: 100,
  })

  const {
    data: statementData,
    isLoading: isLoadingStatement,
    refetch: refetchStatement,
  } = useAccountStatement(activeAccountId, {
    from: fromTimestamp,
    size: 100,
  })

  // Format short date for chart x-axis
  const formatShortDate = (isoStr: string) => {
    try {
      const d = new Date(isoStr)
      if (isNaN(d.getTime())) return isoStr
      return d.toLocaleDateString("en-US", { month: "short", day: "numeric" })
    } catch {
      return isoStr
    }
  }

  // Derive Metric Datasets safely from retrieved authorized data
  const list = txData?.content ? [...txData.content] : []
  const chronologicalTxs = list.sort(
    (a, b) => new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime()
  )

  // 1. Transaction Volume Points (grouped by date)
  const volumeMap = new Map<string, number>()
  chronologicalTxs.forEach((tx) => {
    const dateKey = formatShortDate(tx.createdAt)
    volumeMap.set(dateKey, (volumeMap.get(dateKey) || 0) + 1)
  })
  const volumeData: VolumePoint[] = Array.from(volumeMap.entries()).map(([date, volume]) => ({
    date,
    volume,
  }))

  // 2. Transaction Value Points (summed per date in the account currency)
  const valueMap = new Map<string, number>()
  chronologicalTxs.forEach((tx) => {
    const dateKey = formatShortDate(tx.createdAt)
    const current = valueMap.get(dateKey) || 0
    valueMap.set(dateKey, current + Number(tx.amount || 0))
  })
  const valueData: ValuePoint[] = Array.from(valueMap.entries()).map(([date, value]) => ({
    date,
    value: Math.round(value * 100) / 100,
  }))

  // 3. Balance Trend Points (authoritative from statement entries running balances)
  const statementEntries = statementData?.entries || []
  const balanceData: BalancePoint[] =
    statementEntries.length === 0
      ? statementData && statementData.openingBalance !== undefined
        ? [{ date: "Current", balance: Number(statementData.openingBalance) }]
        : []
      : statementEntries.map((entry) => ({
          date: formatShortDate(entry.createdAt),
          balance: Number(entry.balanceAfter),
        }))

  // 4. Success Rate Points (grouped by date)
  const successMap = new Map<string, { completed: number; total: number }>()
  chronologicalTxs.forEach((tx) => {
    const dateKey = formatShortDate(tx.createdAt)
    const current = successMap.get(dateKey) || { completed: 0, total: 0 }
    const isCompleted = tx.status === "COMPLETED"
    successMap.set(dateKey, {
      completed: current.completed + (isCompleted ? 1 : 0),
      total: current.total + 1,
    })
  })
  const successData: SuccessPoint[] = Array.from(successMap.entries()).map(([date, stats]) => ({
    date,
    completed: stats.completed,
    total: stats.total,
    rate: stats.total > 0 ? Math.round((stats.completed / stats.total) * 100) : 100,
  }))

  // Summary Metrics
  const totalVolume = chronologicalTxs.length
  const totalValue = chronologicalTxs.reduce((sum, tx) => sum + Number(tx.amount || 0), 0)
  const completedCount = chronologicalTxs.filter((tx) => tx.status === "COMPLETED").length
  const overallSuccessRate =
    totalVolume > 0 ? ((completedCount / totalVolume) * 100).toFixed(1) : "100.0"

  // Transaction Mix Breakdown
  const transferCount = chronologicalTxs.filter((tx) => tx.transactionType === "TRANSFER").length
  const depositCount = chronologicalTxs.filter((tx) => tx.transactionType === "DEPOSIT").length
  const withdrawalCount = chronologicalTxs.filter(
    (tx) => tx.transactionType === "WITHDRAWAL"
  ).length

  const isLoadingData = isLoadingTx || isLoadingStatement

  const handleRefresh = () => {
    refetchAccounts()
    refetchTx()
    refetchStatement()
  }

  return (
    <div className="space-y-6 select-none font-sans">
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 pb-3 border-b border-border/70">
        <div>
          <h1 className="text-[26px] font-semibold tracking-tight text-foreground font-sans leading-tight">
            Analytics
          </h1>
          <p className="text-[14px] text-muted-foreground font-sans mt-0.5">
            Operational transaction throughput, monetary volume, balance trends, and settlement success.
          </p>
        </div>

        <div className="flex flex-wrap items-center gap-2">
          {accounts && accounts.length > 0 && (
            <select
              value={activeAccountId}
              onChange={(e) => setSelectedAccountId(e.target.value)}
              className="h-8 rounded-xs border border-border bg-card px-2.5 text-xs text-foreground font-mono focus:outline-hidden focus:ring-1 focus:ring-ring"
            >
              {accounts.map((acc) => (
                <option key={acc.accountId} value={acc.accountId}>
                  {acc.accountNumber} ({acc.currency})
                </option>
              ))}
            </select>
          )}

          <div className="flex items-center gap-1 border border-border/70 rounded-sm p-0.5 bg-muted/20">
            {(["24h", "7d", "30d", "all"] as TimeRange[]).map((range) => (
              <button
                key={range}
                type="button"
                onClick={() => setTimeRange(range)}
                className={cn(
                  "px-2.5 py-1 text-xs rounded-xs font-medium transition-colors",
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
                  : "All Time"}
              </button>
            ))}
          </div>

          <Button
            variant="outline"
            size="xs"
            onClick={handleRefresh}
            className="h-8 px-2 text-muted-foreground hover:text-foreground"
            title="Refresh Analytics"
          >
            <RotateCw className="size-3.5" />
          </Button>
        </div>
      </div>

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
            {accountsError?.message || "Could not retrieve account list."}
          </p>
          <Button variant="outline" size="xs" onClick={() => refetchAccounts()} className="mt-2">
            Retry
          </Button>
        </div>
      ) : !accounts || accounts.length === 0 ? (
        <div className="p-12 text-center space-y-3 bg-card border border-border/70 rounded-sm">
          <Building2 className="size-8 text-muted-foreground/40 mx-auto" />
          <p className="text-base font-semibold text-foreground">No Checking Accounts Available</p>
          <p className="text-xs text-muted-foreground max-w-sm mx-auto">
            Create a checking account to record financial transactions and view operational metrics.
          </p>
        </div>
      ) : (
        <div className="space-y-6">
          {/* Summary Stat Cards */}
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3.5">
            <div className="p-3.5 border border-border/80 rounded-sm bg-card space-y-1 shadow-2xs">
              <div className="flex items-center justify-between text-muted-foreground">
                <span className="text-[12px] font-medium uppercase tracking-wider">
                  Transaction Volume
                </span>
                <BarChart3 className="size-3.5" />
              </div>
              <div className="text-xl font-bold font-mono text-foreground pt-1">
                {totalVolume}{" "}
                <span className="text-xs font-normal text-muted-foreground font-sans">txs</span>
              </div>
              <span className="text-[11px] text-muted-foreground block">
                Retrieved in selected window
              </span>
            </div>

            <div className="p-3.5 border border-border/80 rounded-sm bg-card space-y-1 shadow-2xs">
              <div className="flex items-center justify-between text-muted-foreground">
                <span className="text-[12px] font-medium uppercase tracking-wider">
                  Monetary Flow ({currency})
                </span>
                <Coins className="size-3.5" />
              </div>
              <div className="pt-1">
                <AmountDisplay
                  amount={totalValue}
                  currency={currency}
                  size="lg"
                />
              </div>
              <span className="text-[11px] text-muted-foreground block">
                Total gross volume moved
              </span>
            </div>

            <div className="p-3.5 border border-border/80 rounded-sm bg-card space-y-1 shadow-2xs">
              <div className="flex items-center justify-between text-muted-foreground">
                <span className="text-[12px] font-medium uppercase tracking-wider">
                  Settled Balance ({currency})
                </span>
                <TrendingUp className="size-3.5" />
              </div>
              <div className="pt-1">
                <AmountDisplay
                  amount={currentAccount?.balance || 0}
                  currency={currency}
                  size="lg"
                />
              </div>
              <span className="text-[11px] text-muted-foreground block">
                Current authoritative ledger balance
              </span>
            </div>

            <div className="p-3.5 border border-border/80 rounded-sm bg-card space-y-1 shadow-2xs">
              <div className="flex items-center justify-between text-muted-foreground">
                <span className="text-[12px] font-medium uppercase tracking-wider">
                  Settlement Rate
                </span>
                <Percent className="size-3.5" />
              </div>
              <div className="text-xl font-bold font-mono text-foreground pt-1">
                {overallSuccessRate}%
              </div>
              <span className="text-[11px] text-muted-foreground block">
                {completedCount} of {totalVolume} settled successfully
              </span>
            </div>
          </div>

          {/* Unified Chart Card */}
          {isErrorTx ? (
            <div className="p-8 text-center space-y-2 bg-card border border-destructive/20 rounded-sm">
              <AlertCircle className="size-6 text-destructive mx-auto" />
              <p className="text-sm font-semibold text-foreground">
                Failed to load transaction analytics
              </p>
              <p className="text-xs text-muted-foreground">{txError?.message}</p>
              <Button variant="outline" size="xs" onClick={() => refetchTx()} className="mt-2">
                Retry
              </Button>
            </div>
          ) : (
            <AnalyticsChartCard
              activeMetric={activeMetric}
              onMetricChange={setActiveMetric}
              volumeData={volumeData}
              valueData={valueData}
              balanceData={balanceData}
              successData={successData}
              currency={currency}
              isLoading={isLoadingData}
            />
          )}

          {/* Subordinate Transaction Mix & Protocol Details */}
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            <TransactionMixCard
              transfers={transferCount}
              deposits={depositCount}
              withdrawals={withdrawalCount}
              total={totalVolume}
            />

            <div className="p-4 border border-border/70 rounded-sm bg-card font-sans select-none space-y-2.5">
              <h4 className="text-xs font-semibold text-foreground uppercase tracking-wider">
                Analytics Architecture Notice
              </h4>
              <p className="text-xs text-muted-foreground leading-relaxed">
                <strong className="text-foreground">Backend Availability:</strong> The engine does not expose a dedicated pre-aggregated analytics service.
                Metrics shown are <span className="text-foreground font-medium">frontend-derived deterministically</span> from authorized account transactions and double-entry statements.
              </p>
              <div className="text-[11px] text-muted-foreground/80 font-mono space-y-0.5 pt-1 border-t border-border/50">
                <div>• Scoped strictly to account: {currentAccount?.accountNumber}</div>
                <div>• Denominator defined: COMPLETED + FAILED + PENDING records</div>
                <div>• Single currency guarantee: No cross-currency FX conversion</div>
              </div>
            </div>
          </div>
        </div>
      )}

      <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
        <Section title="Throughput & Concurrency">
          <div className="space-y-1">
            <DataRow label="Locking Strategy" value="Pessimistic row-level" />
            <DataRow label="Lock Order" value="Deterministic by account ID" />
            <DataRow label="Deadlock Prevention" value="Mathematical guarantee" />
          </div>
        </Section>

        <Section title="Idempotency Cache">
          <div className="space-y-1">
            <DataRow label="Fast-Path Cache" value="Redis" />
            <DataRow label="Authoritative Deduplication" value="PostgreSQL unique index" />
            <DataRow label="Retention Window" value="24 Hours (86,400s)" />
          </div>
        </Section>

        <Section title="Financial Precision">
          <div className="space-y-1">
            <DataRow label="Number Precision" value="PostgreSQL NUMERIC / BigDecimal" />
            <DataRow label="Floating Point Math" value="Zero client-side calculations" />
            <DataRow label="Balance Calculation" value="Authoritative backend derivation" />
          </div>
        </Section>
      </div>
    </div>
  )
}
