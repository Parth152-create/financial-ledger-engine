"use client"

import * as React from "react"
import { X, ArrowRight, Copy, Check, FileText } from "lucide-react"
import { Button } from "@/components/ui/button"
import { StatusBadge } from "@/components/ui/status-badge"
import { AmountDisplay } from "@/components/ui/amount-display"
import { TransactionTypeBadge } from "@/components/ledger/transaction-type-badge"
import { formatDate } from "@/lib/formatters/date"
import type { TransactionHistoryItem } from "@/types/transaction"
import type { StatementEntry } from "@/types/statement"
import { cn } from "@/lib/utils"

export type TransactionDetailData =
  | TransactionHistoryItem
  | StatementEntry
  | (Partial<TransactionHistoryItem> & {
      transactionId: string
      amount: number
      currency: string
      direction: "DEBIT" | "CREDIT"
      status: string
      createdAt: string
      transactionType: "TRANSFER" | "DEPOSIT" | "WITHDRAWAL"
      sourceAccountId?: string
      destinationAccountId?: string
      balanceAfter?: number
      initiatedByUserId?: string | null
      description?: string | null
      completedAt?: string | null
    })

interface TransactionDetailDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  transaction: TransactionDetailData | null
}

export function TransactionDetailDialog({
  open,
  onOpenChange,
  transaction,
}: TransactionDetailDialogProps) {
  const [copied, setCopied] = React.useState(false)

  const handleClose = () => {
    setCopied(false)
    onOpenChange(false)
  }

  React.useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape" && open) {
        setCopied(false)
        onOpenChange(false)
      }
    }
    window.addEventListener("keydown", handleKeyDown)
    return () => window.removeEventListener("keydown", handleKeyDown)
  }, [open, onOpenChange])

  if (!open || !transaction) return null

  const copyToClipboard = async (text: string) => {
    try {
      await navigator.clipboard.writeText(text)
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    } catch {
      // Fallback
    }
  }

  const isCredit = transaction.direction === "CREDIT"
  const sourceAccountId = "sourceAccountId" in transaction ? transaction.sourceAccountId : undefined
  const destinationAccountId = "destinationAccountId" in transaction ? transaction.destinationAccountId : undefined
  const hasAccounts = Boolean(sourceAccountId && destinationAccountId)
  const balanceAfter = "balanceAfter" in transaction ? transaction.balanceAfter : undefined
  const initiatedByUserId = "initiatedByUserId" in transaction ? transaction.initiatedByUserId : undefined

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="transaction-detail-title"
      className="fixed inset-0 z-50 flex items-center justify-center bg-background/80 backdrop-blur-xs p-4 select-none"
    >
      <div
        className="fixed inset-0"
        onClick={handleClose}
      />

      <div className="relative w-full max-w-lg rounded-sm border border-border bg-card p-5 text-card-foreground shadow-lg space-y-4 z-10 font-sans">
        <div className="flex items-start justify-between pb-3 border-b border-border/70">
          <div className="flex items-center gap-2.5">
            <div className="size-8 rounded-sm bg-muted/60 border border-border flex items-center justify-center text-muted-foreground shrink-0">
              <FileText className="size-4" />
            </div>
            <div>
              <h2
                id="transaction-detail-title"
                className="text-[17px] font-semibold text-foreground tracking-tight"
              >
                Transaction Details
              </h2>
              <p className="text-[12.5px] font-mono text-muted-foreground mt-0.5 truncate max-w-xs">
                {transaction.transactionId}
              </p>
            </div>
          </div>
          <Button
            type="button"
            variant="ghost"
            size="icon-xs"
            onClick={handleClose}
            className="text-muted-foreground hover:text-foreground"
          >
            <X className="size-3.5" />
            <span className="sr-only">Close</span>
          </Button>
        </div>

        <div className="p-3.5 rounded-sm bg-muted/30 border border-border/60 flex items-center justify-between">
          <div className="space-y-0.5">
            <span className="text-[11px] font-medium text-muted-foreground uppercase tracking-wider">
              {isCredit ? "Credit Amount" : "Debit Amount"}
            </span>
            <div>
              <AmountDisplay
                amount={transaction.amount}
                currency={transaction.currency}
                direction={isCredit ? "credit" : "debit"}
                size="lg"
                showSign
              />
            </div>
          </div>
          <div className="flex flex-col items-end gap-1.5">
            <StatusBadge status={transaction.status} />
            <TransactionTypeBadge type={transaction.transactionType} />
          </div>
        </div>

        <div className="divide-y divide-border/50 text-[13px]">
          <div className="py-2 flex items-center justify-between">
            <span className="text-muted-foreground">Transaction ID</span>
            <div className="flex items-center gap-1.5">
              <span className="font-mono text-xs text-foreground select-all">
                {transaction.transactionId}
              </span>
              <button
                type="button"
                onClick={() => copyToClipboard(transaction.transactionId)}
                className="text-muted-foreground hover:text-foreground p-0.5"
                title="Copy ID"
              >
                {copied ? <Check className="size-3 text-emerald-500" /> : <Copy className="size-3" />}
              </button>
            </div>
          </div>

          <div className="py-2 flex items-center justify-between">
            <span className="text-muted-foreground">Direction</span>
            <span
              className={cn(
                "text-[11px] font-mono font-medium px-1.5 py-0.5 rounded-sm border",
                isCredit
                  ? "text-emerald-700 dark:text-emerald-400 border-emerald-500/20 bg-emerald-500/5"
                  : "text-foreground border-border bg-muted/40"
              )}
            >
              {transaction.direction} ({isCredit ? "CR" : "DR"})
            </span>
          </div>

          {hasAccounts && sourceAccountId && destinationAccountId && (
            <div className="py-2 flex items-center justify-between gap-2">
              <span className="text-muted-foreground">Account Flow</span>
              <div className="flex items-center gap-1.5 font-mono text-xs text-muted-foreground">
                <span className="truncate max-w-[120px]" title={sourceAccountId}>
                  {sourceAccountId.slice(0, 8)}...
                </span>
                <ArrowRight className="size-3 shrink-0 text-muted-foreground/60" />
                <span
                  className="truncate max-w-[120px] text-foreground font-medium"
                  title={destinationAccountId}
                >
                  {destinationAccountId.slice(0, 8)}...
                </span>
              </div>
            </div>
          )}

          {balanceAfter !== undefined && (
            <div className="py-2 flex items-center justify-between">
              <span className="text-muted-foreground">Balance After</span>
              <AmountDisplay
                amount={balanceAfter}
                currency={transaction.currency}
                size="sm"
              />
            </div>
          )}

          <div className="py-2 flex items-center justify-between">
            <span className="text-muted-foreground">Created At</span>
            <span className="font-mono text-xs text-foreground">
              {formatDate(transaction.createdAt)}
            </span>
          </div>

          {transaction.completedAt && (
            <div className="py-2 flex items-center justify-between">
              <span className="text-muted-foreground">Completed At</span>
              <span className="font-mono text-xs text-foreground">
                {formatDate(transaction.completedAt)}
              </span>
            </div>
          )}

          {initiatedByUserId && (
            <div className="py-2 flex items-center justify-between">
              <span className="text-muted-foreground">Initiated By User</span>
              <span className="font-mono text-xs text-muted-foreground truncate max-w-[160px]">
                {initiatedByUserId}
              </span>
            </div>
          )}

          <div className="py-2 flex flex-col gap-1">
            <span className="text-muted-foreground">Description</span>
            <p className="text-[13px] text-foreground bg-muted/20 p-2 rounded-xs border border-border/40">
              {transaction.description?.trim() || "No description provided"}
            </p>
          </div>
        </div>

        <div className="flex justify-end pt-2 border-t border-border/70">
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={handleClose}
          >
            Close
          </Button>
        </div>
      </div>
    </div>
  )
}
