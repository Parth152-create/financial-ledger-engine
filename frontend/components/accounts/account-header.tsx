import * as React from "react"
import { Landmark } from "lucide-react"
import { StatusBadge, type FinancialStatus } from "@/components/ui/status-badge"
import { AmountDisplay } from "@/components/ui/amount-display"
import { cn } from "@/lib/utils"

interface AccountHeaderProps {
  accountId: string
  accountNumber?: string
  accountType?: string
  currency?: string
  status: FinancialStatus | string
  balance?: string | number
  className?: string
  actions?: React.ReactNode
}

export function AccountHeader({
  accountId,
  accountNumber,
  accountType = "Checking",
  currency = "INR",
  status,
  balance,
  className,
  actions,
}: AccountHeaderProps) {
  return (
    <div
      className={cn(
        "rounded-sm border border-border bg-card p-4 text-foreground flex flex-col md:flex-row md:items-center justify-between gap-4 select-none shadow-2xs",
        className
      )}
    >
      <div className="flex items-start gap-3 min-w-0">
        <div className="size-9 rounded-sm border border-border bg-muted/60 flex items-center justify-center shrink-0">
          <Landmark className="size-4 text-muted-foreground" />
        </div>
        <div className="space-y-1 min-w-0">
          <div className="flex items-center gap-2 flex-wrap">
            <span className="font-mono text-[15px] font-semibold tracking-tight">
              {accountNumber || accountId}
            </span>
            <span className="text-[13.5px] font-sans text-muted-foreground">
              {accountType}
            </span>
            <span className="text-xs font-mono uppercase px-1.5 py-0.5 rounded-sm border border-border/80 bg-muted/30 text-muted-foreground">
              {currency}
            </span>
            <StatusBadge status={status} />
          </div>
          <p className="text-[13px] text-muted-foreground font-mono truncate" title={accountId}>
            {accountId}
          </p>
        </div>
      </div>

      <div className="flex items-center justify-between md:justify-end gap-6 pt-3 md:pt-0 border-t md:border-t-0 border-border/50">
        {balance !== undefined && (
          <div className="text-right">
            <span className="text-[13px] text-muted-foreground font-sans block mb-0.5">
              Available Balance
            </span>
            <AmountDisplay amount={balance} currency={currency} size="lg" align="right" />
          </div>
        )}
        {actions && <div className="flex items-center gap-2">{actions}</div>}
      </div>
    </div>
  )
}
