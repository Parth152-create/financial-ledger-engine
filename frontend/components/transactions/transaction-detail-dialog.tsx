"use client"

import * as React from "react"
import { X, Copy, Check, FileText, ShieldCheck, ArrowDown, ArrowUp, RotateCcw, AlertTriangle } from "lucide-react"
import { Button } from "@/components/ui/button"
import { StatusBadge } from "@/components/ui/status-badge"
import { AmountDisplay } from "@/components/ui/amount-display"
import { TransactionTypeBadge } from "@/components/ledger/transaction-type-badge"
import { formatDate } from "@/lib/formatters/date"
import { formatAccountFlowLabel } from "@/lib/formatters/ledger"
import { useAccounts } from "@/hooks/api/use-accounts"
import { useReverseTransaction } from "@/hooks/api/use-transactions"
import type { TransactionHistoryItem, ReversalResponse } from "@/types/transaction"
import type { StatementEntry } from "@/types/statement"
import type { Account } from "@/types/account"
import type { ApiError } from "@/types/api"
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
      transactionType: "TRANSFER" | "DEPOSIT" | "WITHDRAWAL" | "REVERSAL" | "SYSTEM_FUNDING" | string
      sourceAccountId?: string
      destinationAccountId?: string
      balanceAfter?: number
      initiatedByUserId?: string | null
      description?: string | null
      completedAt?: string | null
      reversed?: boolean
      reversalTransactionId?: string | null
      reversesTransactionId?: string | null
    })

interface TransactionDetailDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  transaction: TransactionDetailData | null
  currentAccountId?: string
  accounts?: Account[]
}

export function generateReversalIdempotencyKey(): string {
  return typeof crypto !== "undefined" && crypto.randomUUID
    ? crypto.randomUUID()
    : `REV-${Date.now()}-${Math.random().toString(36).substring(2, 9)}`
}

export function mapReversalError(error: ApiError | Error | unknown): string {
  if (!error || typeof error !== "object") return "An unexpected error occurred."
  const apiErr = error as ApiError & {
    code?: string
    response?: { status?: number; data?: { message?: string; error?: string } }
  }
  const status = apiErr.status || apiErr.response?.status
  const rawMessage = (apiErr.message || apiErr.response?.data?.message || apiErr.error || "").toLowerCase()

  if (status === 409) {
    if (rawMessage.includes("idempotency")) {
      return "An idempotency conflict occurred. Please retry with a new request."
    }
    if (
      (rawMessage.includes("already") && rawMessage.includes("reversed")) ||
      rawMessage.includes("transaction_already_reversed")
    ) {
      return "Transaction has already been reversed."
    }
    return "A conflicting transaction operation was detected. Please verify your transaction status."
  }
  if (status === 422) return "Transaction cannot be reversed."
  if (status === 403) return "You are not authorized to reverse this transaction."
  if (status === 404) return "Transaction not found."
  if (status === 429) return "Too many requests. Please try again later."
  if (apiErr.message?.includes("Network") || apiErr.code === "ERR_NETWORK" || !status) {
    return "Unable to reach the server."
  }
  return apiErr.message || "An unexpected error occurred."
}

interface TransactionDetailContentProps {
  onClose: () => void
  transaction: TransactionDetailData
  currentAccountId?: string
  propAccounts?: Account[]
}

