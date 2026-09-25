import * as React from "react"
import { ArrowRight } from "lucide-react"
import { StatusBadge, type FinancialStatus } from "@/components/ui/status-badge"
import { AmountDisplay } from "@/components/ui/amount-display"
import { TransactionTypeBadge, type TransactionType } from "@/components/ledger/transaction-type-badge"
import { cn } from "@/lib/utils"

export interface LedgerRowProps {
  id: string
  timestamp: string
  type: TransactionType | string
  sourceAccount?: string
  destinationAccount?: string
  direction: "credit" | "debit" | "neutral"
  amount: string | number
  currency?: string
  status: FinancialStatus | string
  className?: string
  onClick?: () => void
}

export function LedgerRow({
  id,
  timestamp,
  type,
  sourceAccount,
  destinationAccount,
  direction,
  amount,
  currency = "INR",
  status,
  className,
  onClick,
}: LedgerRowProps) {
  return (
    <div
      onClick={onClick}
      className={cn(
        "grid grid-cols-12 items-center gap-3 px-3.5 py-2.5 border-b border-border/50 text-xs hover:bg-muted/30 transition-colors select-none font-sans",
        onClick && "cursor-pointer",
        className
      )}
    >
      <div className="col-span-2 flex flex-col font-mono text-[11px] text-muted-foreground leading-tight">
        <span>{timestamp}</span>
        <span className="text-[10px] text-muted-foreground/60 truncate" title={id}>
          {id.slice(0, 8)}
        </span>
      </div>

      <div className="col-span-2">
        <TransactionTypeBadge type={type} />
      </div>

      <div className="col-span-3 flex items-center gap-1.5 font-mono text-xs text-muted-foreground truncate">
        {sourceAccount && <span className="truncate">{sourceAccount}</span>}
        {sourceAccount && destinationAccount && <ArrowRight className="size-3 text-muted-foreground/50 shrink-0" />}
        {destinationAccount && <span className="text-foreground truncate">{destinationAccount}</span>}
      </div>

      <div className="col-span-1 text-center">
        <span
          className={cn(
            "text-[10px] font-mono font-medium px-1.5 py-0.5 rounded-sm border",
            direction === "credit"
              ? "text-emerald-700 dark:text-emerald-400 border-emerald-500/20 bg-emerald-500/5"
              : direction === "debit"
              ? "text-foreground border-border bg-muted/40"
              : "text-muted-foreground border-border bg-muted/20"
          )}
        >
          {direction === "credit" ? "CR" : direction === "debit" ? "DR" : "—"}
        </span>
      </div>

      <div className="col-span-2 text-right">
        <AmountDisplay
          amount={amount}
          currency={currency}
          direction={direction}
          size="sm"
          align="right"
          showSign={direction === "credit"}
        />
      </div>

      <div className="col-span-2 flex justify-end">
        <StatusBadge status={status} />
      </div>
    </div>
  )
}
