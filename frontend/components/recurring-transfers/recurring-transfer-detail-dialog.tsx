"use client"

import * as React from "react"
import {
  CalendarClock,
  X,
  Play,
  Pause,
  Trash2,
  History,
  AlertCircle,
  Loader2,
} from "lucide-react"
import { Button } from "@/components/ui/button"
import { StatusBadge } from "@/components/ui/status-badge"
import { useAccounts } from "@/hooks/api/use-accounts"
import {
  usePauseRecurringTransfer,
  useResumeRecurringTransfer,
  useCancelRecurringTransfer,
} from "@/hooks/api/use-recurring-transfers"
import { formatAccountFlowLabel, maskAccountNumber } from "@/lib/formatters/ledger"
import { ApiError } from "@/types/api"
import type { RecurringTransfer } from "@/types/recurring"

interface RecurringTransferDetailDialogProps {
  schedule: RecurringTransfer | null
  open: boolean
  onOpenChange: (open: boolean) => void
  onViewExecutions?: (schedule: RecurringTransfer) => void
}

export function RecurringTransferDetailDialog({
  schedule,
  open,
  onOpenChange,
  onViewExecutions,
}: RecurringTransferDetailDialogProps) {
  const pauseMutation = usePauseRecurringTransfer()
  const resumeMutation = useResumeRecurringTransfer()
  const cancelMutation = useCancelRecurringTransfer()
  const { data: accounts } = useAccounts()

  const [confirmCancel, setConfirmCancel] = React.useState(false)
  const [errorMsg, setErrorMsg] = React.useState<string | null>(null)

  const isPending =
    pauseMutation.isPending ||
    resumeMutation.isPending ||
    cancelMutation.isPending

  const handleClose = React.useCallback(() => {
    if (!isPending) {
      setConfirmCancel(false)
      setErrorMsg(null)
      onOpenChange(false)
    }
  }, [isPending, onOpenChange])

  React.useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape" && open) {
        handleClose()
      }
    }
    window.addEventListener("keydown", handleKeyDown)
    return () => window.removeEventListener("keydown", handleKeyDown)
  }, [open, handleClose])

  if (!open || !schedule) return null

  const handlePause = async () => {
    setErrorMsg(null)
    try {
      await pauseMutation.mutateAsync(schedule.id)
    } catch (err) {
      if (err instanceof ApiError) {
        setErrorMsg(err.message || "Failed to pause schedule")
      } else if (err instanceof Error) {
        setErrorMsg(err.message)
      }
    }
  }

  const handleResume = async () => {
    setErrorMsg(null)
    try {
      await resumeMutation.mutateAsync(schedule.id)
    } catch (err) {
      if (err instanceof ApiError) {
        setErrorMsg(err.message || "Failed to resume schedule")
      } else if (err instanceof Error) {
        setErrorMsg(err.message)
      }
    }
  }

  const handleCancel = async () => {
    setErrorMsg(null)
    try {
      await cancelMutation.mutateAsync(schedule.id)
      setConfirmCancel(false)
    } catch (err) {
      if (err instanceof ApiError) {
        setErrorMsg(err.message || "Failed to cancel schedule")
      } else if (err instanceof Error) {
        setErrorMsg(err.message)
      }
    }
  }

  const isTerminal =
    schedule.status === "CANCELLED" || schedule.status === "COMPLETED"

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="detail-title"
      className="fixed inset-0 z-50 flex items-center justify-center bg-background/80 backdrop-blur-xs p-4 select-none"
    >
      <div className="fixed inset-0" onClick={handleClose} />

      <div className="relative w-full max-w-lg rounded-sm border border-border bg-card p-5 text-card-foreground shadow-lg space-y-4 z-10 font-sans max-h-[90vh] overflow-y-auto">
        {/* Header */}
        <div className="flex items-start justify-between pb-3 border-b border-border/70">
          <div className="flex items-center gap-2.5">
            <div className="size-8 rounded-sm bg-muted/60 border border-border flex items-center justify-center text-muted-foreground shrink-0">
              <CalendarClock className="size-4" />
            </div>
            <div>
              <div className="flex items-center gap-2">
                <h2
                  id="detail-title"
                  className="text-[17px] font-semibold text-foreground tracking-tight"
                >
                  Schedule Details
                </h2>
                <StatusBadge status={schedule.status} />
              </div>
              <p className="text-[12.5px] text-muted-foreground font-mono mt-0.5">
                Schedule Ref: {maskAccountNumber(schedule.id)}
              </p>
            </div>
          </div>
          <Button
            type="button"
            variant="ghost"
            size="icon-xs"
            onClick={handleClose}
            disabled={isPending}
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

        {/* Schedule Metadata Grid */}
        <div className="space-y-3 text-[13px]">
          <div className="grid grid-cols-2 gap-3 p-3 bg-muted/20 border border-border/70 rounded-sm">
            <div>
              <span className="text-[11.5px] text-muted-foreground uppercase tracking-wider block">
                Amount
              </span>
              <span className="text-[16px] font-semibold text-foreground font-mono">
                ₹{Number(schedule.amount).toFixed(2)} {schedule.currency}
              </span>
            </div>
            <div>
              <span className="text-[11.5px] text-muted-foreground uppercase tracking-wider block">
                Frequency
              </span>
              <span className="text-[15px] font-medium text-foreground">
                {schedule.frequency === "DAILY"
                  ? "Daily"
                  : schedule.frequency === "WEEKLY"
                  ? "Weekly"
                  : "Monthly"}
              </span>
            </div>
          </div>

          <div className="space-y-2 border border-border/70 rounded-sm p-3">
            <div className="flex justify-between py-1 border-b border-border/40">
              <span className="text-muted-foreground">Source Account</span>
              <span className="font-medium text-foreground">
                {formatAccountFlowLabel(schedule.sourceAccountId, undefined, accounts)}
              </span>
            </div>
            <div className="flex justify-between py-1 border-b border-border/40">
              <span className="text-muted-foreground">Destination Account</span>
              <span className="font-medium text-foreground">
                {formatAccountFlowLabel(schedule.destinationAccountId, undefined, accounts)}
              </span>
            </div>
            <div className="flex justify-between py-1 border-b border-border/40">
              <span className="text-muted-foreground">Start Date</span>
              <span className="text-foreground">{schedule.startDate}</span>
            </div>
            <div className="flex justify-between py-1 border-b border-border/40">
              <span className="text-muted-foreground">End Date</span>
              <span className="text-foreground">{schedule.endDate || "No expiry"}</span>
            </div>
            <div className="flex justify-between py-1 border-b border-border/40">
              <span className="text-muted-foreground">Next Execution</span>
              <span className="font-mono text-foreground">
                {schedule.nextExecutionAt
                  ? new Date(schedule.nextExecutionAt).toLocaleString()
                  : "None"}
              </span>
            </div>
            <div className="flex justify-between py-1 border-b border-border/40">
              <span className="text-muted-foreground">Last Executed</span>
              <span className="font-mono text-foreground">
                {schedule.lastExecutedAt
                  ? new Date(schedule.lastExecutedAt).toLocaleString()
                  : "Never"}
              </span>
            </div>
            <div className="flex justify-between py-1">
              <span className="text-muted-foreground">Executions (Success / Fail)</span>
              <span className="font-medium text-foreground">
                <span className="text-emerald-600 dark:text-emerald-400">
                  {schedule.executionCount} success
                </span>
                {" / "}
                <span className="text-red-600 dark:text-red-400">
                  {schedule.failureCount} failed
                </span>
              </span>
            </div>
          </div>
        </div>

        {/* Action Controls */}
        <div className="pt-3 border-t border-border/70 space-y-3">
          {confirmCancel ? (
            <div className="p-3 bg-amber-500/10 border border-amber-500/20 rounded-sm space-y-2">
              <p className="text-[12.5px] text-amber-700 dark:text-amber-400">
                Are you sure you want to cancel this recurring schedule? This action is terminal and cannot be undone.
              </p>
              <div className="flex gap-2">
                <Button
                  type="button"
                  variant="destructive"
                  size="sm"
                  onClick={handleCancel}
                  disabled={isPending}
                  className="gap-1.5 text-[12px] h-8"
                >
                  {cancelMutation.isPending && (
                    <Loader2 className="size-3 animate-spin" />
                  )}
                  Confirm Cancellation
                </Button>
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={() => setConfirmCancel(false)}
                  disabled={isPending}
                  className="text-[12px] h-8"
                >
                  Never mind
                </Button>
              </div>
            </div>
          ) : (
            <div className="flex flex-wrap items-center justify-between gap-2">
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => {
                  handleClose()
                  if (onViewExecutions) {
                    onViewExecutions(schedule)
                  }
                }}
                className="gap-1.5 text-[12.5px] h-8"
              >
                <History className="size-3.5" />
                View Execution Logs
              </Button>

              <div className="flex items-center gap-2">
                {schedule.status === "ACTIVE" && (
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    onClick={handlePause}
                    disabled={isPending}
                    className="gap-1.5 text-[12.5px] h-8 text-amber-600 dark:text-amber-400 hover:text-amber-700"
                  >
                    {pauseMutation.isPending ? (
                      <Loader2 className="size-3.5 animate-spin" />
                    ) : (
                      <Pause className="size-3.5" />
                    )}
                    Pause
                  </Button>
                )}

                {schedule.status === "PAUSED" && (
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    onClick={handleResume}
                    disabled={isPending}
                    className="gap-1.5 text-[12.5px] h-8 text-emerald-600 dark:text-emerald-400 hover:text-emerald-700"
                  >
                    {resumeMutation.isPending ? (
                      <Loader2 className="size-3.5 animate-spin" />
                    ) : (
                      <Play className="size-3.5" />
                    )}
                    Resume
                  </Button>
                )}

                {!isTerminal && (
                  <Button
                    type="button"
                    variant="ghost"
                    size="sm"
                    onClick={() => setConfirmCancel(true)}
                    disabled={isPending}
                    className="gap-1.5 text-[12.5px] h-8 text-red-600 dark:text-red-400 hover:text-red-700 hover:bg-red-500/10"
                  >
                    <Trash2 className="size-3.5" />
                    Cancel
                  </Button>
                )}
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
