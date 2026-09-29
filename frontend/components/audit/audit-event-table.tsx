"use client"

import * as React from "react"
import { Eye, Clock } from "lucide-react"
import { AuditEventBadge } from "./audit-event-badge"
import { formatDate } from "@/lib/formatters/date"
import { formatEntityLabel, formatEventSummary } from "@/lib/formatters/audit"
import type { AuditEvent } from "@/types/audit"

interface AuditEventTableProps {
  events: AuditEvent[]
  isLoading?: boolean
  onSelectEvent: (event: AuditEvent) => void
}

export function AuditEventTable({
  events,
  isLoading = false,
  onSelectEvent,
}: AuditEventTableProps) {
  if (isLoading) {
    return (
      <div className="border border-border/70 rounded-sm overflow-hidden bg-card font-sans select-none">
        <div className="hidden sm:grid sm:grid-cols-12 gap-3 px-4 py-2.5 bg-muted/40 text-[11px] font-medium text-muted-foreground uppercase tracking-wider border-b border-border/70">
          <span className="col-span-2">Timestamp</span>
          <span className="col-span-3">Event</span>
          <span className="col-span-2">Entity</span>
          <span className="col-span-4">Summary</span>
          <span className="col-span-1 text-right">Details</span>
        </div>
        <div className="divide-y divide-border/50">
          {Array.from({ length: 5 }).map((_, i) => (
            <div
              key={i}
              className="grid grid-cols-12 items-center gap-3 px-4 py-3.5 animate-pulse"
            >
              <div className="col-span-12 sm:col-span-2">
                <div className="h-3 w-28 bg-muted rounded-xs" />
              </div>
              <div className="col-span-6 sm:col-span-3">
                <div className="h-4 w-32 bg-muted rounded-xs" />
              </div>
              <div className="col-span-6 sm:col-span-2">
                <div className="h-3 w-16 bg-muted/80 rounded-xs" />
              </div>
              <div className="col-span-10 sm:col-span-4">
                <div className="h-3.5 w-48 bg-muted/60 rounded-xs" />
              </div>
              <div className="col-span-2 sm:col-span-1 flex justify-end">
                <div className="h-5 w-12 bg-muted/50 rounded-xs" />
              </div>
            </div>
          ))}
        </div>
      </div>
    )
  }

  if (events.length === 0) {
    return (
      <div className="border border-border/70 rounded-sm bg-card p-12 text-center space-y-2.5 font-sans">
        <Clock className="size-8 text-muted-foreground mx-auto stroke-1" />
        <p className="text-sm font-medium text-foreground">No Audit Events Found</p>
        <p className="text-xs text-muted-foreground max-w-sm mx-auto">
          No operational events match your current filter parameters. Events are recorded automatically when security, account, or financial actions occur.
        </p>
      </div>
    )
  }

  return (
    <div className="border border-border/70 rounded-sm overflow-hidden bg-card font-sans select-none">
      <div className="hidden sm:grid sm:grid-cols-12 gap-3 px-4 py-2.5 bg-muted/40 text-[11px] font-medium text-muted-foreground uppercase tracking-wider border-b border-border/70">
        <span className="col-span-2">Timestamp</span>
        <span className="col-span-3">Event</span>
        <span className="col-span-2">Entity</span>
        <span className="col-span-4">Summary</span>
        <span className="col-span-1 text-right">Details</span>
      </div>

      <div className="divide-y divide-border/60">
        {events.map((event) => {
          const summary = formatEventSummary(event)
          return (
            <div
              key={event.id}
              onClick={() => onSelectEvent(event)}
              className="grid grid-cols-12 items-center gap-3 px-4 py-3 hover:bg-muted/30 cursor-pointer transition-colors"
            >
              <div className="col-span-12 sm:col-span-2 text-xs text-muted-foreground font-mono">
                {formatDate(event.createdAt)}
              </div>

              <div className="col-span-6 sm:col-span-3">
                <AuditEventBadge eventType={event.eventType} />
              </div>

              <div className="col-span-6 sm:col-span-2 text-xs font-mono font-medium text-foreground">
                {formatEntityLabel(event.entityType)}
              </div>

              <div className="col-span-10 sm:col-span-4 text-xs text-foreground truncate">
                {summary}
              </div>

              <div className="col-span-2 sm:col-span-1 flex justify-end">
                <button
                  type="button"
                  onClick={(e) => {
                    e.stopPropagation()
                    onSelectEvent(event)
                  }}
                  className="inline-flex items-center gap-1 text-[11px] font-medium text-muted-foreground hover:text-foreground p-1 rounded-xs hover:bg-muted/60 transition-colors"
                  aria-label="Inspect audit event"
                >
                  <Eye className="size-3.5" />
                  <span className="hidden lg:inline">Inspect</span>
                </button>
              </div>
            </div>
          )
        })}
      </div>
    </div>
  )
}
