"use client"

import * as React from "react"
import {
  BarChart3,
  IndianRupee,
  TrendingUp,
  CircleCheck,
  Activity,
  AlertCircle,
} from "lucide-react"
import {
  ResponsiveContainer,
  BarChart,
  Bar,
  AreaChart,
  Area,
  XAxis,
  YAxis,
  Tooltip,
  CartesianGrid,
} from "recharts"
import { formatINR } from "@/lib/formatters/currency"
import { cn } from "@/lib/utils"

export type AnalyticsMetric = "volume" | "value" | "balance" | "success"

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

interface AnalyticsChartCardProps {
  activeMetric: AnalyticsMetric
  onMetricChange: (metric: AnalyticsMetric) => void
  volumeData: VolumePoint[]
  valueData: ValuePoint[]
  balanceData: BalancePoint[]
  successData: SuccessPoint[]
  currency?: string
  isLoading?: boolean
  totalVolume?: number
  totalValue?: number
  overallSuccessRate?: string
  completedCount?: number
  totalTransactions?: number
}

const emptySubscribe = () => () => {}

function useHasMounted() {
  return React.useSyncExternalStore(
    emptySubscribe,
    () => true,
    () => false
  )
}

const tooltipContainerStyle = {
  backgroundColor: "var(--card)",
  borderColor: "var(--border)",
  color: "var(--foreground)",
  fontSize: 12,
  borderRadius: 4,
}

