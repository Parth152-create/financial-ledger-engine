import * as React from "react"
import { cn } from "@/lib/utils"

interface SectionProps extends Omit<React.HTMLAttributes<HTMLDivElement>, "title"> {
  title?: React.ReactNode
  description?: React.ReactNode
  badge?: React.ReactNode
  actions?: React.ReactNode
  variant?: "bordered" | "flat" | "subtle"
}

export function Section({
  title,
  description,
  badge,
  actions,
  variant = "bordered",
  className,
  children,
  ...props
}: SectionProps) {
  const hasHeader = Boolean(title || description || badge || actions)

  return (
    <section
      className={cn(
        "relative text-foreground",
        variant === "bordered" && "rounded-sm border border-border bg-card shadow-2xs",
        variant === "flat" && "border-b border-border bg-transparent",
        variant === "subtle" && "rounded-sm border border-border/70 bg-muted/30",
        className
      )}
      {...props}
    >
      {hasHeader && (
        <div className="flex flex-wrap items-center justify-between gap-2 px-4 py-2.5 border-b border-border/70">
          <div className="flex items-center gap-2.5 min-w-0">
            {typeof title === "string" ? (
              <h2 className="text-xs font-semibold text-foreground font-sans">
                {title}
              </h2>
            ) : (
              title
            )}
            {badge}
          </div>
          {actions && <div className="flex items-center gap-2">{actions}</div>}
          {description && (
            <p className="w-full text-xs text-muted-foreground font-sans mt-0.5">{description}</p>
          )}
        </div>
      )}
      <div className="p-4">{children}</div>
    </section>
  )
}
