"use client"

import * as React from "react"
import Link from "next/link"
import { Scale, ArrowUpRight, BookOpen } from "lucide-react"
import { StatusBadge } from "@/components/ui/status-badge"
import { AmountDisplay } from "@/components/ui/amount-display"
import { formatDate } from "@/lib/formatters/date"
import { maskAccountNumber, SYSTEM_CLEARING_ID } from "@/lib/formatters/reconciliation"
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
          <span className="col-span-12 sm:col-span-3">Account Reference</span>
          <span className="col-span-6 sm:col-span-2 text-right">Snapshot Balance</span>
          <span className="col-span-6 sm:col-span-3 text-right">Ledger Derived</span>
          <span className="col-span-4 sm:col-span-2 text-right">Difference</span>
          <span className="col-span-4 sm:col-span-1 text-center">Status</span>
          <span className="col-span-4 sm:col-span-1 text-right">Actions</span>
        </div>
        <div className="divide-y divide-border/50">
          {Array.from({ length: 4 }).map((_, i) => (
            <div
              key={i}
              className="grid grid-cols-12 items-center gap-3 px-4 py-3.5 animate-pulse"
            >
              <div className="col-span-12 sm:col-span-3 space-y-1">
                <div className="h-3.5 w-32 bg-muted rounded-xs" />
                <div className="h-2.5 w-24 bg-muted/60 rounded-xs" />
              </div>
              <div className="col-span-6 sm:col-span-2 flex justify-end">
                <div className="h-4 w-20 bg-muted rounded-xs" />
              </div>
              <div className="col-span-6 sm:col-span-3 flex justify-end">
                <div className="h-4 w-24 bg-muted rounded-xs" />
              </div>
              <div className="col-span-4 sm:col-span-2 flex justify-end">
                <div className="h-4 w-16 bg-muted rounded-xs" />
              </div>
              <div className="col-span-4 sm:col-span-1 flex justify-center">
                <div className="h-4 w-16 bg-muted rounded-xs" />
              </div>
              <div className="col-span-4 sm:col-span-1 flex justify-end">
                <div className="h-4 w-12 bg-muted rounded-xs" />
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
      <div className="hidden sm:grid grid-cols-12 gap-3 px-4 py-2.5 bg-muted/40 text-[11px] font-medium text-muted-foreground uppercase tracking-wider border-b border-border/70">
        <span className="col-span-3">Account Reference</span>
        <span className="col-span-2 text-right">Snapshot Balance</span>
        <span className="col-span-3 text-right">Ledger Derived</span>
        <span className="col-span-2 text-right">Difference</span>
        <span className="col-span-1 text-center">Status</span>
        <span className="col-span-1 text-right">Actions</span>
      </div>

      <div className="divide-y divide-border/50">
        {results.map((r) => {
          const matchedAccount = accountMap.get(r.accountId)
          const currency = matchedAccount?.currency || "INR"
          const isConsistent = r.status === "CONSISTENT" && Number(r.difference) === 0
          const isPlatformClearing = r.accountId === SYSTEM_CLEARING_ID
          const maskedDisplay = isPlatformClearing
            ? "Platform Clearing"
            : maskAccountNumber(matchedAccount?.accountNumber || r.accountId)

          return (
            <div
              key={r.accountId}
              className={cn(
                "grid grid-cols-1 sm:grid-cols-12 items-start sm:items-center gap-2 sm:gap-3 px-4 py-3 text-xs hover:bg-muted/30 transition-colors",
                !isConsistent && "bg-destructive/5"
              )}
            >
              {/* Account Reference */}
              <div className="sm:col-span-3 flex flex-col min-w-0">
                <div className="flex items-center gap-1.5">
                  <span className="font-mono font-medium text-foreground truncate">
                    {maskedDisplay}
                  </span>
                  {!isPlatformClearing && (
                    <Link
                      href={`${ROUTES.ACCOUNTS}/${r.accountId}`}
                      className="text-muted-foreground hover:text-foreground inline-flex items-center transition-colors"
                      title="View Account Details"
                    >
                      <ArrowUpRight className="size-3" />
                    </Link>
                  )}
                </div>
                <div className="flex items-center gap-1.5 text-[10px] text-muted-foreground font-mono truncate">
                  <span title={r.accountId}>{r.accountId.slice(0, 8)}...</span>
                  <span>•</span>
                  <span>{formatDate(r.reconciledAt)}</span>
                </div>
              </div>

              {/* Snapshot Balance */}
              <div className="sm:col-span-2 flex sm:flex-col justify-between sm:justify-center items-baseline sm:items-end text-right">
                <span className="sm:hidden text-[11px] text-muted-foreground">Snapshot:</span>
                <AmountDisplay
                  amount={r.snapshotBalance}
                  currency={currency}
                  size="sm"
                  align="right"
                />
              </div>

              {/* Ledger Derived with Credits / Debits */}
              <div className="sm:col-span-3 flex sm:flex-col justify-between sm:justify-center items-baseline sm:items-end text-right">
                <span className="sm:hidden text-[11px] text-muted-foreground">Ledger Derived:</span>
                <div className="flex flex-col items-end">
                  <AmountDisplay
                    amount={r.ledgerBalance}
                    currency={currency}
                    size="sm"
                    align="right"
                  />
                  <div className="text-[10px] text-muted-foreground font-mono flex items-center gap-1.5">
                    <span className="text-emerald-700 dark:text-emerald-400">
                      Cr: {Number(r.totalCredits).toFixed(2)}
                    </span>
                    <span>/</span>
                    <span className="text-rose-700 dark:text-rose-400">
                      Dr: {Number(r.totalDebits).toFixed(2)}
                    </span>
                  </div>
                </div>
              </div>

              {/* Difference */}
              <div className="sm:col-span-2 flex sm:flex-col justify-between sm:justify-center items-baseline sm:items-end text-right">
                <span className="sm:hidden text-[11px] text-muted-foreground">Difference:</span>
                <AmountDisplay
                  amount={r.difference}
                  currency={currency}
                  direction={!isConsistent ? "debit" : "neutral"}
                  size="sm"
                  align="right"
                  showSign={!isConsistent}
                />
              </div>

              {/* Status Badge */}
              <div className="sm:col-span-1 flex justify-between sm:justify-center items-center">
                <span className="sm:hidden text-[11px] text-muted-foreground">Status:</span>
                <StatusBadge status={r.status} />
              </div>

              {/* Actions */}
              <div className="sm:col-span-1 flex items-center justify-end gap-1.5 pt-1 sm:pt-0 border-t sm:border-t-0 border-border/40">
                <Link
                  href={`${ROUTES.LEDGER}?accountId=${r.accountId}`}
                  className="inline-flex items-center gap-1 px-2 py-1 text-[11px] font-medium rounded-xs border border-border/60 hover:bg-muted text-muted-foreground hover:text-foreground transition-colors"
                  title="Inspect Ledger Entries"
                >
                  <BookOpen className="size-3" />
                  <span className="hidden lg:inline">Ledger</span>
                </Link>
                {!isPlatformClearing && (
                  <Link
                    href={`${ROUTES.ACCOUNTS}/${r.accountId}`}
                    className="inline-flex items-center p-1 text-[11px] rounded-xs border border-border/60 hover:bg-muted text-muted-foreground hover:text-foreground transition-colors"
                    title="View Account Details"
                  >
                    <ArrowUpRight className="size-3" />
                  </Link>
                )}
              </div>
            </div>
          )
        })}
      </div>
    </div>
  )
}
