"use client"

import * as React from "react"
import { ShieldCheck } from "lucide-react"
import { AmountDisplay } from "@/components/ui/amount-display"
import { maskAccountNumber, calculateEstimatedDepositBalanceAfter } from "@/lib/validators/deposit"
import type { Account } from "@/types/account"

interface DepositPreviewProps {
  destinationAccount?: Account | null
  amount: string
  currency?: string
  description?: string
}

export function DepositPreview({
  destinationAccount,
  amount,
  currency = "INR",
  description,
}: DepositPreviewProps) {
  const cleanAmount = (amount || "").trim()
  const isAmountPositive = /^\d+(\.\d{1,4})?$/.test(cleanAmount) && parseFloat(cleanAmount) > 0
  const isCurrencyValid = !destinationAccount || !currency || currency === destinationAccount.currency

  const balanceAfter = React.useMemo(() => {
    if (!destinationAccount || !isAmountPositive || !isCurrencyValid) return null
    return calculateEstimatedDepositBalanceAfter(destinationAccount.balance, cleanAmount)
  }, [destinationAccount, cleanAmount, isAmountPositive, isCurrencyValid])

  return (
    <div className="rounded-sm border border-border/70 overflow-hidden divide-y divide-border/60 bg-card font-sans">
      <div className="p-3 bg-muted/30 flex items-center justify-between text-xs">
        <span className="font-semibold text-foreground tracking-tight">Deposit Preview</span>
        <span className="text-[11px] text-muted-foreground">Live Calculation</span>
      </div>

      {/* From Clearing */}
      <div className="p-3 space-y-1">
        <span className="text-[11px] font-medium uppercase tracking-wider text-muted-foreground block">
          From
        </span>
        <div className="flex items-baseline justify-between pt-0.5">
          <div className="space-y-0.5">
            <span className="font-semibold text-[13.5px] text-foreground block">
              Platform Clearing
            </span>
            <span className="font-mono text-xs text-muted-foreground">
              SYSTEM_CLEARING (•••• 0001)
            </span>
          </div>
          <div className="text-right">
            <span className="text-[10.5px] text-muted-foreground block">Settlement Source</span>
            <span className="text-xs font-medium text-foreground">External Gateway</span>
          </div>
        </div>
      </div>

      {/* To Destination Account */}
      <div className="p-3 space-y-1">
        <span className="text-[11px] font-medium uppercase tracking-wider text-muted-foreground block">
          To
        </span>
        <div className="flex items-baseline justify-between pt-0.5">
          <div className="space-y-0.5">
            <span className="font-semibold text-[13.5px] text-foreground block">Checking</span>
            <span className="font-mono text-xs text-muted-foreground">
              {destinationAccount ? maskAccountNumber(destinationAccount.accountNumber) : "•••• ----"}
            </span>
          </div>
          {destinationAccount && (
            <div className="text-right">
              <span className="text-[10.5px] text-muted-foreground block">Current Balance</span>
              <AmountDisplay
                amount={destinationAccount.balance}
                currency={destinationAccount.currency}
                size="sm"
                align="right"
              />
            </div>
          )}
        </div>
      </div>

      {/* Deposit Amount */}
      <div className="p-3 flex items-center justify-between bg-muted/20">
        <div>
          <span className="text-[11px] font-medium uppercase tracking-wider text-muted-foreground block">
            Amount
          </span>
          <span className="text-[13px] font-medium text-foreground">Principal Deposit</span>
        </div>
        <AmountDisplay
          amount={isAmountPositive ? cleanAmount : "0.00"}
          currency={currency || "INR"}
          size="lg"
          align="right"
        />
      </div>

      {/* Optional Description */}
      {description && description.trim() && (
        <div className="p-3 flex items-start justify-between gap-4">
          <span className="text-[11px] font-medium uppercase tracking-wider text-muted-foreground shrink-0 mt-0.5">
            Description
          </span>
          <span className="text-xs text-foreground font-sans text-right break-words max-w-[240px]">
            {description.trim()}
          </span>
        </div>
      )}

      {/* Est. Destination Balance After */}
      {balanceAfter && (
        <div className="p-3 bg-muted/15 space-y-1">
          <div className="flex items-baseline justify-between">
            <span className="text-xs text-muted-foreground">Est. Destination Balance After:</span>
            <span className="font-mono text-[13px] font-medium text-foreground">
              {balanceAfter.formatted}
            </span>
          </div>
          <p className="text-[10.5px] text-muted-foreground/75 leading-tight">
            Client-side estimation only. The authoritative balance is committed atomically by the ledger engine.
          </p>
        </div>
      )}

      <div className="p-2.5 bg-muted/10 text-[11px] text-muted-foreground flex items-center gap-1.5 border-t border-border/50">
        <ShieldCheck className="size-3.5 text-primary shrink-0" />
        <span>Atomic double-entry transaction · Single authoritative PostgreSQL commit</span>
      </div>
    </div>
  )
}
