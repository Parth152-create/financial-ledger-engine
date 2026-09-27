"use client"

import * as React from "react"
import { useTheme } from "next-themes"
import { Moon, Sun } from "lucide-react"
import { cn } from "@/lib/utils"

const emptySubscribe = () => () => {}

export function ThemeToggle() {
  const { setTheme, resolvedTheme } = useTheme()
  const buttonRef = React.useRef<HTMLButtonElement>(null)
  const isTransitioningRef = React.useRef(false)

  const mounted = React.useSyncExternalStore(
    emptySubscribe,
    () => true,
    () => false
  )

  const toggleTheme = () => {
    if (isTransitioningRef.current) return

    const nextTheme = resolvedTheme === "dark" ? "light" : "dark"

    // Honor prefers-reduced-motion
    const isReducedMotion =
      typeof window !== "undefined" &&
      window.matchMedia("(prefers-reduced-motion: reduce)").matches

    const doc = typeof document !== "undefined" ? document : null

    // If reduced-motion is requested or SSR, switch immediately without transition
    if (isReducedMotion || !doc) {
      setTheme(nextTheme)
      return
    }

    // Preferred: View Transitions API with global 220ms crossfade
    if ("startViewTransition" in doc && typeof doc.startViewTransition === "function") {
      isTransitioningRef.current = true
      try {
        const transition = doc.startViewTransition(() => {
          setTheme(nextTheme)
        })

        transition.finished.finally(() => {
          isTransitioningRef.current = false
        })
      } catch {
        setTheme(nextTheme)
        isTransitioningRef.current = false
      }
      return
    }

    // Lightweight CSS fallback for browsers without View Transitions API
    isTransitioningRef.current = true
    doc.documentElement.classList.add("theme-transitioning")
    setTheme(nextTheme)
    window.setTimeout(() => {
      doc.documentElement.classList.remove("theme-transitioning")
      isTransitioningRef.current = false
    }, 250)
  }

  if (!mounted) {
    return (
      <button
        type="button"
        disabled
        aria-label="Toggle theme"
        className="size-7 flex items-center justify-center rounded-xs border border-border/70 text-muted-foreground opacity-40 select-none"
      >
        <span className="size-3.5" />
      </button>
    )
  }

  const isDark = resolvedTheme === "dark"

  return (
    <button
      ref={buttonRef}
      type="button"
      onClick={toggleTheme}
      aria-label={isDark ? "Switch to light theme" : "Switch to dark theme"}
      className="size-7 flex items-center justify-center rounded-xs border border-border/70 text-foreground hover:bg-muted/80 transition-colors select-none outline-none focus-visible:ring-1 focus-visible:ring-ring"
    >
      <span className="relative flex items-center justify-center size-3.5 overflow-hidden">
        <Sun
          className={cn(
            "size-3.5 transition-all duration-200 ease-out absolute",
            isDark
              ? "rotate-0 scale-100 opacity-100"
              : "-rotate-20 scale-90 opacity-0 pointer-events-none"
          )}
        />
        <Moon
          className={cn(
            "size-3.5 transition-all duration-200 ease-out absolute",
            isDark
              ? "rotate-20 scale-90 opacity-0 pointer-events-none"
              : "rotate-0 scale-100 opacity-100"
          )}
        />
      </span>
    </button>
  )
}
