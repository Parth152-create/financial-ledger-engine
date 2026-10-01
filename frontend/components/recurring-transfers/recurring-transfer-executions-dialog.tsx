"use client"

import * as React from "react"
import { History, X, AlertCircle, ChevronLeft, ChevronRight, CheckCircle2, XCircle } from "lucide-react"
import { Button } from "@/components/ui/button"
import { StatusBadge } from "@/components/ui/status-badge"
import { useRecurringTransferExecutions } from "@/hooks/api/use-recurring-transfers"
import { maskAccountNumber } from "@/lib/formatters/ledger"
import type { RecurringTransfer } from "@/types/recurring"

interface RecurringTransferExecutionsDialogProps {
  schedule: RecurringTransfer | null
  open: boolean
  onOpenChange: (open: boolean) => void
}

export function RecurringTransferExecutionsDialog({
  schedule,
  open,
  onOpenChange,
}: RecurringTransferExecutionsDialogProps) {
  const [page, setPage] = React.useState(0)
  const pageSize = 10

  const { data, isLoading, error } = useRecurringTransferExecutions(
    schedule?.id ?? null,
    { page, size: pageSize }
  )

  const handleClose = React.useCallback(() => {
    setPage(0)
    onOpenChange(false)
  }, [onOpenChange])

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

  const executions = data?.content || []
  const totalPages = data?.totalPages || 0

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="executions-title"
      className="fixed inset-0 z-50 flex items-center justify-center bg-background/80 backdrop-blur-xs p-4 select-none"
    >
      <div className="fixed inset-0" onClick={handleClose} />

      <div className="relative w-full max-w-2xl rounded-sm border border-border bg-card p-5 text-card-foreground shadow-lg space-y-4 z-10 font-sans max-h-[85vh] flex flex-col">
        {/* Header */}
        <div className="flex items-start justify-between pb-3 border-b border-border/70 shrink-0">
          <div className="flex items-center gap-2.5">
            <div className="size-8 rounded-sm bg-muted/60 border border-border flex items-center justify-center text-muted-foreground shrink-0">
              <History className="size-4" />
            </div>
            <div>
              <h2
                id="executions-title"
                className="text-[17px] font-semibold text-foreground tracking-tight"
              >
                Execution History
              </h2>
              <p className="text-[13px] text-muted-foreground mt-0.5">
                Schedule Ref: {maskAccountNumber(schedule.id)} • {schedule.frequency} • ₹
                {Number(schedule.amount).toFixed(2)} INR
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
            <X className="size-4" />
            <span className="sr-only">Close</span>
          </Button>
        </div>

        {/* Content */}
        <div className="overflow-y-auto flex-1 min-h-[220px]">
          {isLoading ? (
            <div className="space-y-2 py-4">
              {[1, 2, 3].map((i) => (
                <div key={i} className="h-16 bg-muted/30 rounded-sm animate-pulse" />
              ))}
            </div>
          ) : error ? (
            <div className="p-4 bg-red-500/10 border border-red-500/20 rounded-sm flex items-start gap-2.5 text-red-700 dark:text-red-400 text-[13px] my-4">
              <AlertCircle className="size-4 shrink-0 mt-0.5" />
              <span>Failed to load execution history: {error.message}</span>
            </div>
          ) : executions.length === 0 ? (
            <div className="text-center py-12 text-muted-foreground text-[13.5px]">
              <History className="size-8 mx-auto mb-2 opacity-40" />
              <p className="font-medium">No executions recorded yet</p>
              <p className="text-[12px] mt-1">
                The scheduler will record an execution attempt when the next run time arrives.
              </p>
            </div>
          ) : (
            <div className="divide-y divide-border/60">
              {executions.map((exec) => {
                const isSuccess = exec.status === "SUCCESS"
                const scheduledDate = new Date(exec.scheduledFor).toLocaleString()
                const executedDate = new Date(exec.executedAt).toLocaleString()

                return (
                  <div
                    key={exec.id}
                    className="py-3 px-2 flex flex-col sm:flex-row sm:items-center justify-between gap-2 hover:bg-muted/20 rounded-xs transition-colors"
                  >
                    <div className="flex items-start gap-2.5">
                      <div className="mt-0.5">
                        {isSuccess ? (
                          <CheckCircle2 className="size-4 text-emerald-600 dark:text-emerald-400" />
                        ) : (
                          <XCircle className="size-4 text-red-600 dark:text-red-400" />
                        )}
                      </div>
                      <div className="space-y-0.5">
                        <div className="flex items-center gap-2">
                          <StatusBadge status={exec.status} />
                          <span className="text-[12px] text-muted-foreground font-mono">
                            Slot: {scheduledDate}
                          </span>
                        </div>
                        {isSuccess && exec.transactionId && (
                          <p className="text-[12px] text-muted-foreground">
                            Transaction Ref:{" "}
                            <span className="font-mono text-foreground">
                              {maskAccountNumber(exec.transactionId)}
                            </span>
                          </p>
                        )}
                        {!isSuccess && exec.failureReason && (
                          <p className="text-[12px] text-red-600 dark:text-red-400">
                            Failure: {exec.failureReason}
                          </p>
                        )}
                      </div>
                    </div>
                    <div className="text-[11.5px] text-muted-foreground text-right shrink-0">
                      Executed: {executedDate}
                    </div>
                  </div>
                )
              })}
            </div>
          )}
        </div>

        {/* Footer / Pagination */}
        <div className="flex items-center justify-between pt-3 border-t border-border/70 shrink-0 text-[13px]">
          <span className="text-muted-foreground text-[12px]">
            {data?.totalElements ? `${data.totalElements} total executions` : ""}
          </span>
          <div className="flex items-center gap-1.5">
            <Button
              type="button"
              variant="outline"
              size="sm"
              disabled={page === 0 || isLoading}
              onClick={() => setPage((p) => Math.max(0, p - 1))}
              className="gap-1 text-[12px] h-8 px-2"
            >
              <ChevronLeft className="size-3.5" />
              Previous
            </Button>
            <span className="text-[12px] text-muted-foreground px-2">
              Page {page + 1} of {Math.max(1, totalPages)}
            </span>
            <Button
              type="button"
              variant="outline"
              size="sm"
              disabled={page + 1 >= totalPages || isLoading}
              onClick={() => setPage((p) => p + 1)}
              className="gap-1 text-[12px] h-8 px-2"
            >
              Next
              <ChevronRight className="size-3.5" />
            </Button>
          </div>
        </div>
      </div>
    </div>
  )
}
