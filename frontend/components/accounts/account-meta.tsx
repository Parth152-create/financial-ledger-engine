import * as React from "react"
import { DataRow } from "@/components/ui/data-row"
import { StatusBadge, type FinancialStatus } from "@/components/ui/status-badge"
import { cn } from "@/lib/utils"

interface AccountMetaProps {
  accountId: string
  currency: string
  accountType: string
  status: FinancialStatus | string
  createdAt?: string
  reconciliationStatus?: FinancialStatus | string
  className?: string
}

export function AccountMeta({
  accountId,
  currency,
  accountType,
  status,
  createdAt,
  reconciliationStatus = "CONSISTENT",
  className,
}: AccountMetaProps) {
  return (
    <div className={cn("rounded-sm border border-border bg-card p-4 space-y-1 shadow-2xs", className)}>
      <h3 className="text-xs font-semibold text-foreground font-sans mb-3">
        Account Details
      </h3>
      <DataRow label="Account ID" value={accountId} monospace />
      <DataRow label="Account Type" value={accountType} />
      <DataRow label="Base Currency" value={currency} monospace />
      <DataRow
        label="Status"
        value={<StatusBadge status={status} />}
        monospace={false}
      />
      <DataRow
        label="Reconciliation"
        value={<StatusBadge status={reconciliationStatus} />}
        monospace={false}
      />
      {createdAt && <DataRow label="Created Date" value={createdAt} monospace />}
    </div>
  )
}
