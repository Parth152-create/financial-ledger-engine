"use client"

import * as React from "react"
import { AlertTriangle, CheckCircle2 } from "lucide-react"
import { StatusBadge } from "@/components/ui/status-badge"
import { AmountDisplay } from "@/components/ui/amount-display"
import { formatDate } from "@/lib/formatters/date"
import type { OverallReconciliation } from "@/types/reconciliation"

interface ReconciliationSummaryProps {
  data: OverallReconciliation
  primaryCurrency?: string
}

export function ReconciliationSummary({
  data,
  primaryCurrency = "INR",
}: ReconciliationSummaryProps) {
  const isConsistent = data.discrepancyCount === 0

  // Calculate snapshot and ledger aggregate sums
  const totalSnapshot = data.reconciliationResults.reduce(
    (sum, r) => sum + Number(r.snapshotBalance || 0),
    0
  )
  const totalLedger = data.reconciliationResults.reduce(
    (sum, r) => sum + Number(r.ledgerBalance || 0),
    0
  )
  const totalDifference = data.reconciliationResults.reduce(
    (sum, r) => sum + Math.abs(Number(r.difference || 0)),
    0
  )

  return (
    <div className="space-y-4 font-sans select-none">
      {!isConsistent && (
        <div
          role="alert"
          className="flex items-start gap-3 p-3.5 rounded-sm bg-destructive/10 border border-destructive/30 text-destructive"
        >
          <AlertTriangle className="size-5 shrink-0 mt-0.5" />
          <div className="space-y-1 text-xs">
            <p className="font-semibold text-sm">
              Ledger Discrepancy Detected ({data.discrepancyCount}{" "}
              {data.discrepancyCount === 1 ? "account" : "accounts"})
            </p>
            <p className="text-destructive/90 leading-relaxed">
              One or more accounts diverge from the immutable double-entry ledger source of truth.
              Discrepancies are flagged authoritatively by the backend and are not automatically repaired.
            </p>
          </div>
        </div>
      )}

      {isConsistent && data.totalAccountsChecked > 0 && (
        <div className="flex items-center gap-2.5 p-3 rounded-sm bg-emerald-500/10 border border-emerald-500/20 text-emerald-800 dark:text-emerald-400 text-xs">
          <CheckCircle2 className="size-4 shrink-0" />
          <span>
            All {data.totalAccountsChecked} checking account
            {data.totalAccountsChecked === 1 ? "" : "s"} perfectly match immutable ledger entries.
          </span>
        </div>
      )}

      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3.5">
        <div className="p-3.5 border border-border/80 rounded-sm bg-card space-y-1 shadow-2xs">
          <div className="flex items-center justify-between">
            <span className="text-[12px] font-medium text-muted-foreground uppercase tracking-wider">
              Overall Status
            </span>
            <StatusBadge status={isConsistent ? "CONSISTENT" : "DISCREPANCY"} />
          </div>
          <div className="text-xl font-bold tracking-tight text-foreground font-mono pt-1">
            {data.consistentAccounts} / {data.totalAccountsChecked}
          </div>
          <span className="text-[11px] text-muted-foreground block">
            Accounts fully consistent
          </span>
        </div>

        <div className="p-3.5 border border-border/80 rounded-sm bg-card space-y-1 shadow-2xs">
          <span className="text-[12px] font-medium text-muted-foreground uppercase tracking-wider block">
            Cached Snapshot Total
          </span>
          <div className="pt-1">
            <AmountDisplay
              amount={totalSnapshot}
              currency={primaryCurrency}
              size="lg"
            />
          </div>
          <span className="text-[11px] text-muted-foreground block">
            Aggregate snapshot balance
          </span>
        </div>

        <div className="p-3.5 border border-border/80 rounded-sm bg-card space-y-1 shadow-2xs">
          <span className="text-[12px] font-medium text-muted-foreground uppercase tracking-wider block">
            Ledger-Derived Total
          </span>
          <div className="pt-1">
            <AmountDisplay
              amount={totalLedger}
              currency={primaryCurrency}
              size="lg"
            />
          </div>
          <span className="text-[11px] text-muted-foreground block">
            Authoritative journal sum (Credits - Debits)
          </span>
        </div>

        <div className="p-3.5 border border-border/80 rounded-sm bg-card space-y-1 shadow-2xs">
          <div className="flex items-center justify-between">
            <span className="text-[12px] font-medium text-muted-foreground uppercase tracking-wider block">
              Net Discrepancy
            </span>
            <span className="text-[10px] font-mono text-muted-foreground">
              {formatDate(data.reconciledAt)}
            </span>
          </div>
          <div className="pt-1">
            <AmountDisplay
              amount={totalDifference}
              currency={primaryCurrency}
              direction={totalDifference > 0 ? "debit" : "neutral"}
              size="lg"
            />
          </div>
          <span className="text-[11px] text-muted-foreground block">
            {totalDifference === 0 ? "Zero variance baseline" : "Unbalanced ledger variance"}
          </span>
        </div>
      </div>
    </div>
  )
}
