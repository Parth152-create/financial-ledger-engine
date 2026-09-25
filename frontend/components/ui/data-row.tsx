import * as React from "react"
import { cn } from "@/lib/utils"

interface DataRowProps {
  label: React.ReactNode
  value: React.ReactNode
  monospace?: boolean
  description?: React.ReactNode
  className?: string
}

export function DataRow({
  label,
  value,
  monospace = false,
  description,
  className,
}: DataRowProps) {
  return (
    <div
      className={cn(
        "flex items-baseline justify-between py-2 border-b border-border/50 text-[13.5px] last:border-b-0 gap-4",
        className
      )}
    >
      <div className="flex flex-col">
        <span className="text-muted-foreground font-sans text-[13px]">{label}</span>
        {description && <span className="text-[12px] text-muted-foreground/75 mt-0.5">{description}</span>}
      </div>
      <div className={cn("text-right font-medium text-foreground", monospace ? "font-mono tabular-nums text-[13.5px]" : "font-sans text-[14px]")}>
        {value}
      </div>
    </div>
  )
}
