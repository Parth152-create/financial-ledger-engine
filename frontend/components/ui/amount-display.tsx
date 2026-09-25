import * as React from "react"
import { cn } from "@/lib/utils"

interface AmountDisplayProps {
  amount: string | number
  currency?: string
  direction?: "credit" | "debit" | "neutral"
  size?: "xs" | "sm" | "default" | "lg" | "xl"
  align?: "left" | "right" | "center"
  showSign?: boolean
  className?: string
}

const SIZE_CLASSES = {
  xs: "text-xs font-medium",
  sm: "text-xs font-semibold",
  default: "text-sm font-semibold",
  lg: "text-base font-semibold",
  xl: "text-2xl font-bold tracking-tight",
}

export function AmountDisplay({
  amount,
  currency = "INR",
  direction = "neutral",
  size = "default",
  align = "left",
  showSign = false,
  className,
}: AmountDisplayProps) {
  const formattedAmount =
    typeof amount === "number"
      ? amount.toLocaleString("en-IN", { minimumFractionDigits: 2, maximumFractionDigits: 2 })
      : String(amount)

  const sign = direction === "credit" ? "+" : direction === "debit" ? "-" : ""

  const currencySymbol =
    currency === "INR" ? "₹" : currency === "USD" ? "$" : currency === "EUR" ? "€" : currency

  const directionColor =
    direction === "credit"
      ? "text-emerald-700 dark:text-emerald-400"
      : direction === "debit"
      ? "text-foreground dark:text-foreground"
      : "text-foreground"

  return (
    <span
      className={cn(
        "font-mono tabular-nums inline-flex items-baseline gap-1 select-none",
        align === "right" && "justify-end text-right",
        align === "center" && "justify-center text-center",
        directionColor,
        SIZE_CLASSES[size],
        className
      )}
    >
      {showSign && sign && <span>{sign}</span>}
      <span className="text-muted-foreground text-[0.85em] font-sans font-normal">{currencySymbol}</span>
      <span>{formattedAmount}</span>
    </span>
  )
}
