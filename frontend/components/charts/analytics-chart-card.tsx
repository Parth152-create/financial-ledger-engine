"use client"

import * as React from "react"
import {
  BarChart3,
  TrendingUp,
  Coins,
  Percent,
  Activity,
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
import { cn } from "@/lib/utils"

export type AnalyticsMetric = "volume" | "value" | "balance" | "success"

export interface VolumePoint {
  date: string
  volume: number
}

export interface ValuePoint {
  date: string
  value: number
}

export interface BalancePoint {
  date: string
  balance: number
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
}

const emptySubscribe = () => () => {}

function useHasMounted() {
  return React.useSyncExternalStore(
    emptySubscribe,
    () => true,
    () => false
  )
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

  return (
    <div className="border border-border/70 rounded-sm bg-card overflow-hidden font-sans select-none">
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 px-4 py-3 border-b border-border/70 bg-card">
        <div>
          <h3 className="text-sm font-semibold text-foreground tracking-tight">
            {activeMetric === "volume"
              ? "Transaction Volume"
              : activeMetric === "value"
              ? `Transaction Value Flow (${currency})`
              : activeMetric === "balance"
              ? `Balance Trend (${currency})`
              : "Settlement Success Rate"}
          </h3>
          <p className="text-xs text-muted-foreground mt-0.5">
            {activeMetric === "volume"
              ? "Number of transactions recorded per date bucket."
              : activeMetric === "value"
              ? "Total gross monetary value of transactions processed."
              : activeMetric === "balance"
              ? "Authoritative running balance derived from the double-entry statement."
              : "Percentage of successfully settled transactions vs total executed."}
          </p>
        </div>

        <div className="flex items-center gap-1 border border-border/70 rounded-sm p-0.5 bg-muted/20 self-start sm:self-auto">
          <button
            type="button"
            onClick={() => onMetricChange("volume")}
            className={cn(
              "flex items-center gap-1.5 px-2.5 py-1 text-xs rounded-xs transition-colors font-medium",
              activeMetric === "volume"
                ? "bg-foreground text-background shadow-2xs"
                : "text-muted-foreground hover:text-foreground hover:bg-muted/50"
            )}
            title="Transaction Volume"
          >
            <BarChart3 className="size-3" />
            <span>Volume</span>
          </button>

          <button
            type="button"
            onClick={() => onMetricChange("value")}
            className={cn(
              "flex items-center gap-1.5 px-2.5 py-1 text-xs rounded-xs transition-colors font-medium",
              activeMetric === "value"
                ? "bg-foreground text-background shadow-2xs"
                : "text-muted-foreground hover:text-foreground hover:bg-muted/50"
            )}
            title="Transaction Value"
          >
            <Coins className="size-3" />
            <span>Value</span>
          </button>

          <button
            type="button"
            onClick={() => onMetricChange("balance")}
            className={cn(
              "flex items-center gap-1.5 px-2.5 py-1 text-xs rounded-xs transition-colors font-medium",
              activeMetric === "balance"
                ? "bg-foreground text-background shadow-2xs"
                : "text-muted-foreground hover:text-foreground hover:bg-muted/50"
            )}
            title="Balance Trend"
          >
            <TrendingUp className="size-3" />
            <span>Balance</span>
          </button>

          <button
            type="button"
            onClick={() => onMetricChange("success")}
            className={cn(
              "flex items-center gap-1.5 px-2.5 py-1 text-xs rounded-xs transition-colors font-medium",
              activeMetric === "success"
                ? "bg-foreground text-background shadow-2xs"
                : "text-muted-foreground hover:text-foreground hover:bg-muted/50"
            )}
            title="Success Rate"
          >
            <Percent className="size-3" />
            <span>Success Rate</span>
          </button>
        </div>
      </div>

      <div className="h-72 p-4 flex flex-col justify-center">
        {isLoading || !mounted ? (
          <div className="h-full w-full flex items-center justify-center animate-pulse bg-muted/10 rounded-sm">
            <span className="text-xs text-muted-foreground font-mono">Loading metric dataset...</span>
          </div>
        ) : !hasData ? (
          <div className="h-full w-full border border-dashed border-border/70 rounded-sm flex flex-col items-center justify-center p-6 bg-muted/10 text-center space-y-2">
            <Activity className="size-7 text-muted-foreground/40 mb-0.5" />
            <p className="text-sm font-medium text-foreground">
              No Data in Selected Window
            </p>
            <p className="text-xs text-muted-foreground max-w-sm">
              Transactions executed during this time window will appear on the chart.
            </p>
          </div>
        ) : (
          <div className="h-full w-full">
            <ResponsiveContainer width="100%" height="100%">
              {activeMetric === "volume" ? (
                <BarChart data={volumeData} margin={{ top: 10, right: 10, left: -20, bottom: 0 }}>
                  <CartesianGrid strokeDasharray="3 3" vertical={false} opacity={0.2} />
                  <XAxis dataKey="date" tick={{ fontSize: 11 }} tickLine={false} axisLine={false} />
                  <YAxis tick={{ fontSize: 11 }} tickLine={false} axisLine={false} allowDecimals={false} />
                  <Tooltip
                    formatter={(value: unknown) => [`${value} txs`, "Volume"]}
                    contentStyle={{
                      backgroundColor: "var(--card)",
                      borderColor: "var(--border)",
                      fontSize: 12,
                      borderRadius: 4,
                    }}
                  />
                  <Bar dataKey="volume" fill="var(--color-primary, #2563eb)" radius={[2, 2, 0, 0]} />
                </BarChart>
              ) : activeMetric === "value" ? (
                <BarChart data={valueData} margin={{ top: 10, right: 10, left: -10, bottom: 0 }}>
                  <CartesianGrid strokeDasharray="3 3" vertical={false} opacity={0.2} />
                  <XAxis dataKey="date" tick={{ fontSize: 11 }} tickLine={false} axisLine={false} />
                  <YAxis tick={{ fontSize: 11 }} tickLine={false} axisLine={false} />
                  <Tooltip
                    formatter={(value: unknown) => [`${currency} ${Number(value).toFixed(2)}`, "Value"]}
                    contentStyle={{
                      backgroundColor: "var(--card)",
                      borderColor: "var(--border)",
                      fontSize: 12,
                      borderRadius: 4,
                    }}
                  />
                  <Bar dataKey="value" fill="var(--color-primary, #059669)" radius={[2, 2, 0, 0]} />
                </BarChart>
              ) : activeMetric === "balance" ? (
                <AreaChart data={balanceData} margin={{ top: 10, right: 10, left: -10, bottom: 0 }}>
                  <CartesianGrid strokeDasharray="3 3" vertical={false} opacity={0.2} />
                  <XAxis dataKey="date" tick={{ fontSize: 11 }} tickLine={false} axisLine={false} />
                  <YAxis tick={{ fontSize: 11 }} tickLine={false} axisLine={false} />
                  <Tooltip
                    formatter={(value: unknown) => [`${currency} ${Number(value).toFixed(2)}`, "Balance"]}
                    contentStyle={{
                      backgroundColor: "var(--card)",
                      borderColor: "var(--border)",
                      fontSize: 12,
                      borderRadius: 4,
                    }}
                  />
                  <Area
                    type="monotone"
                    dataKey="balance"
                    stroke="#0284c7"
                    fill="#0284c7"
                    fillOpacity={0.15}
                    strokeWidth={2}
                  />
                </AreaChart>
              ) : (
                <AreaChart data={successData} margin={{ top: 10, right: 10, left: -10, bottom: 0 }}>
                  <CartesianGrid strokeDasharray="3 3" vertical={false} opacity={0.2} />
                  <XAxis dataKey="date" tick={{ fontSize: 11 }} tickLine={false} axisLine={false} />
                  <YAxis domain={[0, 100]} tick={{ fontSize: 11 }} tickLine={false} axisLine={false} />
                  <Tooltip
                    formatter={(value: unknown, _: unknown, item: { payload?: SuccessPoint }) => {
                      const point = item?.payload
                      return point
                        ? [`${value}% (${point.completed}/${point.total})`, "Success Rate"]
                        : [`${value}%`, "Success Rate"]
                    }}
                    contentStyle={{
                      backgroundColor: "var(--card)",
                      borderColor: "var(--border)",
                      fontSize: 12,
                      borderRadius: 4,
                    }}
                  />
                  <Area
                    type="monotone"
                    dataKey="rate"
                    stroke="#10b981"
                    fill="#10b981"
                    fillOpacity={0.15}
                    strokeWidth={2}
                  />
                </AreaChart>
              )}
            </ResponsiveContainer>
          </div>
        )}
      </div>
    </div>
  )
}
