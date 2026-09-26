"use client"

import * as React from "react"
import { RotateCw, AlertCircle } from "lucide-react"
import { Section } from "@/components/ui/section"
import { Button } from "@/components/ui/button"
import { DataRow } from "@/components/ui/data-row"
import { ReconciliationSummary } from "@/components/reconciliation/reconciliation-summary"
import { ReconciliationTable } from "@/components/reconciliation/reconciliation-table"
import { useReconciliation } from "@/hooks/api/use-reconciliation"
import { useAccounts } from "@/hooks/api/use-accounts"

export default function ReconciliationPage() {
  const {
    data: reconciliationData,
    isLoading: isLoadingRecon,
    isError: isErrorRecon,
    error: reconError,
    refetch,
    isFetching,
  } = useReconciliation()

  const { data: accounts, isLoading: isLoadingAccounts } = useAccounts()

  const primaryCurrency = accounts && accounts.length > 0 ? accounts[0].currency : "INR"

  const handleRunReconciliation = () => {
    refetch()
  }

  return (
    <div className="space-y-6 select-none font-sans">
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 pb-3 border-b border-border/70">
        <div>
          <h1 className="text-[26px] font-semibold tracking-tight text-foreground font-sans leading-tight">
            Reconciliation
          </h1>
          <p className="text-[14px] text-muted-foreground font-sans mt-0.5">
            Verify balance integrity between cached account snapshots and the sum of immutable ledger entries.
          </p>
        </div>

        <div className="flex items-center gap-2">
          <Button
            size="sm"
            onClick={handleRunReconciliation}
            disabled={isFetching}
            className="gap-1.5 text-[13px]"
          >
            <RotateCw className={`size-3.5 ${isFetching ? "animate-spin" : ""}`} />
            <span>{isFetching ? "Reconciling..." : "Run Reconciliation"}</span>
          </Button>
        </div>
      </div>

      {isLoadingRecon ? (
        <div className="space-y-4">
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3.5 animate-pulse">
            {Array.from({ length: 4 }).map((_, i) => (
              <div key={i} className="p-4 border border-border rounded-sm bg-card space-y-2">
                <div className="h-3 w-28 bg-muted rounded-xs" />
                <div className="h-6 w-36 bg-muted/70 rounded-xs" />
                <div className="h-2.5 w-24 bg-muted/50 rounded-xs" />
              </div>
            ))}
          </div>
          <div className="border border-border/70 rounded-sm p-8 bg-card animate-pulse space-y-3">
            <div className="h-4 w-40 bg-muted rounded-xs" />
            <div className="h-24 w-full bg-muted/40 rounded-xs" />
          </div>
        </div>
      ) : isErrorRecon ? (
        <div className="p-8 text-center space-y-3 bg-card border border-destructive/20 rounded-sm">
          <AlertCircle className="size-8 text-destructive mx-auto" />
          <p className="text-base font-semibold text-foreground">
            Reconciliation Execution Failed
          </p>
          <p className="text-xs text-muted-foreground max-w-md mx-auto">
            {reconError?.message ||
              "An unexpected error occurred while communicating with the reconciliation service."}
          </p>
          <div className="pt-2">
            <Button variant="outline" size="sm" onClick={() => refetch()} className="gap-1.5">
              <RotateCw className="size-3.5" />
              <span>Retry Reconciliation</span>
            </Button>
          </div>
        </div>
      ) : reconciliationData ? (
        <div className="space-y-6">
          <ReconciliationSummary
            data={reconciliationData}
            primaryCurrency={primaryCurrency}
          />

          <Section
            title="Account Reconciliation"
            description="Individual account balance comparison against ledger-derived totals."
          >
            <ReconciliationTable
              results={reconciliationData.reconciliationResults}
              accounts={accounts || []}
              isLoading={isLoadingAccounts}
            />
          </Section>
        </div>
      ) : null}

      <Section title="Reconciliation Protocol">
        <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
          <div className="space-y-1">
            <DataRow label="Reconciliation Invariant" value="Snapshot Balance == Sum(Journal Credits - Debits)" />
            <DataRow label="Reconciliation Endpoint" value="GET /api/v1/reconciliation" />
            <DataRow label="Audit Scope" value="Authenticated user checking accounts" />
          </div>
          <div className="space-y-1">
            <DataRow label="Discrepancy Remediation" value="Zero automated mutation (Read-only audit)" />
            <DataRow label="Rounding Tolerance" value="0.0000 (Zero floating-point tolerance)" />
            <DataRow label="Data Authority" value="Immutable append-only ledger entries" />
          </div>
        </div>
      </Section>
    </div>
  )
}