function TransactionDetailContent({
  onClose,
  transaction,
  currentAccountId,
  propAccounts,
}: TransactionDetailContentProps) {
  const [copied, setCopied] = React.useState(false)
  const [showConfirm, setShowConfirm] = React.useState(false)
  const [reason, setReason] = React.useState("")
  const [errorMessage, setErrorMessage] = React.useState<string | null>(null)
  const [reversalResult, setReversalResult] = React.useState<ReversalResponse | null>(null)
  const idempotencyKeyRef = React.useRef<string | null>(null)

  const reverseMutation = useReverseTransaction()

  // Use accounts hook as fallback if propAccounts not provided
  const { data: queryAccounts } = useAccounts()
  const accounts = propAccounts || queryAccounts || []

  React.useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        onClose()
      }
    }
    window.addEventListener("keydown", handleKeyDown)
    return () => window.removeEventListener("keydown", handleKeyDown)
  }, [onClose])

  const copyToClipboard = async (text: string) => {
    try {
      await navigator.clipboard.writeText(text)
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    } catch {
      // Clipboard fallback
    }
  }

  const isCredit = transaction.direction === "CREDIT"
  const sourceAccountId = "sourceAccountId" in transaction ? transaction.sourceAccountId : undefined
  const destinationAccountId = "destinationAccountId" in transaction ? transaction.destinationAccountId : undefined
  const balanceAfter = "balanceAfter" in transaction ? transaction.balanceAfter : undefined

  const sourceLabel = formatAccountFlowLabel(sourceAccountId, currentAccountId, accounts)
  const destinationLabel = formatAccountFlowLabel(destinationAccountId, currentAccountId, accounts)

  const isTransfer = transaction.transactionType === "TRANSFER"
  const isDeposit = transaction.transactionType === "DEPOSIT"
  const isWithdrawal = transaction.transactionType === "WITHDRAWAL"
  const isReversal = transaction.transactionType === "REVERSAL"
  const isSystemFunding = transaction.transactionType === "SYSTEM_FUNDING"

  const isAlreadyReversed = Boolean(
    ("reversed" in transaction && transaction.reversed) ||
      ("reversalTransactionId" in transaction && transaction.reversalTransactionId) ||
      reversalResult
  )
  const activeReversalTxId =
    reversalResult?.reversalTransactionId ||
    ("reversalTransactionId" in transaction ? transaction.reversalTransactionId : undefined)

  const reversesOriginalTxId =
    "reversesTransactionId" in transaction ? transaction.reversesTransactionId : undefined

  const isEligibleForReversal =
    transaction.status === "COMPLETED" &&
    !isReversal &&
    !isSystemFunding &&
    !isAlreadyReversed

  const formattedAmount = `${transaction.currency === "INR" ? "₹" : ""}${transaction.amount.toLocaleString("en-IN", {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })}`

  const handleExecuteReversal = async () => {
    setErrorMessage(null)
    if (!idempotencyKeyRef.current) {
      idempotencyKeyRef.current = generateReversalIdempotencyKey()
    }
    const currentKey = idempotencyKeyRef.current

    try {
      const res = await reverseMutation.mutateAsync({
        transactionId: transaction.transactionId,
        request: reason.trim() ? { reason: reason.trim() } : undefined,
        idempotencyKey: currentKey,
      })
      setReversalResult(res)
      setShowConfirm(false)
      idempotencyKeyRef.current = null
    } catch (err) {
      setErrorMessage(mapReversalError(err))
    }
  }

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="transaction-detail-title"
      className="fixed inset-0 z-50 flex items-center justify-center bg-background/80 backdrop-blur-xs p-4 select-none"
    >
      <div
        className="fixed inset-0"
        onClick={onClose}
      />

      <div className="relative w-full max-w-lg rounded-sm border border-border bg-card p-5 text-card-foreground shadow-lg space-y-4 z-10 font-sans max-h-[90vh] overflow-y-auto">
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
            onClick={onClose}
            className="text-muted-foreground hover:text-foreground"
          >
            <X className="size-3.5" />
            <span className="sr-only">Close</span>
          </Button>
        </div>

        {/* Reversal Confirmation Modal Layer */}
        {showConfirm ? (
          <div className="rounded-sm border border-amber-500/30 bg-amber-500/5 p-4 space-y-3.5">
            <div className="flex items-start gap-2.5">
              <AlertTriangle className="size-5 text-amber-600 dark:text-amber-400 shrink-0 mt-0.5" />
              <div className="space-y-1">
                <h3 className="text-sm font-semibold text-foreground">
                  Reverse transaction?
                </h3>
                <p className="text-xs text-muted-foreground leading-relaxed">
                  This creates a new compensating transaction. The original transaction will remain in your transaction history.
                </p>
              </div>
            </div>

            <div className="p-2.5 rounded-xs bg-muted/40 border border-border/60 text-xs space-y-1.5">
              <div className="flex justify-between">
                <span className="text-muted-foreground">Compensating Amount:</span>
                <span className="font-semibold font-mono text-foreground">{formattedAmount}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-muted-foreground">Original Transaction:</span>
                <span className="font-mono text-xs text-muted-foreground">{transaction.transactionId.substring(0, 8)}...</span>
              </div>
            </div>

            <div className="space-y-1.5">
              <label htmlFor="reversal-reason-input" className="text-xs font-medium text-foreground block">
                Reason (optional)
              </label>
              <input
                id="reversal-reason-input"
                type="text"
                maxLength={255}
                value={reason}
                onChange={(e) => setReason(e.target.value)}
                placeholder="e.g. Duplicate transfer or incorrect account"
                className="w-full text-xs px-2.5 py-1.5 rounded-xs border border-border bg-background focus:outline-hidden focus:ring-1 focus:ring-ring text-foreground"
                disabled={reverseMutation.isPending}
              />
            </div>

            {errorMessage && (
              <div className="text-xs text-destructive p-2.5 rounded-xs bg-destructive/10 border border-destructive/20 font-medium">
                {errorMessage}
              </div>
            )}

            <div className="flex justify-end gap-2 pt-1">
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => {
                  setShowConfirm(false)
                  setErrorMessage(null)
                  idempotencyKeyRef.current = null
                }}
                disabled={reverseMutation.isPending}
              >
                Cancel
              </Button>
              <Button
                type="button"
                variant="destructive"
                size="sm"
                onClick={handleExecuteReversal}
                disabled={reverseMutation.isPending}
              >
                {reverseMutation.isPending ? "Reversing..." : "Confirm Reversal"}
              </Button>
            </div>
          </div>
        ) : null}

        {/* Reversal Success Banner */}
        {reversalResult && (
          <div className="p-3.5 rounded-sm bg-emerald-500/10 border border-emerald-500/25 space-y-1.5 text-xs text-emerald-900 dark:text-emerald-300">
            <div className="flex items-center gap-1.5 font-semibold text-emerald-800 dark:text-emerald-300">
              <Check className="size-4 text-emerald-600 dark:text-emerald-400" />
              <span>Reversal completed successfully</span>
            </div>
            <p className="text-[12px] opacity-90">
              A new compensating transaction has been recorded.
            </p>
            <div className="font-mono text-[11px] space-y-0.5 pt-1 border-t border-emerald-500/20">
              <div>Reversal Transaction: {reversalResult.reversalTransactionId}</div>
              <div>Original Transaction: {reversalResult.originalTransactionId}</div>
            </div>
          </div>
        )}

        {/* Existing Reversal Status Banners */}
        {isAlreadyReversed && !reversalResult && (
          <div className="p-3 rounded-sm bg-amber-500/10 border border-amber-500/25 text-amber-900 dark:text-amber-300 space-y-1 text-xs">
            <div className="flex items-center gap-1.5 font-semibold">
              <RotateCcw className="size-3.5 text-amber-600 dark:text-amber-400 shrink-0" />
              <span>This transaction has been reversed</span>
            </div>
            <p className="text-[11.5px] opacity-90">
              A compensating transaction was recorded. The historical ledger remains intact.
            </p>
            {activeReversalTxId && (
              <p className="font-mono text-[11px] truncate pt-0.5">
                Reversal Transaction: {activeReversalTxId}
              </p>
            )}
          </div>
        )}

        {isReversal && (
          <div className="p-3 rounded-sm bg-blue-500/10 border border-blue-500/25 text-blue-900 dark:text-blue-300 space-y-1 text-xs">
            <div className="flex items-center gap-1.5 font-semibold">
              <RotateCcw className="size-3.5 text-blue-600 dark:text-blue-400 shrink-0" />
              <span>Compensating Reversal Transaction</span>
            </div>
            <p className="text-[11.5px] opacity-90">
              This transaction compensates for a previously executed transaction.
            </p>
            {reversesOriginalTxId && (
              <p className="font-mono text-[11px] truncate pt-0.5">
                Reverses Original: {reversesOriginalTxId}
              </p>
            )}
          </div>
        )}

        {/* Top Summary Banner */}
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
            <div className="flex items-center gap-1">
              {isAlreadyReversed && (
                <span className="inline-flex items-center gap-1 text-[11px] font-medium px-2 py-0.5 rounded-xs border border-amber-500/30 bg-amber-500/10 text-amber-700 dark:text-amber-400">
                  <RotateCcw className="size-3" />
                  Reversed
                </span>
              )}
              <StatusBadge status={transaction.status} />
            </div>
            <TransactionTypeBadge type={transaction.transactionType} />
          </div>
        </div>

        {/* Double-Entry Financial Movement Presentation */}
        <div className="rounded-sm border border-border/70 bg-muted/15 p-3.5 space-y-2.5 font-sans">
          <div className="flex items-center justify-between text-xs">
            <span className="font-semibold text-foreground tracking-tight">Double-Entry Movement</span>
            <span className="text-[11px] font-mono text-muted-foreground">Authoritative Ledger</span>
          </div>

          <div className="space-y-1.5 text-xs">
            {isTransfer && (
              <>
                <div className="flex items-center justify-between p-2 rounded-xs border border-border/50 bg-card">
                  <div>
                    <span className="text-[10.5px] font-medium text-muted-foreground uppercase tracking-wider block">
                      Source Account
                    </span>
                    <span className="font-semibold text-foreground text-xs">{sourceLabel}</span>
                  </div>
                  <div className="text-right flex items-center gap-1 font-mono text-xs font-semibold text-foreground">
                    <ArrowDown className="size-3 text-muted-foreground" />
                    <span>Debit {formattedAmount}</span>
                  </div>
                </div>

                <div className="flex items-center justify-between p-2 rounded-xs border border-emerald-500/25 bg-emerald-500/5">
                  <div>
                    <span className="text-[10.5px] font-medium text-emerald-700 dark:text-emerald-400 uppercase tracking-wider block">
                      Destination Account
                    </span>
                    <span className="font-semibold text-foreground text-xs">{destinationLabel}</span>
                  </div>
                  <div className="text-right flex items-center gap-1 font-mono text-xs font-semibold text-emerald-600 dark:text-emerald-400">
                    <ArrowUp className="size-3" />
                    <span>Credit {formattedAmount}</span>
                  </div>
                </div>
              </>
            )}

            {isDeposit && (
              <>
                <div className="flex items-center justify-between p-2 rounded-xs border border-border/50 bg-card">
                  <div>
                    <span className="text-[10.5px] font-medium text-muted-foreground uppercase tracking-wider block">
                      Settlement Source
                    </span>
                    <span className="font-semibold text-foreground text-xs">Platform Clearing</span>
                  </div>
                  <div className="text-right flex items-center gap-1 font-mono text-xs font-semibold text-muted-foreground">
                    <ArrowDown className="size-3" />
                    <span>Clearing Settlement</span>
                  </div>
                </div>

                <div className="flex items-center justify-between p-2 rounded-xs border border-emerald-500/25 bg-emerald-500/5">
                  <div>
                    <span className="text-[10.5px] font-medium text-emerald-700 dark:text-emerald-400 uppercase tracking-wider block">
                      Your Account
                    </span>
                    <span className="font-semibold text-foreground text-xs">{destinationLabel}</span>
                  </div>
                  <div className="text-right flex items-center gap-1 font-mono text-xs font-semibold text-emerald-600 dark:text-emerald-400">
                    <ArrowUp className="size-3" />
                    <span>Credit {formattedAmount}</span>
                  </div>
                </div>
              </>
            )}

            {isWithdrawal && (
              <>
                <div className="flex items-center justify-between p-2 rounded-xs border border-border/50 bg-card">
                  <div>
                    <span className="text-[10.5px] font-medium text-muted-foreground uppercase tracking-wider block">
                      Your Account
                    </span>
                    <span className="font-semibold text-foreground text-xs">{sourceLabel}</span>
                  </div>
                  <div className="text-right flex items-center gap-1 font-mono text-xs font-semibold text-foreground">
                    <ArrowDown className="size-3 text-muted-foreground" />
                    <span>Debit {formattedAmount}</span>
                  </div>
                </div>

                <div className="flex items-center justify-between p-2 rounded-xs border border-border/50 bg-card">
                  <div>
                    <span className="text-[10.5px] font-medium text-muted-foreground uppercase tracking-wider block">
                      Settlement Target
                    </span>
                    <span className="font-semibold text-foreground text-xs">Platform Clearing</span>
                  </div>
                  <div className="text-right flex items-center gap-1 font-mono text-xs font-semibold text-muted-foreground">
                    <ArrowDown className="size-3" />
                    <span>External Settlement</span>
                  </div>
                </div>
              </>
            )}

            {isReversal && (
              <>
                <div className="flex items-center justify-between p-2 rounded-xs border border-border/50 bg-card">
                  <div>
                    <span className="text-[10.5px] font-medium text-muted-foreground uppercase tracking-wider block">
                      Compensating Debit
                    </span>
                    <span className="font-semibold text-foreground text-xs">{sourceLabel}</span>
                  </div>
                  <div className="text-right flex items-center gap-1 font-mono text-xs font-semibold text-foreground">
                    <ArrowDown className="size-3 text-muted-foreground" />
                    <span>Debit {formattedAmount}</span>
                  </div>
                </div>

                <div className="flex items-center justify-between p-2 rounded-xs border border-emerald-500/25 bg-emerald-500/5">
                  <div>
                    <span className="text-[10.5px] font-medium text-emerald-700 dark:text-emerald-400 uppercase tracking-wider block">
                      Compensating Credit
                    </span>
                    <span className="font-semibold text-foreground text-xs">{destinationLabel}</span>
                  </div>
                  <div className="text-right flex items-center gap-1 font-mono text-xs font-semibold text-emerald-600 dark:text-emerald-400">
                    <ArrowUp className="size-3" />
                    <span>Credit {formattedAmount}</span>
                  </div>
                </div>
              </>
            )}
          </div>

          <div className="flex items-center gap-1.5 text-[11px] text-muted-foreground/80 pt-1.5 border-t border-border/40">
            <ShieldCheck className="size-3.5 text-primary shrink-0" />
            <span>Ledger entries are immutable. Corrections are recorded as compensating transactions.</span>
          </div>
        </div>

        {/* Detailed Fields */}
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
                aria-label="Copy transaction ID"
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

          <div className="py-2 flex flex-col gap-1">
            <span className="text-muted-foreground">Description</span>
            <p className="text-[13px] text-foreground bg-muted/20 p-2 rounded-xs border border-border/40">
              {transaction.description?.trim() || "No description provided"}
            </p>
          </div>
        </div>

        <div className="flex items-center justify-between pt-2 border-t border-border/70">
          <div>
            {isEligibleForReversal && !showConfirm && (
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => {
                  idempotencyKeyRef.current = null
                  setErrorMessage(null)
                  setShowConfirm(true)
                }}
                className="text-amber-600 dark:text-amber-400 border-amber-500/30 hover:bg-amber-500/10 text-xs"
              >
                <RotateCcw className="size-3.5 mr-1.5" />
                Reverse Transaction
              </Button>
            )}
          </div>
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={onClose}
          >
            Close
          </Button>
        </div>
      </div>
    </div>
  )
}

export function TransactionDetailDialog({
  open,
  onOpenChange,
  transaction,
  currentAccountId,
  accounts,
}: TransactionDetailDialogProps) {
  if (!open || !transaction) return null

  return (
    <TransactionDetailContent
      key={transaction.transactionId}
      onClose={() => onOpenChange(false)}
      transaction={transaction}
      currentAccountId={currentAccountId}
      propAccounts={accounts}
    />
  )
}
