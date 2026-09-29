import * as React from "react"
import { ArrowLeftRight, ArrowDownLeft, ArrowUpRight, RotateCcw, Landmark } from "lucide-react"
import { cn } from "@/lib/utils"

export type TransactionType = "TRANSFER" | "DEPOSIT" | "WITHDRAWAL" | "REVERSAL" | "SYSTEM_FUNDING"

interface TransactionTypeBadgeProps {
  type: TransactionType | string
  className?: string
  showIcon?: boolean
}

const TYPE_LABELS: Record<string, string> = {
  TRANSFER: "Transfer",
  DEPOSIT: "Deposit",
  WITHDRAWAL: "Withdrawal",
  REVERSAL: "Reversal",
  SYSTEM_FUNDING: "System Funding",
}

export function TransactionTypeBadge({
  type,
  className,
  showIcon = true,
}: TransactionTypeBadgeProps) {
  const normalized = (type || "").toUpperCase()
  const label = TYPE_LABELS[normalized] || type

  const Icon =
    normalized === "TRANSFER"
      ? ArrowLeftRight
      : normalized === "DEPOSIT"
      ? ArrowDownLeft
      : normalized === "WITHDRAWAL"
      ? ArrowUpRight
      : normalized === "REVERSAL"
      ? RotateCcw
      : Landmark

  return (
    <span
      className={cn(
        "inline-flex items-center gap-1.5 text-xs font-sans font-medium text-foreground select-none",
        className
      )}
    >
      {showIcon && <Icon className="size-3 text-muted-foreground shrink-0" />}
      <span>{label}</span>
    </span>
  )
}
