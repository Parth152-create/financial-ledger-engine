import * as React from "react"
import { ArrowLeftRight, ArrowDownLeft, ArrowUpRight } from "lucide-react"
import { cn } from "@/lib/utils"

export type TransactionType = "TRANSFER" | "DEPOSIT" | "WITHDRAWAL"

interface TransactionTypeBadgeProps {
  type: TransactionType | string
  className?: string
  showIcon?: boolean
}

const TYPE_LABELS: Record<string, string> = {
  TRANSFER: "Transfer",
  DEPOSIT: "Deposit",
  WITHDRAWAL: "Withdrawal",
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
      : ArrowUpRight

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
