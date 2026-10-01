"use client"

import * as React from "react"
import { CalendarClock, CheckCircle, PauseCircle, Ban, Repeat } from "lucide-react"
import { RecurringTransferList } from "@/components/recurring-transfers/recurring-transfer-list"
import { useRecurringTransfers } from "@/hooks/api/use-recurring-transfers"

export default function RecurringTransfersPage() {
  const { data: allSchedules } = useRecurringTransfers()

  const stats = React.useMemo(() => {
    const list = allSchedules?.content || []
    let active = 0
    let paused = 0
    let terminal = 0
    for (const item of list) {
      if (item.status === "ACTIVE") active++
      else if (item.status === "PAUSED") paused++
      else terminal++
    }
    return {
      total: allSchedules?.totalElements || list.length,
      active,
      paused,
      terminal,
    }
  }, [allSchedules])

  return (
    <div className="space-y-6 select-none font-sans max-w-6xl mx-auto">
      {/* Page Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 pb-3 border-b border-border/70">
        <div className="flex items-center gap-3">
          <div className="size-10 rounded-sm bg-muted/60 border border-border flex items-center justify-center text-muted-foreground shrink-0">
            <CalendarClock className="size-5" />
          </div>
          <div>
            <h1 className="text-[24px] font-semibold tracking-tight text-foreground leading-tight">
              Recurring Transfers
            </h1>
            <p className="text-[13.5px] text-muted-foreground mt-0.5">
              Automated transfer schedules executed safely via the authoritative double-entry engine.
            </p>
          </div>
        </div>
      </div>

      {/* Overview Metric Cards */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
        <div className="p-3.5 bg-card border border-border/70 rounded-sm">
          <div className="flex items-center justify-between text-muted-foreground">
            <span className="text-[12px] font-medium uppercase tracking-wider">Total Schedules</span>
            <Repeat className="size-4 opacity-70" />
          </div>
          <div className="text-[22px] font-semibold text-foreground mt-1 font-mono">
            {stats.total}
          </div>
        </div>

        <div className="p-3.5 bg-card border border-border/70 rounded-sm">
          <div className="flex items-center justify-between text-emerald-600 dark:text-emerald-400">
            <span className="text-[12px] font-medium uppercase tracking-wider text-muted-foreground">
              Active
            </span>
            <CheckCircle className="size-4" />
          </div>
          <div className="text-[22px] font-semibold text-emerald-600 dark:text-emerald-400 mt-1 font-mono">
            {stats.active}
          </div>
        </div>

        <div className="p-3.5 bg-card border border-border/70 rounded-sm">
          <div className="flex items-center justify-between text-amber-600 dark:text-amber-400">
            <span className="text-[12px] font-medium uppercase tracking-wider text-muted-foreground">
              Paused
            </span>
            <PauseCircle className="size-4" />
          </div>
          <div className="text-[22px] font-semibold text-amber-600 dark:text-amber-400 mt-1 font-mono">
            {stats.paused}
          </div>
        </div>

        <div className="p-3.5 bg-card border border-border/70 rounded-sm">
          <div className="flex items-center justify-between text-muted-foreground">
            <span className="text-[12px] font-medium uppercase tracking-wider">Ended / Cancelled</span>
            <Ban className="size-4 opacity-70" />
          </div>
          <div className="text-[22px] font-semibold text-foreground mt-1 font-mono">
            {stats.terminal}
          </div>
        </div>
      </div>

      {/* Main recurring transfers list */}
      <div className="space-y-4">
        <RecurringTransferList />
      </div>
    </div>
  )
}
