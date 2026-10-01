"use client"

import * as React from "react"
import { CalendarClock, X, AlertCircle, Loader2 } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { useAccounts } from "@/hooks/api/use-accounts"
import { useCreateRecurringTransfer } from "@/hooks/api/use-recurring-transfers"
import { ApiError } from "@/types/api"
import type { RecurringFrequency } from "@/types/recurring"

interface CreateRecurringTransferDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  onSuccess?: (scheduleId: string) => void
}

export function CreateRecurringTransferDialog({
  open,
  onOpenChange,
  onSuccess,
}: CreateRecurringTransferDialogProps) {
  const { data: accounts, isLoading: accountsLoading } = useAccounts()
  const createMutation = useCreateRecurringTransfer()

  const [sourceAccountId, setSourceAccountId] = React.useState("")
  const [destinationAccountId, setDestinationAccountId] = React.useState("")
  const [amount, setAmount] = React.useState("")
  const [frequency, setFrequency] = React.useState<RecurringFrequency>("DAILY")
  const [startDate, setStartDate] = React.useState(
    () => new Date().toISOString().split("T")[0]
  )
  const [endDate, setEndDate] = React.useState("")
  const [errorMsg, setErrorMsg] = React.useState<string | null>(null)

  // Filter user's active checking accounts
  const activeCheckingAccounts = React.useMemo(() => {
    return (accounts || []).filter(
      (a) => a.accountType === "USER_CHECKING" && a.status === "ACTIVE"
    )
  }, [accounts])

  const effectiveSourceAccountId = sourceAccountId || (activeCheckingAccounts[0]?.accountId ?? "")

  const handleClose = React.useCallback(() => {
    if (!createMutation.isPending) {
      setErrorMsg(null)
      setSourceAccountId("")
      setAmount("")
      setEndDate("")
      onOpenChange(false)
    }
  }, [createMutation.isPending, onOpenChange])

  React.useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape" && open) {
        handleClose()
      }
    }
    window.addEventListener("keydown", handleKeyDown)
    return () => window.removeEventListener("keydown", handleKeyDown)
  }, [open, handleClose])

  if (!open) return null

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setErrorMsg(null)

    if (!effectiveSourceAccountId) {
      setErrorMsg("Please select a source account")
      return
    }

    const cleanDest = destinationAccountId.trim()
    if (!cleanDest) {
      setErrorMsg("Please specify a destination account ID")
      return
    }

    if (effectiveSourceAccountId === cleanDest) {
      setErrorMsg("Source and destination accounts must be different")
      return
    }

    const numAmount = parseFloat(amount)
    if (!amount || isNaN(numAmount) || numAmount <= 0) {
      setErrorMsg("Transfer amount must be greater than zero")
      return
    }

    if (!startDate) {
      setErrorMsg("Start date is required")
      return
    }

    if (endDate && endDate < startDate) {
      setErrorMsg("End date must be greater than or equal to start date")
      return
    }

    try {
      const created = await createMutation.mutateAsync({
        sourceAccountId: effectiveSourceAccountId,
        destinationAccountId: cleanDest,
        amount: numAmount,
        currency: "INR",
        frequency,
        startDate,
        endDate: endDate ? endDate : null,
      })

      handleClose()
      if (onSuccess) {
        onSuccess(created.id)
      }
    } catch (err) {
      if (err instanceof ApiError) {
        setErrorMsg(err.message || "Failed to create recurring transfer")
      } else if (err instanceof Error) {
        setErrorMsg(err.message)
      } else {
        setErrorMsg("An unexpected error occurred while scheduling recurring transfer")
      }
    }
  }

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="create-recurring-title"
      className="fixed inset-0 z-50 flex items-center justify-center bg-background/80 backdrop-blur-xs p-4 select-none"
    >
      <div className="fixed inset-0" onClick={handleClose} />

      <div className="relative w-full max-w-lg rounded-sm border border-border bg-card p-5 text-card-foreground shadow-lg space-y-4 z-10 font-sans max-h-[90vh] overflow-y-auto">
        <div className="flex items-start justify-between pb-3 border-b border-border/70">
          <div className="flex items-center gap-2.5">
            <div className="size-8 rounded-sm bg-muted/60 border border-border flex items-center justify-center text-muted-foreground shrink-0">
              <CalendarClock className="size-4" />
            </div>
            <div>
              <h2
                id="create-recurring-title"
                className="text-[17px] font-semibold text-foreground tracking-tight"
              >
                Schedule Recurring Transfer
              </h2>
              <p className="text-[13.5px] text-muted-foreground mt-0.5">
                Set up automated recurring transfers between accounts.
              </p>
            </div>
          </div>
          <Button
            type="button"
            variant="ghost"
            size="icon-xs"
            onClick={handleClose}
            disabled={createMutation.isPending}
            className="text-muted-foreground hover:text-foreground"
          >
            <X className="size-4" />
            <span className="sr-only">Close</span>
          </Button>
        </div>

        {errorMsg && (
          <div className="p-3 bg-red-500/10 border border-red-500/20 rounded-sm flex items-start gap-2.5 text-red-700 dark:text-red-400 text-[13px]">
            <AlertCircle className="size-4 shrink-0 mt-0.5" />
            <span className="leading-snug">{errorMsg}</span>
          </div>
        )}

        <form onSubmit={handleSubmit} className="space-y-4">
          {/* Source Account */}
          <div className="space-y-1.5">
            <label className="text-[13px] font-medium text-foreground">
              Source Account
            </label>
            {accountsLoading ? (
              <div className="h-9 w-full bg-muted/40 rounded-sm animate-pulse" />
            ) : (
              <select
                value={effectiveSourceAccountId}
                onChange={(e) => setSourceAccountId(e.target.value)}
                disabled={createMutation.isPending}
                className="w-full h-9 rounded-sm border border-border bg-background px-3 py-1 text-[13px] text-foreground focus:outline-hidden focus:ring-1 focus:ring-ring"
              >
                {activeCheckingAccounts.map((acc) => (
                  <option key={acc.accountId} value={acc.accountId}>
                    {acc.accountNumber || acc.accountId} (Balance: ₹{acc.balance} {acc.currency})
                  </option>
                ))}
              </select>
            )}
            <p className="text-[11.5px] text-muted-foreground">
              Must be an active checking account you own.
            </p>
          </div>

          {/* Destination Account */}
          <div className="space-y-1.5">
            <label className="text-[13px] font-medium text-foreground">
              Destination Account ID
            </label>
            <Input
              type="text"
              placeholder="e.g. 123e4567-e89b-12d3-a456-426614174000"
              value={destinationAccountId}
              onChange={(e) => setDestinationAccountId(e.target.value)}
              disabled={createMutation.isPending}
              required
            />
            {accounts && accounts.length > 1 && (
              <div className="flex flex-wrap gap-1.5 mt-1">
                <span className="text-[11.5px] text-muted-foreground">Quick pick:</span>
                {accounts
                  .filter((a) => a.accountId !== effectiveSourceAccountId)
                  .map((a) => (
                    <button
                      key={a.accountId}
                      type="button"
                      onClick={() => setDestinationAccountId(a.accountId)}
                      className="text-[11.5px] text-primary hover:underline px-1 py-0.5 bg-muted/30 rounded-xs"
                    >
                      {a.accountNumber || a.accountId.slice(0, 8)}
                    </button>
                  ))}
              </div>
            )}
          </div>

          {/* Amount & Currency */}
          <div className="grid grid-cols-3 gap-3">
            <div className="col-span-2 space-y-1.5">
              <label className="text-[13px] font-medium text-foreground">
                Transfer Amount
              </label>
              <Input
                type="number"
                step="0.01"
                min="0.01"
                placeholder="0.00"
                value={amount}
                onChange={(e) => setAmount(e.target.value)}
                disabled={createMutation.isPending}
                required
              />
            </div>
            <div className="space-y-1.5">
              <label className="text-[13px] font-medium text-foreground">
                Currency
              </label>
              <Input
                type="text"
                value="INR"
                disabled
                className="bg-muted/50 cursor-not-allowed font-medium text-muted-foreground"
              />
            </div>
          </div>

          {/* Frequency */}
          <div className="space-y-1.5">
            <label className="text-[13px] font-medium text-foreground">
              Frequency
            </label>
            <div className="grid grid-cols-3 gap-2">
              {(["DAILY", "WEEKLY", "MONTHLY"] as RecurringFrequency[]).map((freq) => (
                <button
                  key={freq}
                  type="button"
                  onClick={() => setFrequency(freq)}
                  disabled={createMutation.isPending}
                  className={`h-9 rounded-sm border text-[13px] font-medium transition-colors ${
                    frequency === freq
                      ? "border-primary bg-primary/10 text-primary font-semibold"
                      : "border-border bg-background text-muted-foreground hover:text-foreground"
                  }`}
                >
                  {freq === "DAILY" ? "Daily" : freq === "WEEKLY" ? "Weekly" : "Monthly"}
                </button>
              ))}
            </div>
            <p className="text-[11.5px] text-muted-foreground">
              {frequency === "DAILY" && "Executes once every calendar day (+1 day)."}
              {frequency === "WEEKLY" && "Executes once every 7 calendar days (+1 week)."}
              {frequency === "MONTHLY" && "Executes once every month preserving original anchor day without drift."}
            </p>
          </div>

          {/* Schedule Dates */}
          <div className="grid grid-cols-2 gap-3">
            <div className="space-y-1.5">
              <label className="text-[13px] font-medium text-foreground">
                Start Date
              </label>
              <Input
                type="date"
                value={startDate}
                onChange={(e) => setStartDate(e.target.value)}
                disabled={createMutation.isPending}
                required
              />
            </div>
            <div className="space-y-1.5">
              <label className="text-[13px] font-medium text-foreground">
                End Date (Optional)
              </label>
              <Input
                type="date"
                value={endDate}
                min={startDate}
                onChange={(e) => setEndDate(e.target.value)}
                disabled={createMutation.isPending}
              />
            </div>
          </div>

          <div className="flex items-center justify-end gap-2.5 pt-3 border-t border-border/70">
            <Button
              type="button"
              variant="outline"
              onClick={handleClose}
              disabled={createMutation.isPending}
            >
              Cancel
            </Button>
            <Button
              type="submit"
              disabled={createMutation.isPending}
              className="gap-1.5"
            >
              {createMutation.isPending && (
                <Loader2 className="size-3.5 animate-spin" />
              )}
              {createMutation.isPending ? "Scheduling..." : "Create Schedule"}
            </Button>
          </div>
        </form>
      </div>
    </div>
  )
}
