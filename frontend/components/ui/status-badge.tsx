import * as React from "react"
import { cn } from "@/lib/utils"

export type FinancialStatus =
  | "ACTIVE"
  | "FROZEN"
  | "CLOSED"
  | "COMPLETED"
  | "PENDING"
  | "FAILED"
  | "CONSISTENT"
  | "DISCREPANCY"

interface StatusBadgeProps {
  status: FinancialStatus | string
  className?: string
  showDot?: boolean
}

const STATUS_CONFIG: Record<
  string,
  { label: string; dot: string; text: string; bg: string; border: string }
> = {
  ACTIVE: {
    label: "Active",
    dot: "bg-emerald-500",
    text: "text-emerald-700 dark:text-emerald-400",
    bg: "bg-emerald-500/10",
    border: "border-emerald-500/20",
  },
  COMPLETED: {
    label: "Completed",
    dot: "bg-emerald-500",
    text: "text-emerald-700 dark:text-emerald-400",
    bg: "bg-emerald-500/10",
    border: "border-emerald-500/20",
  },
  CONSISTENT: {
    label: "Consistent",
    dot: "bg-emerald-500",
    text: "text-emerald-700 dark:text-emerald-400",
    bg: "bg-emerald-500/10",
    border: "border-emerald-500/20",
  },
  PENDING: {
    label: "Pending",
    dot: "bg-amber-500",
    text: "text-amber-700 dark:text-amber-400",
    bg: "bg-amber-500/10",
    border: "border-amber-500/20",
  },
  FROZEN: {
    label: "Frozen",
    dot: "bg-amber-500",
    text: "text-amber-700 dark:text-amber-400",
    bg: "bg-amber-500/10",
    border: "border-amber-500/20",
  },
  FAILED: {
    label: "Failed",
    dot: "bg-red-500",
    text: "text-red-700 dark:text-red-400",
    bg: "bg-red-500/10",
    border: "border-red-500/20",
  },
  DISCREPANCY: {
    label: "Discrepancy",
    dot: "bg-red-500",
    text: "text-red-700 dark:text-red-400",
    bg: "bg-red-500/10",
    border: "border-red-500/20",
  },
  CLOSED: {
    label: "Closed",
    dot: "bg-muted-foreground/60",
    text: "text-muted-foreground",
    bg: "bg-muted/50",
    border: "border-border",
  },
}

export function StatusBadge({ status, className, showDot = true }: StatusBadgeProps) {
  const normalized = (status || "").toUpperCase()
  const config = STATUS_CONFIG[normalized] || {
    label: status || "Unknown",
    dot: "bg-muted-foreground/60",
    text: "text-muted-foreground",
    bg: "bg-muted/40",
    border: "border-border",
  }

  return (
    <span
      className={cn(
        "inline-flex items-center gap-1.5 px-2 py-0.5 rounded-sm border text-[11px] font-sans font-medium tracking-normal select-none",
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
