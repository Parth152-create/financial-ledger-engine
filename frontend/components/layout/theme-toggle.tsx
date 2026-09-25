"use client"

import * as React from "react"
import { useTheme } from "next-themes"
import { Moon, Sun } from "lucide-react"

const emptySubscribe = () => () => {}

export function ThemeToggle() {
  const { setTheme, resolvedTheme } = useTheme()
  const mounted = React.useSyncExternalStore(
    emptySubscribe,
    () => true,
    () => false
  )

  if (!mounted) {
    return (
      <button
        type="button"
        disabled
        aria-label="Toggle theme"
        className="size-7 flex items-center justify-center rounded-xs border border-border/70 text-muted-foreground opacity-40"
      >
        <span className="size-3.5" />
      </button>
    )
  }

  const isDark = resolvedTheme === "dark"

  return (
    <button
      type="button"
      onClick={() => setTheme(isDark ? "light" : "dark")}
      aria-label={isDark ? "Switch to light theme" : "Switch to dark theme"}
      className="size-7 flex items-center justify-center rounded-xs border border-border/70 text-foreground hover:bg-muted/80 transition-colors"
    >
      {isDark ? <Sun className="size-3.5" /> : <Moon className="size-3.5" />}
    </button>
  )
}
