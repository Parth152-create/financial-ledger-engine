"use client"

import * as React from "react"
import { useTheme } from "next-themes"
import { Sun, Moon, Laptop } from "lucide-react"
import { cn } from "@/lib/utils"

const emptySubscribe = () => () => {}

function useHasMounted() {
  return React.useSyncExternalStore(
    emptySubscribe,
    () => true,
    () => false
  )
}

export function ThemeSelector() {
  const { theme, setTheme } = useTheme()
  const mounted = useHasMounted()

  const options = [
    { value: "light", label: "Light", icon: Sun },
    { value: "dark", label: "Dark", icon: Moon },
    { value: "system", label: "System", icon: Laptop },
  ] as const

  if (!mounted) {
    return (
      <div className="flex items-center gap-1 border border-border/70 rounded-sm p-0.5 bg-muted/20 animate-pulse">
        <div className="h-7 w-20 bg-muted/60 rounded-xs" />
        <div className="h-7 w-20 bg-muted/60 rounded-xs" />
        <div className="h-7 w-20 bg-muted/60 rounded-xs" />
      </div>
    )
  }

  return (
    <div
      role="radiogroup"
      aria-label="Theme preference"
      className="flex items-center gap-1 border border-border/70 rounded-sm p-0.5 bg-muted/20 select-none"
    >
      {options.map((opt) => {
        const Icon = opt.icon
        const isSelected = theme === opt.value

        return (
          <button
            key={opt.value}
            type="button"
            role="radio"
            aria-checked={isSelected}
            aria-label={`${opt.label} theme`}
            onClick={() => setTheme(opt.value)}
            className={cn(
              "flex items-center gap-1.5 px-3 py-1.5 text-xs rounded-xs font-medium transition-colors outline-none focus-visible:ring-1 focus-visible:ring-ring",
              isSelected
                ? "bg-foreground text-background shadow-2xs font-semibold"
                : "text-muted-foreground hover:text-foreground hover:bg-muted/50 border border-transparent"
            )}
          >
            <Icon className="size-3.5" />
            <span>{opt.label}</span>
          </button>
        )
      })}
    </div>
  )
}
