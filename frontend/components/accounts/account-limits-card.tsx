"use client"

import * as React from "react"
import { ShieldCheck, ArrowLeftRight, ArrowDownLeft, ArrowUpRight, Scale } from "lucide-react"
import { useAccountLimits } from "@/hooks/api/use-accounts"
import { formatINR } from "@/lib/formatters/currency"
import { cn } from "@/lib/utils"
import type { TransactionType } from "@/types/transaction"
import type { PolicyLimitSummary } from "@/types/policy"

interface AccountLimitsCardProps {
  accountId: string
  className?: string
}

const TABS: { type: TransactionType; label: string; icon: React.ComponentType<{ className?: string }> }[] = [
  { type: "TRANSFER", label: "Transfer", icon: ArrowLeftRight },
  { type: "DEPOSIT", label: "Deposit", icon: ArrowDownLeft },
  { type: "WITHDRAWAL", label: "Withdrawal", icon: ArrowUpRight },
]

export function AccountLimitsCard({ accountId, className }: AccountLimitsCardProps) {
  const [selectedType, setSelectedType] = React.useState<TransactionType>("TRANSFER")
  const { data: limits, isLoading, isError } = useAccountLimits(accountId)

  if (isLoading) {
    return (
      <div className={cn("rounded-sm border border-border bg-card p-4 space-y-3 shadow-2xs", className)}>
        <div className="flex items-center gap-2">
          <div className="size-4 bg-muted rounded-xs animate-pulse" />
          <div className="h-4 w-32 bg-muted rounded-xs animate-pulse" />
        </div>
        <div className="h-20 w-full bg-muted/40 rounded-xs animate-pulse" />
      </div>
    )
  }

  if (isError || !limits || limits.length === 0) {
    return null
  }

  const currentSummary: PolicyLimitSummary | undefined = limits.find(
    (l) => l.transactionType === selectedType
  ) || limits[0]

  const dailyAmountPct =
    currentSummary?.dailyAmountLimit && currentSummary.dailyAmountLimit > 0
      ? Math.min(100, Math.round((currentSummary.dailyAmountUsed / currentSummary.dailyAmountLimit) * 100))
      : 0

  const dailyCountPct =
    currentSummary?.dailyCountLimit && currentSummary.dailyCountLimit > 0
      ? Math.min(100, Math.round((currentSummary.dailyCountUsed / currentSummary.dailyCountLimit) * 100))
      : 0

  return (
    <div className={cn("rounded-sm border border-border bg-card p-4 space-y-3 shadow-2xs font-sans", className)}>
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <ShieldCheck className="size-4 text-primary" />
          <h3 className="text-[15px] font-semibold text-foreground tracking-tight">
            Policies & Limits
          </h3>
        </div>
        <span className="text-[11px] font-mono text-muted-foreground uppercase">
          Daily Quota
        </span>
      </div>

      <div className="flex items-center gap-1 border-b border-border/60 pb-2">
        {TABS.map((tab) => {
          const Icon = tab.icon
          const isActive = selectedType === tab.type
          return (
            <button
              key={tab.type}
              type="button"
              onClick={() => setSelectedType(tab.type)}
              className={cn(
                "flex items-center gap-1.5 px-2.5 py-1 text-xs font-medium rounded-xs transition-colors",
                isActive
                  ? "bg-muted text-foreground font-semibold"
                  : "text-muted-foreground hover:text-foreground hover:bg-muted/40"
              )}
            >
              <Icon className="size-3" />
              <span>{tab.label}</span>
            </button>
          )
        })}
      </div>

      {currentSummary && (
        <div className="space-y-3 text-xs">
          {/* Max Transaction Amount */}
          <div className="flex items-center justify-between py-1 border-b border-border/40">
            <span className="text-muted-foreground">Max Per Transaction</span>
            <span className="font-mono font-medium text-foreground">
              {currentSummary.maxTransactionAmount != null
                ? formatINR(currentSummary.maxTransactionAmount)
                : "No limit"}
            </span>
          </div>

          {/* Daily Amount Limit & Usage */}
          <div className="space-y-1.5 py-1 border-b border-border/40">
            <div className="flex items-center justify-between">
              <span className="text-muted-foreground">Daily Amount Quota</span>
              <span className="font-mono text-muted-foreground">
                {currentSummary.dailyAmountLimit != null ? (
                  <>
                    <span className="text-foreground font-medium">
                      {formatINR(currentSummary.dailyAmountUsed)}
                    </span>
                    {" / "}
                    {formatINR(currentSummary.dailyAmountLimit)}
                  </>
                ) : (
                  "Unlimited"
                )}
              </span>
            </div>
            {currentSummary.dailyAmountLimit != null && (
              <div className="space-y-1">
                <div className="h-1.5 w-full bg-muted rounded-full overflow-hidden">
                  <div
                    className={cn(
                      "h-full rounded-full transition-all duration-300",
                      dailyAmountPct >= 90
                        ? "bg-destructive"
                        : dailyAmountPct >= 70
                        ? "bg-amber-500"
                        : "bg-primary"
                    )}
                    style={{ width: `${dailyAmountPct}%` }}
                  />
                </div>
                <div className="flex justify-between text-[11px] text-muted-foreground font-mono">
                  <span>{dailyAmountPct}% used today</span>
                  <span>
                    Remaining:{" "}
                    <strong className={cn("text-foreground", currentSummary.dailyAmountRemaining === 0 && "text-destructive font-semibold")}>
                      {currentSummary.dailyAmountRemaining != null
                        ? formatINR(currentSummary.dailyAmountRemaining)
                        : "Unlimited"}
                      {currentSummary.dailyAmountRemaining === 0 ? " (Limit reached)" : ""}
                    </strong>
                  </span>
                </div>
              </div>
            )}
          </div>

          {/* Daily Transaction Count & Usage */}
          <div className="space-y-1.5 py-1 border-b border-border/40">
            <div className="flex items-center justify-between">
              <span className="text-muted-foreground">Daily Transaction Count</span>
              <span className="font-mono text-muted-foreground">
                {currentSummary.dailyCountLimit != null ? (
                  <>
                    <span className="text-foreground font-medium">
                      {currentSummary.dailyCountUsed}
                    </span>
                    {" / "}
                    {currentSummary.dailyCountLimit} txns
                  </>
                ) : (
                  "Unlimited"
                )}
              </span>
            </div>
            {currentSummary.dailyCountLimit != null && (
              <div className="space-y-1">
                <div className="h-1.5 w-full bg-muted rounded-full overflow-hidden">
                  <div
                    className={cn(
                      "h-full rounded-full transition-all duration-300",
                      dailyCountPct >= 90
                        ? "bg-destructive"
                        : dailyCountPct >= 70
                        ? "bg-amber-500"
                        : "bg-primary"
                    )}
                    style={{ width: `${dailyCountPct}%` }}
                  />
                </div>
                <div className="flex justify-between text-[11px] text-muted-foreground font-mono">
                  <span>{dailyCountPct}% consumed</span>
                  <span>
                    Remaining:{" "}
                    <strong className={cn("text-foreground", currentSummary.dailyCountRemaining === 0 && "text-destructive font-semibold")}>
                      {currentSummary.dailyCountRemaining != null
                        ? `${currentSummary.dailyCountRemaining} txns`
                        : "Unlimited"}
                      {currentSummary.dailyCountRemaining === 0 ? " (Limit reached)" : ""}
                    </strong>
                  </span>
                </div>
              </div>
            )}
          </div>

          {/* Balance Cap */}
          {(currentSummary.balanceLimit != null || currentSummary.accountBalanceLimit != null) && (
            <div className="flex items-center justify-between py-1">
              <div className="flex items-center gap-1.5 text-muted-foreground">
                <Scale className="size-3.5" />
                <span>Account Balance Cap</span>
              </div>
              <div className="text-right font-mono">
                <div className="font-medium text-foreground">
                  {formatINR(currentSummary.balanceLimit ?? currentSummary.accountBalanceLimit ?? 0)}
                </div>
                {(currentSummary.balanceRemaining != null || currentSummary.balanceCapacityRemaining != null) && (
                  <div className={cn("text-[10.5px]", (currentSummary.balanceRemaining ?? currentSummary.balanceCapacityRemaining) === 0 ? "text-destructive font-medium" : "text-muted-foreground")}>
                    Capacity: {formatINR(currentSummary.balanceRemaining ?? currentSummary.balanceCapacityRemaining ?? 0)}
                    {(currentSummary.balanceRemaining ?? currentSummary.balanceCapacityRemaining) === 0 ? " (Limit reached)" : ""}
                  </div>
                )}
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  )
}
