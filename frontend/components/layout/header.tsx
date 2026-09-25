"use client"

import { usePathname } from "next/navigation"
import { Menu, Search } from "lucide-react"
import { ThemeToggle } from "@/components/layout/theme-toggle"
import { SITE_CONFIG } from "@/config/site"
import { useAuth } from "@/hooks/auth/use-auth"

export function Header({
  onOpenMobileNav,
}: {
  onOpenMobileNav: () => void
}) {
  const pathname = usePathname()
  const { user } = useAuth()

  const currentNav = SITE_CONFIG.navigation.find((item) =>
    item.exact ? pathname === item.href : pathname === item.href || pathname.startsWith(`${item.href}/`)
  )

  const pageTitle = currentNav?.title || "Overview"

  return (
    <header className="h-13 border-b border-border bg-background px-4 flex items-center justify-between gap-4 sticky top-0 z-30 select-none">
      <div className="flex items-center gap-3">
        <button
          type="button"
          onClick={onOpenMobileNav}
          aria-label="Open navigation menu"
          className="md:hidden p-1.5 rounded-sm border border-border text-foreground hover:bg-muted transition-colors"
        >
          <Menu className="size-4" />
        </button>

        <div className="flex items-center gap-2">
          <h1 className="text-sm font-semibold text-foreground font-sans tracking-tight">
            {pageTitle}
          </h1>
        </div>
      </div>

      <div className="flex items-center gap-3">
        <div className="hidden sm:flex items-center gap-2 px-2.5 py-1 rounded-sm border border-border/80 bg-muted/30 text-xs text-muted-foreground">
          <Search className="size-3.5 text-muted-foreground/60" />
          <span className="text-[11px]">Search transactions, accounts...</span>
        </div>

        <ThemeToggle />

        {user && (
          <div className="hidden sm:flex items-center gap-2 pl-2 border-l border-border text-xs">
            <span className="font-medium text-foreground truncate max-w-[140px]">{user.name}</span>
          </div>
        )}
      </div>
    </header>
  )
}
