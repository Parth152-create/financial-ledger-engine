import * as React from "react"
import { cn } from "@/lib/utils"
import { getEventTypeConfig } from "@/lib/formatters/audit"

interface AuditEventBadgeProps {
  eventType: string
  className?: string
  showDot?: boolean
}

export function AuditEventBadge({
  eventType,
  className,
  showDot = true,
}: AuditEventBadgeProps) {
  const config = getEventTypeConfig(eventType)

  return (
    <span
      className={cn(
        "inline-flex items-center gap-1.5 px-2 py-0.5 rounded-sm border text-[12px] font-sans font-medium tracking-normal select-none shrink-0 whitespace-nowrap",
        config.bg,
        config.border,
        config.text,
        className
      )}
    >
      {showDot && <span className={cn("size-1.5 rounded-full shrink-0", config.dot)} />}
      <span>{config.label}</span>
    </span>
  )
}
