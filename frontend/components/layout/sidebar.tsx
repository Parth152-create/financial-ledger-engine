"use client"

import Link from "next/link"
import { usePathname } from "next/navigation"
import { LogOut } from "lucide-react"
import { SITE_CONFIG } from "@/config/site"
import { useAuth } from "@/hooks/auth/use-auth"
import { cn } from "@/lib/utils"

export function Sidebar({ className }: { className?: string }) {
  const pathname = usePathname()
  const { user, logout } = useAuth()

  const sections = ["Operations", "Audit & Reporting", "System"] as const

  return (
    <aside
      className={cn(
        "flex flex-col h-full bg-sidebar border-r border-sidebar-border select-none text-sidebar-foreground",
        className
      )}
    >
      <div className="h-13 flex items-center px-4 border-b border-sidebar-border bg-sidebar">
        <Link href="/app" className="flex items-center gap-2.5">
          <div className="size-6.5 rounded-sm bg-foreground text-background flex items-center justify-center font-bold text-xs tracking-tight">
            FL
          </div>
          <div className="flex flex-col leading-none">
            <span className="text-sm font-semibold tracking-tight text-foreground">
              Financial Ledger
            </span>
            <span className="text-xs text-muted-foreground mt-0.5">
              Engine
            </span>
          </div>
        </Link>
      </div>

      <nav className="flex-1 px-3 py-4 space-y-5 overflow-y-auto">
        {sections.map((sectionName) => {
          const items = SITE_CONFIG.navigation.filter(
            (item) => item.section === sectionName
          )
          if (!items.length) return null

          return (
            <div key={sectionName} className="space-y-1">
              <div className="px-2 pb-1 text-xs font-medium text-muted-foreground/75 uppercase tracking-wider">
                {sectionName}
              </div>
              {items.map((item) => {
                const Icon = item.icon
                const isActive = item.exact
                  ? pathname === item.href
                  : pathname === item.href || pathname.startsWith(`${item.href}/`)

                return (
                  <Link
                    key={item.href}
                    href={item.href}
                    className={cn(
                      "flex items-center gap-2.5 px-2.5 py-1.5 rounded-sm text-[14px] transition-colors font-sans",
                      isActive
                        ? "bg-sidebar-accent text-foreground font-medium border-l-2 border-foreground"
                        : "text-muted-foreground hover:bg-sidebar-accent/50 hover:text-foreground"
                    )}
                  >
                    <Icon className={cn("size-3.5 shrink-0", isActive ? "text-foreground" : "text-muted-foreground")} />
                    <span className="truncate">{item.title}</span>
                  </Link>
                )
              })}
            </div>
          )
        })}
      </nav>

      <div className="p-3 border-t border-sidebar-border bg-sidebar/50">
        <div className="flex items-center justify-between px-2 py-1.5 text-xs">
          <div className="min-w-0 pr-2">
            <p className="text-[13px] font-medium text-foreground truncate">
              {user?.name || "Operator Session"}
            </p>
            <p className="text-xs text-muted-foreground truncate font-mono">
              {user?.email || "session@ledger"}
            </p>
          </div>
          {user && (
            <button
              type="button"
              onClick={() => logout()}
              title="Sign Out"
              aria-label="Sign Out"
              className="p-1 rounded-sm text-muted-foreground hover:text-foreground hover:bg-muted transition-colors"
            >
              <LogOut className="size-3.5" />
            </button>
          )}
        </div>
      </div>
    </aside>
  )
}
