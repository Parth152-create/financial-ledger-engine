"use client"

import * as React from "react"
import { AlertTriangle, CheckCircle2, ShieldCheck, Clock } from "lucide-react"
import { StatusBadge } from "@/components/ui/status-badge"
import { AmountDisplay } from "@/components/ui/amount-display"
import { formatDate } from "@/lib/formatters/date"
import { calculateReconciliationAggregates } from "@/lib/formatters/reconciliation"
import type { OverallReconciliation } from "@/types/reconciliation"

interface ReconciliationSummaryProps {
  data: OverallReconciliation
  primaryCurrency?: string
}

export function ReconciliationSummary({
  data,
  primaryCurrency = "INR",
}: ReconciliationSummaryProps) {
  const isConsistent = data.discrepancyCount === 0 && data.totalAccountsChecked > 0
  const hasDiscrepancy = data.discrepancyCount > 0
  const isEmpty = data.totalAccountsChecked === 0

  const { totalSnapshot, totalLedger, totalDifference } =
    calculateReconciliationAggregates(data.reconciliationResults)

  return (
    <div className="space-y-4 font-sans select-none">
      {hasDiscrepancy && (
        <div
          role="alert"
          aria-live="polite"
          className="flex items-start justify-between gap-3 p-4 rounded-sm bg-destructive/10 border border-destructive/30 text-destructive"
        >
          <div className="flex items-start gap-3">
            <AlertTriangle className="size-5 shrink-0 mt-0.5" />
            <div className="space-y-1 text-xs">
              <p className="font-semibold text-sm">
                Ledger Discrepancy Detected ({data.discrepancyCount}{" "}
                {data.discrepancyCount === 1 ? "account" : "accounts"} affected)
              </p>
              <p className="text-destructive/90 leading-relaxed max-w-2xl">
                One or more account balances diverge from the immutable double-entry ledger source of truth.
                Discrepancies are flagged authoritatively by the backend and are not automatically repaired.
              </p>
            </div>
          </div>
          <div className="hidden sm:flex flex-col items-end shrink-0 pl-4 border-l border-destructive/20 text-xs">
            <span className="text-[11px] font-medium uppercase tracking-wider text-destructive/80">
              Unbalanced Variance
            </span>
            <div className="pt-0.5 font-mono font-bold text-sm text-destructive">
              <AmountDisplay
                amount={totalDifference}
                currency={primaryCurrency}
                direction="debit"
                size="sm"
              />
            </div>
            <span className="text-[10px] text-destructive/70 mt-0.5">
              Audited: {formatDate(data.reconciledAt)}
            </span>
          </div>
        </div>
      )}

      {isConsistent && (
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2 p-3.5 rounded-sm bg-emerald-500/10 border border-emerald-500/20 text-emerald-800 dark:text-emerald-400 text-xs">
          <div className="flex items-center gap-2.5">
            <CheckCircle2 className="size-4 shrink-0 text-emerald-600 dark:text-emerald-400" />
            <span className="font-medium">
              Account balances match ledger-derived balances. No balance discrepancies detected across {data.totalAccountsChecked} checking account{data.totalAccountsChecked === 1 ? "" : "s"}.
            </span>
          </div>
          <div className="flex items-center gap-1.5 text-[11px] text-emerald-700/80 dark:text-emerald-400/80 pl-6 sm:pl-0 shrink-0 font-mono">
            <Clock className="size-3" />
            <span>Audited: {formatDate(data.reconciledAt)}</span>
          </div>
        </div>
      )}

      {isEmpty && (
        <div className="flex items-center gap-2.5 p-3.5 rounded-sm bg-muted/50 border border-border/70 text-muted-foreground text-xs">
          <ShieldCheck className="size-4 shrink-0" />
          <span>
            No accounts provisioned. Create checking accounts to execute financial operations and audit balance integrity.
          </span>
        </div>
      )}

      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3.5">
        <div className="p-3.5 border border-border/80 rounded-sm bg-card space-y-1 shadow-2xs">
          <div className="flex items-center justify-between">
            <span className="text-[12px] font-medium text-muted-foreground uppercase tracking-wider">
              Overall Status
            </span>
            <StatusBadge status={hasDiscrepancy ? "DISCREPANCY" : "CONSISTENT"} />
          </div>
          <div className="text-xl font-bold tracking-tight text-foreground font-mono pt-1">
            {data.consistentAccounts} / {data.totalAccountsChecked}
          </div>
          <span className="text-[11px] text-muted-foreground block">
            {hasDiscrepancy
              ? `${data.discrepancyCount} account${data.discrepancyCount === 1 ? "" : "s"} with variance`
              : "Accounts fully consistent"}
          </span>
        </div>

        <div className="p-3.5 border border-border/80 rounded-sm bg-card space-y-1 shadow-2xs">
          <span className="text-[12px] font-medium text-muted-foreground uppercase tracking-wider block">
            Recorded Balance (Snapshot)
          </span>
          <div className="pt-1">
            <AmountDisplay
              amount={totalSnapshot}
              currency={primaryCurrency}
              size="lg"
            />
          </div>
          <span className="text-[11px] text-muted-foreground block">
            Sum of account balance snapshots
          </span>
        </div>

        <div className="p-3.5 border border-border/80 rounded-sm bg-card space-y-1 shadow-2xs">
          <span className="text-[12px] font-medium text-muted-foreground uppercase tracking-wider block">
            Ledger-Derived Balance
          </span>
          <div className="pt-1">
            <AmountDisplay
              amount={totalLedger}
              currency={primaryCurrency}
              size="lg"
            />
          </div>
          <span className="text-[11px] text-muted-foreground block">
            Authoritative journal sum (Credits − Debits)
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
              direction={hasDiscrepancy ? "debit" : "neutral"}
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
