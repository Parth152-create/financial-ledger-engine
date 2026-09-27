import * as React from "react"
import { DataRow } from "@/components/ui/data-row"
import { StatusBadge, type FinancialStatus } from "@/components/ui/status-badge"
import { cn } from "@/lib/utils"

interface AccountMetaProps {
  accountNumber?: string
  accountId?: string
  currency: string
  accountType: string
  status: FinancialStatus | string
  createdAt?: string
  updatedAt?: string
  reconciliationStatus?: FinancialStatus | string
  className?: string
}

export function AccountMeta({
  accountNumber,
  accountId,
  currency,
  accountType,
  status,
  createdAt,
  updatedAt,
  reconciliationStatus = "CONSISTENT",
  className,
}: AccountMetaProps) {
  return (
    <div className={cn("rounded-sm border border-border bg-card p-4 space-y-1 shadow-2xs", className)}>
      <h3 className="text-[17px] font-semibold text-foreground font-sans mb-3 tracking-tight">
        Account Details
      </h3>
      {accountNumber && <DataRow label="Account Number" value={accountNumber} monospace />}
      <DataRow label="Account Type" value={accountType} />
      <DataRow label="Base Currency" value={currency} monospace />
      <DataRow
        label="Status"
        value={<StatusBadge status={status} />}
        monospace={false}
      />
      {reconciliationStatus && (
        <DataRow
          label="Reconciliation"
          value={<StatusBadge status={reconciliationStatus} />}
          monospace={false}
        />
      )}
      {createdAt && <DataRow label="Created Date" value={createdAt} monospace />}
      {updatedAt && <DataRow label="Last Updated" value={updatedAt} monospace />}
      {accountId && <DataRow label="Account ID" value={accountId} monospace />}
    </div>
  )
}
