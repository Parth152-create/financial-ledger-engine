"use client"

import * as React from "react"
import { X, Copy, Check, ShieldCheck } from "lucide-react"
import { Button } from "@/components/ui/button"
import { AuditEventBadge } from "./audit-event-badge"
import { formatDate } from "@/lib/formatters/date"
import { formatEntityLabel, formatEventSummary } from "@/lib/formatters/audit"
import { formatINR } from "@/lib/formatters/currency"
import type { AuditEvent } from "@/types/audit"

interface AuditDetailDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  event: AuditEvent | null
}

export function AuditDetailDialog({
  open,
  onOpenChange,
  event,
}: AuditDetailDialogProps) {
  const [copiedKey, setCopiedKey] = React.useState<string | null>(null)

  const handleClose = () => {
    setCopiedKey(null)
    onOpenChange(false)
  }

  React.useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape" && open) {
        setCopiedKey(null)
        onOpenChange(false)
      }
    }
    window.addEventListener("keydown", handleKeyDown)
    return () => window.removeEventListener("keydown", handleKeyDown)
  }, [open, onOpenChange])

  if (!open || !event) return null

  const copyToClipboard = async (text: string, key: string) => {
    try {
      await navigator.clipboard.writeText(text)
      setCopiedKey(key)
      setTimeout(() => setCopiedKey(null), 2000)
    } catch {
      // Clipboard fallback
    }
  }

  const metadata = event.metadata || {}
  const summary = formatEventSummary(event)

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="audit-detail-title"
      className="fixed inset-0 z-50 flex items-center justify-center bg-background/80 backdrop-blur-xs p-4 select-none"
    >
      <div className="fixed inset-0" onClick={handleClose} />

      <div className="relative w-full max-w-lg rounded-sm border border-border bg-card p-5 text-card-foreground shadow-lg space-y-4 z-10 font-sans max-h-[90vh] overflow-y-auto">
        <div className="flex items-start justify-between pb-3 border-b border-border/70">
          <div className="flex items-center gap-2.5">
            <div className="size-8 rounded-sm bg-muted/60 border border-border flex items-center justify-center text-muted-foreground shrink-0">
              <ShieldCheck className="size-4" />
            </div>
            <div>
              <h2
                id="audit-detail-title"
                className="text-base font-semibold text-foreground tracking-tight leading-tight"
              >
                Audit Event Record
              </h2>
              <p className="text-xs text-muted-foreground mt-0.5">
                {summary}
              </p>
            </div>
          </div>
          <button
            type="button"
            onClick={handleClose}
            className="rounded-xs p-1 text-muted-foreground hover:text-foreground hover:bg-muted/60 transition-colors"
            aria-label="Close dialog"
          >
            <X className="size-4" />
          </button>
        </div>

        <div className="space-y-3 text-xs">
          {/* Core Event Data */}
          <div className="p-3 bg-muted/30 border border-border/70 rounded-sm space-y-2.5">
            <div className="flex items-center justify-between">
              <span className="text-muted-foreground font-medium">Event Type</span>
              <AuditEventBadge eventType={event.eventType} />
            </div>

            <div className="flex items-center justify-between">
              <span className="text-muted-foreground font-medium">Target Entity</span>
              <span className="font-semibold text-foreground font-mono">
                {formatEntityLabel(event.entityType)}
              </span>
            </div>

            <div className="flex items-center justify-between">
              <span className="text-muted-foreground font-medium">Timestamp</span>
              <span className="font-medium text-foreground">
                {formatDate(event.createdAt)}
              </span>
            </div>

            <div className="flex items-center justify-between">
              <span className="text-muted-foreground font-medium">Entity Reference</span>
              <div className="flex items-center gap-1.5 font-mono text-[11px] text-muted-foreground">
                <span>{event.entityId ? `${event.entityId.slice(0, 8)}...${event.entityId.slice(-4)}` : "—"}</span>
                {event.entityId && (
                  <button
                    type="button"
                    onClick={() => copyToClipboard(event.entityId, "entityId")}
                    className="p-1 hover:text-foreground hover:bg-muted/80 rounded-xs transition-colors"
                    title="Copy full Entity ID"
                  >
                    {copiedKey === "entityId" ? (
                      <Check className="size-3 text-emerald-600 dark:text-emerald-400" />
                    ) : (
                      <Copy className="size-3" />
                    )}
                  </button>
                )}
              </div>
            </div>

            <div className="flex items-center justify-between">
              <span className="text-muted-foreground font-medium">Event ID</span>
              <div className="flex items-center gap-1.5 font-mono text-[11px] text-muted-foreground">
                <span>{event.id.slice(0, 8)}...{event.id.slice(-4)}</span>
                <button
                  type="button"
                  onClick={() => copyToClipboard(event.id, "eventId")}
                  className="p-1 hover:text-foreground hover:bg-muted/80 rounded-xs transition-colors"
                  title="Copy full Event ID"
                >
                  {copiedKey === "eventId" ? (
                    <Check className="size-3 text-emerald-600 dark:text-emerald-400" />
                  ) : (
                    <Copy className="size-3" />
                  )}
                </button>
              </div>
            </div>
          </div>

          {/* Operational Metadata */}
          {Object.keys(metadata).length > 0 && (
            <div className="space-y-1.5">
              <h3 className="font-semibold text-foreground text-[11px] tracking-wider uppercase text-muted-foreground">
                Contextual Metadata
              </h3>
              <div className="border border-border/70 rounded-sm divide-y divide-border/60 bg-card overflow-hidden">
                {Object.entries(metadata).map(([key, val]) => {
                  let formattedValue = String(val)
                  if (key === "amount" && (typeof val === "number" || typeof val === "string")) {
                    formattedValue = formatINR(Number(val))
                  } else if (typeof val === "boolean") {
                    formattedValue = val ? "True" : "False"
                  } else if (val === null || val === undefined) {
                    formattedValue = "—"
                  }

                  const formattedKey = key
                    .replace(/([A-Z])/g, " $1")
                    .replace(/_/g, " ")
                    .replace(/^\w/, (c) => c.toUpperCase())

                  return (
                    <div
                      key={key}
                      className="px-3 py-2 flex items-center justify-between text-xs gap-3"
                    >
                      <span className="text-muted-foreground font-medium shrink-0">
                        {formattedKey}
                      </span>
                      <span className="font-mono text-foreground text-right truncate max-w-[260px]">
                        {formattedValue}
                      </span>
                    </div>
                  )
                })}
              </div>
            </div>
          )}
        </div>

        <div className="pt-2 flex justify-end">
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={handleClose}
            className="text-xs h-8 px-4"
          >
            Close
          </Button>
        </div>
      </div>
    </div>
  )
}