export function AnalyticsChartCard({
  activeMetric,
  onMetricChange,
  volumeData,
  valueData,
  balanceData,
  successData,
  currency = "INR",
  isLoading = false,
  totalVolume = 0,
  totalValue = 0,
  overallSuccessRate = "0.0",
  completedCount = 0,
  totalTransactions = 0,
}: AnalyticsChartCardProps) {
  const mounted = useHasMounted()

  const hasData =
    activeMetric === "volume"
      ? volumeData.length > 0 && volumeData.some((p) => p.volume > 0)
      : activeMetric === "value"
      ? valueData.length > 0 && valueData.some((p) => p.value > 0)
      : activeMetric === "balance"
      ? balanceData.length > 0
      : successData.length > 0 && successData.some((p) => p.total > 0)

  // Accessible supporting text summarizing the current metric
  const supportingText = React.useMemo(() => {
    switch (activeMetric) {
      case "volume":
        return `Total retrieved volume: ${totalVolume} transactions across ${volumeData.length} date buckets`
      case "value":
        return `Total retrieved value: ${formatINR(totalValue)} across ${valueData.length} date buckets`
      case "balance":
        return balanceData.length > 0
          ? `Latest ledger balance: ${formatINR(balanceData[balanceData.length - 1].balance)} across ${balanceData.length} entry points`
          : "Insufficient data to plot balance trend"
      case "success":
        return `Overall settlement success rate: ${overallSuccessRate}% (${completedCount} of ${totalTransactions} settled)`
    }
  }, [
    activeMetric,
    totalVolume,
    volumeData.length,
    totalValue,
    valueData.length,
    balanceData,
    overallSuccessRate,
    completedCount,
    totalTransactions,
  ])

  return (
    <div className="border border-border/70 rounded-sm bg-card overflow-hidden font-sans select-none flex flex-col">
      {/* Card Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 px-4 py-3 border-b border-border/70 bg-card">
        <div>
          <h3 className="text-sm font-semibold text-foreground tracking-tight">
            Activity Overview
          </h3>
          <p className="text-xs text-muted-foreground mt-0.5">
            {activeMetric === "volume"
              ? "Transaction Volume — Based on retrieved transactions"
              : activeMetric === "value"
              ? `Transaction Value Flow (${currency}) — Based on retrieved transactions`
              : activeMetric === "balance"
              ? `Balance Trend (${currency}) — Authoritative ledger running balance`
              : "Settlement Success Rate — Based on retrieved transactions"}
          </p>
        </div>

        {/* Top-Right Mode Selector with Small Icon Buttons */}
        <div
          className="flex items-center gap-1 border border-border/70 rounded-sm p-0.5 bg-muted/20 self-start sm:self-auto"
          role="tablist"
          aria-label="Analytics Chart Modes"
        >
          <button
            type="button"
            role="tab"
            aria-selected={activeMetric === "volume"}
            aria-label="Transaction Volume"
            title="Transaction Volume"
            onClick={() => onMetricChange("volume")}
            className={cn(
              "size-7 flex items-center justify-center rounded-xs transition-colors focus-visible:ring-1 focus-visible:ring-ring outline-none",
              activeMetric === "volume"
                ? "bg-muted border border-border text-foreground shadow-2xs font-semibold"
                : "text-muted-foreground hover:text-foreground hover:bg-muted/40 border border-transparent"
            )}
          >
            <BarChart3 className="size-3.5" />
          </button>

          <button
            type="button"
            role="tab"
            aria-selected={activeMetric === "value"}
            aria-label="Transaction Value"
            title="Transaction Value"
            onClick={() => onMetricChange("value")}
            className={cn(
              "size-7 flex items-center justify-center rounded-xs transition-colors focus-visible:ring-1 focus-visible:ring-ring outline-none",
              activeMetric === "value"
                ? "bg-muted border border-border text-foreground shadow-2xs font-semibold"
                : "text-muted-foreground hover:text-foreground hover:bg-muted/40 border border-transparent"
            )}
          >
            <IndianRupee className="size-3.5" />
          </button>

          <button
            type="button"
            role="tab"
            aria-selected={activeMetric === "balance"}
            aria-label="Balance Trend"
            title="Balance Trend"
            onClick={() => onMetricChange("balance")}
            className={cn(
              "size-7 flex items-center justify-center rounded-xs transition-colors focus-visible:ring-1 focus-visible:ring-ring outline-none",
              activeMetric === "balance"
                ? "bg-muted border border-border text-foreground shadow-2xs font-semibold"
                : "text-muted-foreground hover:text-foreground hover:bg-muted/40 border border-transparent"
            )}
          >
            <TrendingUp className="size-3.5" />
          </button>

          <button
            type="button"
            role="tab"
            aria-selected={activeMetric === "success"}
            aria-label="Success Rate"
            title="Success Rate"
            onClick={() => onMetricChange("success")}
            className={cn(
              "size-7 flex items-center justify-center rounded-xs transition-colors focus-visible:ring-1 focus-visible:ring-ring outline-none",
              activeMetric === "success"
                ? "bg-muted border border-border text-foreground shadow-2xs font-semibold"
                : "text-muted-foreground hover:text-foreground hover:bg-muted/40 border border-transparent"
            )}
          >
            <CircleCheck className="size-3.5" />
          </button>
        </div>
      </div>

      {/* Fixed Height Chart Container */}
      <div className="h-72 p-4 flex flex-col justify-center">
        {isLoading || !mounted ? (
          <div className="h-full w-full flex items-center justify-center animate-pulse bg-muted/10 rounded-xs">
            <span className="text-xs text-muted-foreground font-mono">
              Loading metric dataset...
            </span>
          </div>
        ) : activeMetric === "balance" && balanceData.length === 0 ? (
          /* Honest Insufficient Data State for Balance Trend */
          <div className="h-full w-full border border-dashed border-border/70 rounded-xs flex flex-col items-center justify-center p-6 bg-muted/10 text-center space-y-1.5">
            <AlertCircle className="size-6 text-muted-foreground/60 mb-1" />
            <p className="text-sm font-medium text-foreground">Insufficient data</p>
            <p className="text-xs text-muted-foreground max-w-sm">
              At least one settled ledger statement entry is required to establish historical trend points.
            </p>
          </div>
        ) : !hasData ? (
          /* Clean Empty State */
          <div className="h-full w-full border border-dashed border-border/70 rounded-xs flex flex-col items-center justify-center p-6 bg-muted/10 text-center space-y-1.5">
            <Activity className="size-6 text-muted-foreground/60 mb-1" />
            <p className="text-sm font-medium text-foreground">No Data in Selected Window</p>
            <p className="text-xs text-muted-foreground max-w-sm">
              Transactions executed during this time window will appear on the chart.
            </p>
          </div>
        ) : (
          <div className="h-full w-full">
            <ResponsiveContainer width="100%" height="100%">
              {activeMetric === "volume" ? (
                <BarChart data={volumeData} margin={{ top: 10, right: 10, left: -20, bottom: 0 }}>
                  <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="var(--border)" opacity={0.3} />
                  <XAxis
                    dataKey="date"
                    tick={{ fontSize: 11, fill: "var(--muted-foreground)" }}
                    tickLine={false}
                    axisLine={false}
                  />
                  <YAxis
                    tick={{ fontSize: 11, fill: "var(--muted-foreground)" }}
                    tickLine={false}
                    axisLine={false}
                    allowDecimals={false}
                  />
                  <Tooltip
                    formatter={(value: unknown) => [`${value} txs`, "Volume"]}
                    contentStyle={tooltipContainerStyle}
                  />
                  <Bar
                    dataKey="volume"
                    fill="var(--color-primary, #3b82f6)"
                    radius={[2, 2, 0, 0]}
                    isAnimationActive={false}
                  />
                </BarChart>
              ) : activeMetric === "value" ? (
                <BarChart data={valueData} margin={{ top: 10, right: 10, left: -10, bottom: 0 }}>
                  <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="var(--border)" opacity={0.3} />
                  <XAxis
                    dataKey="date"
                    tick={{ fontSize: 11, fill: "var(--muted-foreground)" }}
                    tickLine={false}
                    axisLine={false}
                  />
                  <YAxis
                    tick={{ fontSize: 11, fill: "var(--muted-foreground)" }}
                    tickLine={false}
                    axisLine={false}
                    tickFormatter={(v) => `₹${v}`}
                  />
                  <Tooltip
                    formatter={(value: unknown) => [formatINR(Number(value)), "Value"]}
                    contentStyle={tooltipContainerStyle}
                  />
                  <Bar
                    dataKey="value"
                    fill="var(--color-chart-2, #059669)"
                    radius={[2, 2, 0, 0]}
                    isAnimationActive={false}
                  />
                </BarChart>
              ) : activeMetric === "balance" ? (
                <AreaChart data={balanceData} margin={{ top: 10, right: 10, left: -10, bottom: 0 }}>
                  <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="var(--border)" opacity={0.3} />
                  <XAxis
                    dataKey="date"
                    tick={{ fontSize: 11, fill: "var(--muted-foreground)" }}
                    tickLine={false}
                    axisLine={false}
                  />
                  <YAxis
                    tick={{ fontSize: 11, fill: "var(--muted-foreground)" }}
                    tickLine={false}
                    axisLine={false}
                    tickFormatter={(v) => `₹${v}`}
                  />
                  <Tooltip
                    formatter={(value: unknown) => [formatINR(Number(value)), "Running Balance"]}
                    contentStyle={tooltipContainerStyle}
                  />
                  <Area
                    type="monotone"
                    dataKey="balance"
                    stroke="var(--color-primary, #0284c7)"
                    fill="var(--color-primary, #0284c7)"
                    fillOpacity={0.12}
                    strokeWidth={2}
                    isAnimationActive={false}
                  />
                </AreaChart>
              ) : (
                <AreaChart data={successData} margin={{ top: 10, right: 10, left: -10, bottom: 0 }}>
                  <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="var(--border)" opacity={0.3} />
                  <XAxis
                    dataKey="date"
                    tick={{ fontSize: 11, fill: "var(--muted-foreground)" }}
                    tickLine={false}
                    axisLine={false}
                  />
                  <YAxis
                    domain={[0, 100]}
                    tick={{ fontSize: 11, fill: "var(--muted-foreground)" }}
                    tickLine={false}
                    axisLine={false}
                    tickFormatter={(v) => `${v}%`}
                  />
                  <Tooltip
                    formatter={(value: unknown, _: unknown, item: { payload?: SuccessPoint }) => {
                      const point = item?.payload
                      return point
                        ? [`${value}% (${point.completed}/${point.total})`, "Success Rate"]
                        : [`${value}%`, "Success Rate"]
                    }}
                    contentStyle={tooltipContainerStyle}
                  />
                  <Area
                    type="monotone"
                    dataKey="rate"
                    stroke="#10b981"
                    fill="#10b981"
                    fillOpacity={0.12}
                    strokeWidth={2}
                    isAnimationActive={false}
                  />
                </AreaChart>
              )}
            </ResponsiveContainer>
          </div>
        )}
      </div>

      {/* Accessible Supporting Text Bar */}
      <div className="px-4 py-2 border-t border-border/60 bg-muted/10 text-xs text-muted-foreground flex flex-wrap items-center justify-between gap-2">
        <span>{supportingText}</span>
        <span className="font-mono text-[11px] text-muted-foreground/80">
          Based on retrieved transactions
        </span>
      </div>
    </div>
  )
}
