"use client"

import * as React from "react"
import Link from "next/link"
import { CheckCircle2, Copy, Check, Plus, ExternalLink } from "lucide-react"
import { Button } from "@/components/ui/button"
import { StatusBadge } from "@/components/ui/status-badge"
import { AmountDisplay } from "@/components/ui/amount-display"
import { DataRow } from "@/components/ui/data-row"
import { formatDate } from "@/lib/formatters/date"
import { maskAccountNumber } from "@/lib/validators/withdrawal"
import { ROUTES } from "@/constants/routes"
import type { WithdrawalResponse } from "@/types/transaction"
import type { Account } from "@/types/account"

interface WithdrawalResultProps {
  result: WithdrawalResponse
  sourceAccount?: Account | null
  onStartAnother: () => void
}

export function WithdrawalResult({
  result,
  sourceAccount,
  onStartAnother,
}: WithdrawalResultProps) {
  const [copied, setCopied] = React.useState(false)

  const handleCopyId = async () => {
    if (!result.transactionId) return
    try {
      await navigator.clipboard.writeText(result.transactionId)
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    } catch {
      // Clipboard write fallback
    }
  }

  const maskedSource = sourceAccount
    ? maskAccountNumber(sourceAccount.accountNumber)
    : maskAccountNumber(result.sourceAccountId)

  return (
    <div className="space-y-4 font-sans">
      {/* Success Banner */}
      <div className="rounded-sm border border-emerald-500/25 bg-emerald-500/5 p-5 text-center space-y-2">
        <div className="inline-flex items-center justify-center p-2 rounded-full bg-emerald-500/10 text-emerald-600 dark:text-emerald-400 mb-1">
          <CheckCircle2 className="size-6" />
        </div>

        <h3 className="text-[17px] font-semibold tracking-tight text-foreground">
          Withdrawal completed
        </h3>

        <div>
          <AmountDisplay
            amount={result.amount}
            currency={result.currency || "INR"}
            size="xl"
            direction="neutral"
            align="center"
          />
        </div>

        <div className="flex items-center justify-center gap-2 pt-1">
          <span className="text-xs text-muted-foreground font-mono">Status:</span>
          <StatusBadge status={result.status} />
        </div>
      </div>

      {/* Transaction Details */}
      <div className="rounded-sm border border-border/70 divide-y divide-border/60 bg-card overflow-hidden">
        {/* Settlement Source / Destination summary */}
        <div className="p-3.5 grid grid-cols-2 gap-4 bg-muted/20">
          <div>
            <span className="text-[11px] font-medium uppercase tracking-wider text-muted-foreground block">
              Source
            </span>
            <span className="font-semibold text-foreground text-sm block mt-0.5">
              Checking {maskedSource}
            </span>
            <span className="font-mono text-[11px] text-muted-foreground block truncate">
              {sourceAccount?.accountNumber || result.sourceAccountId}
            </span>
          </div>

          <div>
            <span className="text-[11px] font-medium uppercase tracking-wider text-muted-foreground block">
              Destination
            </span>
            <span className="font-semibold text-foreground text-sm block mt-0.5">
              Platform Clearing
            </span>
            <span className="font-mono text-[11px] text-muted-foreground block truncate">
              SYSTEM_CLEARING (•••• 0001)
            </span>
          </div>
        </div>

        {/* Transaction ID with Copy */}
        <div className="p-3.5 flex items-center justify-between gap-3">
          <div className="space-y-0.5 min-w-0">
            <span className="text-[11.5px] font-medium text-muted-foreground block">
              Transaction ID
            </span>
            <span className="font-mono text-xs text-foreground block truncate">
              {result.transactionId}
            </span>
          </div>

          <Button
            type="button"
            variant="outline"
            size="xs"
            onClick={handleCopyId}
            className="gap-1 shrink-0 h-7"
            aria-label="Copy transaction ID"
          >
            {copied ? (
              <>
                <Check className="size-3 text-emerald-600 dark:text-emerald-400" />
                <span className="text-emerald-600 dark:text-emerald-400">Copied</span>
              </>
            ) : (
              <>
                <Copy className="size-3" />
                <span>Copy</span>
              </>
            )}
          </Button>
        </div>

        <DataRow
          label="Timestamp"
          value={formatDate(result.completedAt || result.createdAt)}
        />

        {result.description && (
          <DataRow
            label="Description"
            value={result.description}
          />
        )}

        <DataRow
          label="Authoritative Accounting"
          value="Committed to PostgreSQL Ledger (Balanced Checking-to-Clearing)"
        />
      </div>

      {/* Navigation & Follow-up Actions */}
      <div className="flex flex-col sm:flex-row items-center gap-2.5 pt-2">
        {result.sourceAccountId && (
          <Link
            href={ROUTES.ACCOUNT_DETAILS(result.sourceAccountId)}
            className="w-full sm:w-auto flex-1"
          >
            <Button
              type="button"
              variant="outline"
              size="sm"
              className="w-full gap-1.5 justify-center text-[13px]"
            >
              <span>View Account</span>
              <ExternalLink className="size-3 text-muted-foreground" />
            </Button>
          </Link>
        )}

        <Link href={ROUTES.ACCOUNTS} className="w-full sm:w-auto flex-1">
          <Button
            type="button"
            variant="outline"
            size="sm"
            className="w-full justify-center text-[13px]"
          >
            <span>All Accounts</span>
          </Button>
        </Link>

        <Button
          type="button"
          size="sm"
          onClick={onStartAnother}
          className="w-full sm:w-auto flex-1 gap-1.5 justify-center text-[13px] font-medium"
        >
          <Plus className="size-3.5" />
          <span>New Withdrawal</span>
        </Button>
      </div>
    </div>
  )
}
