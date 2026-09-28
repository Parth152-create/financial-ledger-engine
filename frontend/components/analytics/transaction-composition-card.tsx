"use client"

import * as React from "react"
import { PieChart, Pie, Cell, ResponsiveContainer, Tooltip } from "recharts"
import { ArrowLeftRight, ArrowDownLeft, ArrowUpRight, PieChart as PieChartIcon } from "lucide-react"

interface TransactionCompositionCardProps {
  transfers: number
  deposits: number
  withdrawals: number
  total: number
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

export function TransactionCompositionCard({
  transfers,
  deposits,
  withdrawals,
  total,
  isLoading = false,
}: TransactionCompositionCardProps) {
  const mounted = useHasMounted()

  if (isLoading || !mounted) {
    return (
      <div className="border border-border/70 rounded-sm bg-card overflow-hidden font-sans select-none animate-pulse">
        <div className="px-4 py-3 border-b border-border/70 bg-card flex items-center justify-between">
          <div className="h-4 w-36 bg-muted rounded-xs" />
          <div className="h-3 w-20 bg-muted/70 rounded-xs" />
        </div>
        <div className="p-6 h-52 flex items-center justify-center">
          <div className="size-32 rounded-full border-4 border-muted border-t-muted-foreground/40 animate-spin" />
        </div>
      </div>
    )
  }

  if (total === 0) {
    return (
      <div className="border border-border/70 rounded-sm bg-card overflow-hidden font-sans select-none">
        <div className="px-4 py-3 border-b border-border/70 bg-card flex items-center justify-between">
          <div>
            <h3 className="text-sm font-semibold text-foreground tracking-tight">
              Transaction Composition
            </h3>
            <p className="text-xs text-muted-foreground mt-0.5">
              Breakdown of retrieved transactions by type
            </p>
          </div>
          <span className="text-xs font-mono text-muted-foreground">0 retrieved</span>
        </div>
        <div className="p-8 text-center space-y-1.5">
          <PieChartIcon className="size-6 text-muted-foreground/40 mx-auto mb-1" />
          <p className="text-sm font-medium text-foreground">No Transactions Retrieved</p>
          <p className="text-xs text-muted-foreground max-w-sm mx-auto">
            No transactions found in the retrieved window for this account.
          </p>
        </div>
      </div>
    )
  }

  const transferPct = Math.round((transfers / total) * 100)
  const depositPct = Math.round((deposits / total) * 100)
  const withdrawalPct = Math.max(0, 100 - transferPct - depositPct)

  const chartData = [
    {
      name: "Transfer",
      type: "TRANSFER",
      count: transfers,
      pct: transferPct,
      fill: "var(--color-primary, #3b82f6)",
    },
    {
      name: "Deposit",
      type: "DEPOSIT",
      count: deposits,
      pct: depositPct,
      fill: "#10b981",
    },
    {
      name: "Withdrawal",
      type: "WITHDRAWAL",
      count: withdrawals,
      pct: withdrawalPct,
      fill: "#f59e0b",
    },
  ].filter((item) => item.count > 0)

  return (
    <div className="border border-border/70 rounded-sm bg-card overflow-hidden font-sans select-none">
      <div className="px-4 py-3 border-b border-border/70 bg-card flex items-center justify-between">
        <div>
          <h3 className="text-sm font-semibold text-foreground tracking-tight">
            Transaction Composition
          </h3>
          <p className="text-xs text-muted-foreground mt-0.5">
            Breakdown of retrieved transactions by type
          </p>
        </div>
        <span className="text-xs font-mono text-muted-foreground">
          {total} retrieved
        </span>
      </div>

      <div className="grid grid-cols-1 sm:grid-cols-2 gap-4 items-center p-4">
        {/* Donut Chart */}
        <div className="h-48 w-full flex items-center justify-center">
          <ResponsiveContainer width="100%" height="100%">
            <PieChart>
              <Pie
                data={chartData}
                dataKey="count"
                nameKey="name"
                cx="50%"
                cy="50%"
                innerRadius={46}
                outerRadius={72}
                paddingAngle={2}
                stroke="var(--card)"
                strokeWidth={2}
                isAnimationActive={false}
              >
                {chartData.map((entry) => (
                  <Cell key={entry.type} fill={entry.fill} />
                ))}
              </Pie>
              <Tooltip
                formatter={(value: unknown, name: unknown) => [
                  `${value} txs (${Math.round((Number(value) / total) * 100)}%)`,
                  String(name),
                ]}
                contentStyle={{
                  backgroundColor: "var(--card)",
                  borderColor: "var(--border)",
                  color: "var(--foreground)",
                  fontSize: 12,
                  borderRadius: 4,
                }}
              />
            </PieChart>
          </ResponsiveContainer>
        </div>

        {/* Legend / Metrics Breakdown */}
        <div className="space-y-2.5 font-sans text-xs">
          <div className="flex items-center justify-between p-2.5 rounded-xs border border-border/50 bg-muted/20">
            <div className="flex items-center gap-2">
              <span className="size-2.5 rounded-full bg-blue-500 shrink-0" />
              <ArrowLeftRight className="size-3 text-muted-foreground" />
              <span className="font-medium text-foreground">Transfer</span>
            </div>
            <div className="flex items-center gap-3 font-mono">
              <span className="text-foreground font-semibold">{transfers}</span>
              <span className="text-muted-foreground text-[11px] w-12 text-right">
                ({transferPct}%)
              </span>
            </div>
          </div>

          <div className="flex items-center justify-between p-2.5 rounded-xs border border-border/50 bg-muted/20">
            <div className="flex items-center gap-2">
              <span className="size-2.5 rounded-full bg-emerald-500 shrink-0" />
              <ArrowDownLeft className="size-3 text-muted-foreground" />
              <span className="font-medium text-foreground">Deposit</span>
            </div>
            <div className="flex items-center gap-3 font-mono">
              <span className="text-foreground font-semibold">{deposits}</span>
              <span className="text-muted-foreground text-[11px] w-12 text-right">
                ({depositPct}%)
              </span>
            </div>
          </div>

          <div className="flex items-center justify-between p-2.5 rounded-xs border border-border/50 bg-muted/20">
            <div className="flex items-center gap-2">
              <span className="size-2.5 rounded-full bg-amber-500 shrink-0" />
              <ArrowUpRight className="size-3 text-muted-foreground" />
              <span className="font-medium text-foreground">Withdrawal</span>
            </div>
            <div className="flex items-center gap-3 font-mono">
              <span className="text-foreground font-semibold">{withdrawals}</span>
              <span className="text-muted-foreground text-[11px] w-12 text-right">
                ({withdrawalPct}%)
              </span>
            </div>
          </div>
        </div>
      </div>

      <div className="px-4 py-2 border-t border-border/60 bg-muted/10 text-xs text-muted-foreground flex items-center justify-between">
        <span>
          Total: <strong className="text-foreground font-mono">{total}</strong> transactions
        </span>
        <span className="font-mono text-[11px] text-muted-foreground/80">
          Based on retrieved transactions
        </span>
      </div>
    </div>
  )
}
