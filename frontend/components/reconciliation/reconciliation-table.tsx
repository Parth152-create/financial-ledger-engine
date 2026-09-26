"use client"

import * as React from "react"
import Link from "next/link"
import { Scale, ArrowUpRight } from "lucide-react"
import { StatusBadge } from "@/components/ui/status-badge"
import { AmountDisplay } from "@/components/ui/amount-display"
import { formatDate } from "@/lib/formatters/date"
import { ROUTES } from "@/constants/routes"
import type { ReconciliationResult } from "@/types/reconciliation"
import type { Account } from "@/types/account"
import { cn } from "@/lib/utils"

interface ReconciliationTableProps {
  results: ReconciliationResult[]
  accounts?: Account[]
  isLoading?: boolean
}

export function ReconciliationTable({
  results,
  accounts = [],
  isLoading = false,
}: ReconciliationTableProps) {
  const accountMap = React.useMemo(() => {
    const map = new Map<string, Account>()
    accounts.forEach((acc) => {
      map.set(acc.accountId, acc)
    })
    return map
  }, [accounts])

  if (isLoading) {
    return (
      <div className="border border-border/70 rounded-sm overflow-hidden bg-card font-sans select-none">
        <div className="grid grid-cols-12 gap-3 px-4 py-2.5 bg-muted/40 text-[11px] font-medium text-muted-foreground uppercase tracking-wider border-b border-border/70">
          <span className="col-span-4">Account Reference</span>
          <span className="col-span-2 text-right">Snapshot Balance</span>
          <span className="col-span-2 text-right">Ledger Derived</span>
          <span className="col-span-2 text-right">Difference</span>
          <span className="col-span-2 text-right">Status</span>
        </div>
        <div className="divide-y divide-border/50">
          {Array.from({ length: 4 }).map((_, i) => (
            <div
              key={i}
              className="grid grid-cols-12 items-center gap-3 px-4 py-3 animate-pulse"
            >
              <div className="col-span-4 space-y-1">
                <div className="h-3.5 w-32 bg-muted rounded-xs" />
                <div className="h-2.5 w-24 bg-muted/60 rounded-xs" />
              </div>
              <div className="col-span-2 flex justify-end">
                <div className="h-3.5 w-18 bg-muted rounded-xs" />
              </div>
              <div className="col-span-2 flex justify-end">
                <div className="h-3.5 w-18 bg-muted rounded-xs" />
              </div>
              <div className="col-span-2 flex justify-end">
                <div className="h-3.5 w-14 bg-muted rounded-xs" />
              </div>
              <div className="col-span-2 flex justify-end">
                <div className="h-4 w-20 bg-muted rounded-xs" />
              </div>
            </div>
          ))}
        </div>
      </div>
    )
  }

  if (results.length === 0) {
    return (
      <div className="border border-border/70 rounded-sm overflow-hidden bg-card p-12 text-center space-y-3 font-sans select-none">
        <Scale className="size-8 text-muted-foreground/40 mx-auto" />
        <p className="text-base font-semibold text-foreground">
          No Accounts Reconciled
        </p>
        <p className="text-xs text-muted-foreground max-w-sm mx-auto">
          No checking accounts are currently provisioned for this user. Create an account to begin recording transactions and performing reconciliation.
        </p>
      </div>
    )
  }

  return (
    <div className="border border-border/70 rounded-sm overflow-hidden bg-card font-sans select-none">
      <div className="grid grid-cols-12 gap-3 px-4 py-2.5 bg-muted/40 text-[11px] font-medium text-muted-foreground uppercase tracking-wider border-b border-border/70">
        <span className="col-span-4">Account Reference</span>
        <span className="col-span-2 text-right">Snapshot Balance</span>
        <span className="col-span-2 text-right">Ledger Derived</span>
        <span className="col-span-2 text-right">Difference</span>
        <span className="col-span-2 text-right">Status</span>
      </div>

      <div className="divide-y divide-border/50">
        {results.map((r) => {
          const matchedAccount = accountMap.get(r.accountId)
          const currency = matchedAccount?.currency || "INR"
          const isConsistent = r.status === "CONSISTENT" && Number(r.difference) === 0

          return (
            <div
              key={r.accountId}
              className={cn(
                "grid grid-cols-12 items-center gap-3 px-4 py-3 text-xs hover:bg-muted/30 transition-colors",
                !isConsistent && "bg-destructive/5"
              )}
            >
              <div className="col-span-4 flex flex-col min-w-0">
                <div className="flex items-center gap-1.5">
                  <span className="font-mono font-medium text-foreground truncate">
                    {matchedAccount?.accountNumber || r.accountId}
                  </span>
                  <Link
                    href={`${ROUTES.ACCOUNTS}/${r.accountId}`}
                    className="text-muted-foreground hover:text-foreground inline-flex items-center"
                    title="View Account Detail"
                  >
                    <ArrowUpRight className="size-3" />
                  </Link>
                </div>
                <div className="flex items-center gap-2 text-[10px] text-muted-foreground font-mono truncate">
                  <span title={r.accountId}>{r.accountId.slice(0, 8)}...</span>
                  <span>•</span>
                  <span>Checked: {formatDate(r.reconciledAt)}</span>
                </div>
              </div>

              <div className="col-span-2 text-right">
                <AmountDisplay
                  amount={r.snapshotBalance}
                  currency={currency}
                  size="sm"
                  align="right"
                />
              </div>

              <div className="col-span-2 text-right">
                <AmountDisplay
                  amount={r.ledgerBalance}
                  currency={currency}
                  size="sm"
                  align="right"
                />
              </div>

              <div className="col-span-2 text-right">
                <AmountDisplay
                  amount={r.difference}
                  currency={currency}
                  direction={!isConsistent ? "debit" : "neutral"}
                  size="sm"
                  align="right"
                  showSign={!isConsistent}
                />
              </div>

              <div className="col-span-2 flex justify-end">
                <StatusBadge status={r.status} />
              </div>
            </div>
          )
        })}
      </div>
    </div>
  )
}
