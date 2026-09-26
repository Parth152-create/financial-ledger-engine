"use client"

import * as React from "react"
import { ArrowLeftRight, ArrowDownLeft, ArrowUpRight } from "lucide-react"

interface TransactionMixCardProps {
  transfers: number
  deposits: number
  withdrawals: number
  total: number
}

export function TransactionMixCard({
  transfers,
  deposits,
  withdrawals,
  total,
}: TransactionMixCardProps) {
  if (total === 0) {
    return (
      <div className="p-4 border border-border/70 rounded-sm bg-card font-sans select-none space-y-2">
        <h4 className="text-xs font-semibold text-foreground uppercase tracking-wider">
          Transaction Mix
        </h4>
        <p className="text-xs text-muted-foreground">
          No transactions recorded in the selected period.
        </p>
      </div>
    )
  }

  const transferPct = Math.round((transfers / total) * 100)
  const depositPct = Math.round((deposits / total) * 100)
  const withdrawalPct = Math.max(0, 100 - transferPct - depositPct)

  return (
    <div className="p-4 border border-border/70 rounded-sm bg-card font-sans select-none space-y-3">
      <div className="flex items-center justify-between">
        <h4 className="text-xs font-semibold text-foreground uppercase tracking-wider">
          Transaction Mix
        </h4>
        <span className="text-xs font-mono text-muted-foreground">
          {total} total
        </span>
      </div>

      {/* Proportional Segmented Progress Bar */}
      <div className="h-2 w-full rounded-xs bg-muted/40 overflow-hidden flex">
        {transferPct > 0 && (
          <div
            style={{ width: `${transferPct}%` }}
            className="bg-blue-500 transition-all duration-300"
            title={`Transfers: ${transferPct}%`}
          />
        )}
        {depositPct > 0 && (
          <div
            style={{ width: `${depositPct}%` }}
            className="bg-emerald-500 transition-all duration-300"
            title={`Deposits: ${depositPct}%`}
          />
        )}
        {withdrawalPct > 0 && (
          <div
            style={{ width: `${withdrawalPct}%` }}
            className="bg-amber-500 transition-all duration-300"
            title={`Withdrawals: ${withdrawalPct}%`}
          />
        )}
      </div>

      {/* Breakdown Metrics */}
      <div className="grid grid-cols-3 gap-2 pt-1 text-xs">
        <div className="space-y-0.5">
          <div className="flex items-center gap-1 text-muted-foreground text-[11px]">
            <span className="size-2 rounded-full bg-blue-500 shrink-0" />
            <ArrowLeftRight className="size-2.5" />
            <span>Transfer</span>
          </div>
          <div className="font-mono text-xs font-semibold text-foreground">
            {transfers}{" "}
            <span className="text-[10px] text-muted-foreground font-normal">
              ({transferPct}%)
            </span>
          </div>
        </div>

        <div className="space-y-0.5">
          <div className="flex items-center gap-1 text-muted-foreground text-[11px]">
            <span className="size-2 rounded-full bg-emerald-500 shrink-0" />
            <ArrowDownLeft className="size-2.5" />
            <span>Deposit</span>
          </div>
          <div className="font-mono text-xs font-semibold text-foreground">
            {deposits}{" "}
            <span className="text-[10px] text-muted-foreground font-normal">
              ({depositPct}%)
            </span>
          </div>
        </div>

        <div className="space-y-0.5">
          <div className="flex items-center gap-1 text-muted-foreground text-[11px]">
            <span className="size-2 rounded-full bg-amber-500 shrink-0" />
            <ArrowUpRight className="size-2.5" />
            <span>Withdrawal</span>
          </div>
          <div className="font-mono text-xs font-semibold text-foreground">
            {withdrawals}{" "}
            <span className="text-[10px] text-muted-foreground font-normal">
              ({withdrawalPct}%)
            </span>
          </div>
        </div>
      </div>
    </div>
  )
}
