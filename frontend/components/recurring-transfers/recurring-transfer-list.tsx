"use client"

import * as React from "react"
import {
  CalendarClock,
  Plus,
  Play,
  Pause,
  Trash2,
  History,
  ChevronLeft,
  ChevronRight,
  AlertCircle,
} from "lucide-react"
import { Button } from "@/components/ui/button"
import { StatusBadge } from "@/components/ui/status-badge"
import { useAccounts } from "@/hooks/api/use-accounts"
import {
  useRecurringTransfers,
  usePauseRecurringTransfer,
  useResumeRecurringTransfer,
  useCancelRecurringTransfer,
} from "@/hooks/api/use-recurring-transfers"
import { formatAccountFlowLabel } from "@/lib/formatters/ledger"
import { CreateRecurringTransferDialog } from "./create-recurring-transfer-dialog"
import { RecurringTransferExecutionsDialog } from "./recurring-transfer-executions-dialog"
import { RecurringTransferDetailDialog } from "./recurring-transfer-detail-dialog"
import type { RecurringTransfer, RecurringTransferStatus } from "@/types/recurring"

export function RecurringTransferList() {
  const [statusFilter, setStatusFilter] = React.useState<RecurringTransferStatus | "ALL">("ALL")
  const [page, setPage] = React.useState(0)
  const pageSize = 10

  const [createDialogOpen, setCreateDialogOpen] = React.useState(false)
  const [detailSchedule, setDetailSchedule] = React.useState<RecurringTransfer | null>(null)
  const [executionsSchedule, setExecutionsSchedule] = React.useState<RecurringTransfer | null>(null)

  const queryParams = React.useMemo(() => {
    return {
      status: statusFilter === "ALL" ? undefined : statusFilter,
      page,
      size: pageSize,
    }
  }, [statusFilter, page, pageSize])

  const { data: data, isLoading, error, refetch } = useRecurringTransfers(queryParams)
  const { data: accounts } = useAccounts()
  const pauseMutation = usePauseRecurringTransfer()
  const resumeMutation = useResumeRecurringTransfer()
  const cancelMutation = useCancelRecurringTransfer()

  const schedules = data?.content || []
  const totalPages = data?.totalPages || 0

  const handlePause = async (schedule: RecurringTransfer) => {
    try {
      await pauseMutation.mutateAsync(schedule.id)
    } catch {
      // Handled in mutation state
    }
  }

  const handleResume = async (schedule: RecurringTransfer) => {
    try {
      await resumeMutation.mutateAsync(schedule.id)
    } catch {
      // Handled in mutation state
    }
  }

  const handleCancel = async (schedule: RecurringTransfer) => {
    if (confirm("Are you sure you want to cancel this recurring schedule? This cannot be undone.")) {
      try {
        await cancelMutation.mutateAsync(schedule.id)
      } catch {
        // Handled in mutation state
      }
    }
  }

  return (
    <div className="space-y-4 font-sans select-none">
      {/* Top action & filter bar */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 pb-2">
        <div className="flex items-center gap-1.5 overflow-x-auto pb-1 sm:pb-0">
          {(["ALL", "ACTIVE", "PAUSED", "COMPLETED", "CANCELLED"] as const).map((st) => (
            <button
              key={st}
              type="button"
              onClick={() => {
                setStatusFilter(st)
                setPage(0)
              }}
              className={`px-3 py-1 rounded-sm text-[12.5px] font-medium transition-colors whitespace-nowrap ${
                statusFilter === st
                  ? "bg-foreground text-background font-semibold"
                  : "bg-muted/40 text-muted-foreground hover:text-foreground hover:bg-muted/60"
              }`}
            >
              {st === "ALL" ? "All Schedules" : st.charAt(0) + st.slice(1).toLowerCase()}
            </button>
          ))}
        </div>

        <Button
          type="button"
          onClick={() => setCreateDialogOpen(true)}
          className="gap-1.5 shrink-0 h-9 text-[13px]"
        >
          <Plus className="size-4" />
          Schedule Transfer
        </Button>
      </div>

      {/* Schedule Table / Cards */}
      {isLoading ? (
        <div className="space-y-3">
          {[1, 2, 3].map((i) => (
            <div key={i} className="h-20 bg-muted/20 border border-border/70 rounded-sm animate-pulse" />
          ))}
        </div>
      ) : error ? (
        <div className="p-4 bg-red-500/10 border border-red-500/20 rounded-sm flex items-center justify-between text-red-700 dark:text-red-400 text-[13px]">
          <div className="flex items-center gap-2">
            <AlertCircle className="size-4 shrink-0" />
            <span>Failed to load recurring transfers: {error.message}</span>
          </div>
          <Button variant="outline" size="sm" onClick={() => refetch()} className="text-[12px] h-7">
            Retry
          </Button>
        </div>
      ) : schedules.length === 0 ? (
        <div className="rounded-sm border border-dashed border-border p-12 text-center bg-card/40">
          <CalendarClock className="size-10 mx-auto text-muted-foreground/50 mb-3" />
          <h3 className="text-[15px] font-semibold text-foreground">No recurring transfers</h3>
          <p className="text-[13px] text-muted-foreground mt-1 max-w-sm mx-auto">
            {statusFilter === "ALL"
              ? "You haven't set up any recurring transfers yet. Automate regular transfers on daily, weekly, or monthly intervals."
              : `No schedules found with status ${statusFilter.toLowerCase()}.`}
          </p>
          {statusFilter === "ALL" && (
            <Button
              type="button"
              onClick={() => setCreateDialogOpen(true)}
              className="mt-4 gap-1.5 h-8 text-[12.5px]"
            >
              <Plus className="size-3.5" />
              Create your first schedule
            </Button>
          )}
        </div>
      ) : (
        <div className="border border-border/70 rounded-sm divide-y divide-border/60 bg-card overflow-hidden">
          {schedules.map((schedule) => {
            const isTerminal = schedule.status === "CANCELLED" || schedule.status === "COMPLETED"
            const nextRunText = schedule.nextExecutionAt
              ? new Date(schedule.nextExecutionAt).toLocaleString(undefined, {
                  month: "short",
                  day: "numeric",
                  hour: "2-digit",
                  minute: "2-digit",
                })
              : "None"

            return (
              <div
                key={schedule.id}
                className="p-4 flex flex-col md:flex-row md:items-center justify-between gap-3 hover:bg-muted/15 transition-colors"
              >
                {/* Main Info */}
                <div className="space-y-1.5 flex-1 min-w-0">
                  <div className="flex flex-wrap items-center gap-2.5">
                    <StatusBadge status={schedule.status} />
                    <span className="font-semibold text-[15px] text-foreground font-mono">
                      ₹{Number(schedule.amount).toFixed(2)} {schedule.currency}
                    </span>
                    <span className="text-[12px] px-2 py-0.5 rounded-xs bg-muted/60 text-muted-foreground font-medium uppercase tracking-wider">
                      {schedule.frequency}
                    </span>
                  </div>

                  <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-[12.5px] text-muted-foreground">
                    <div>
                      From:{" "}
                      <span className="font-medium text-foreground">
                        {formatAccountFlowLabel(schedule.sourceAccountId, undefined, accounts)}
                      </span>
                    </div>
                    <div>
                      To:{" "}
                      <span className="font-medium text-foreground">
                        {formatAccountFlowLabel(schedule.destinationAccountId, undefined, accounts)}
                      </span>
                    </div>
                    <div>
                      Next run:{" "}
                      <span className="font-medium text-foreground">
                        {nextRunText}
                      </span>
                    </div>
                    <div>
                      Executions:{" "}
                      <span className="text-emerald-600 dark:text-emerald-400 font-medium">
                        {schedule.executionCount}
                      </span>
                      {schedule.failureCount > 0 && (
                        <span className="text-red-600 dark:text-red-400 font-medium ml-1">
                          ({schedule.failureCount} failed)
                        </span>
                      )}
                    </div>
                  </div>
                </div>

                {/* Actions */}
                <div className="flex items-center gap-1.5 self-end md:self-center shrink-0">
                  {schedule.status === "ACTIVE" && (
                    <Button
                      type="button"
                      variant="outline"
                      size="sm"
                      onClick={() => handlePause(schedule)}
                      title="Pause recurring schedule"
                      className="gap-1 h-8 text-[12px] text-amber-600 dark:text-amber-400 hover:text-amber-700"
                    >
                      <Pause className="size-3.5" />
                      Pause
                    </Button>
                  )}

                  {schedule.status === "PAUSED" && (
                    <Button
                      type="button"
                      variant="outline"
                      size="sm"
                      onClick={() => handleResume(schedule)}
                      title="Resume recurring schedule"
                      className="gap-1 h-8 text-[12px] text-emerald-600 dark:text-emerald-400 hover:text-emerald-700"
                    >
                      <Play className="size-3.5" />
                      Resume
                    </Button>
                  )}

                  <Button
                    type="button"
                    variant="ghost"
                    size="sm"
                    onClick={() => setExecutionsSchedule(schedule)}
                    title="View execution history"
                    className="gap-1 h-8 text-[12px] text-muted-foreground hover:text-foreground"
                  >
                    <History className="size-3.5" />
                    History
                  </Button>

                  <Button
                    type="button"
                    variant="ghost"
                    size="sm"
                    onClick={() => setDetailSchedule(schedule)}
                    title="View schedule details"
                    className="h-8 text-[12px] px-2 text-muted-foreground hover:text-foreground"
                  >
                    Details
                  </Button>

                  {!isTerminal && (
                    <Button
                      type="button"
                      variant="ghost"
                      size="icon-xs"
                      onClick={() => handleCancel(schedule)}
                      title="Cancel schedule"
                      className="text-red-500/70 hover:text-red-600 hover:bg-red-500/10 size-8"
                    >
                      <Trash2 className="size-3.5" />
                      <span className="sr-only">Cancel schedule</span>
                    </Button>
                  )}
                </div>
              </div>
            )
          })}
        </div>
      )}

      {/* Pagination Bar */}
      {totalPages > 1 && (
        <div className="flex items-center justify-between pt-2 text-[13px]">
          <span className="text-muted-foreground text-[12px]">
            {data?.totalElements ? `${data.totalElements} total schedules` : ""}
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
              Page {page + 1} of {totalPages}
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
      )}

      {/* Dialogs */}
      <CreateRecurringTransferDialog
        open={createDialogOpen}
        onOpenChange={setCreateDialogOpen}
      />

      <RecurringTransferDetailDialog
        schedule={detailSchedule}
        open={Boolean(detailSchedule)}
        onOpenChange={(open) => !open && setDetailSchedule(null)}
        onViewExecutions={(sched) => {
          setDetailSchedule(null)
          setExecutionsSchedule(sched)
        }}
      />

      <RecurringTransferExecutionsDialog
        schedule={executionsSchedule}
        open={Boolean(executionsSchedule)}
        onOpenChange={(open) => !open && setExecutionsSchedule(null)}
      />
    </div>
  )
}
