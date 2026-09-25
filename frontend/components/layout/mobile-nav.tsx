"use client"

import * as React from "react"
import Link from "next/link"
import { usePathname } from "next/navigation"
import { X, LogOut } from "lucide-react"
import { SITE_CONFIG } from "@/config/site"
import { useAuth } from "@/hooks/auth/use-auth"
import { cn } from "@/lib/utils"

export function MobileNav({
  isOpen,
  onClose,
}: {
  isOpen: boolean
  onClose: () => void
}) {
  const pathname = usePathname()
  const { user, logout } = useAuth()

  React.useEffect(() => {
    onClose()
  }, [pathname, onClose])

  if (!isOpen) return null

  const sections = ["Operations", "Audit & Reporting", "System"] as const

  return (
    <div className="fixed inset-0 z-50 md:hidden flex">
      <div
        className="fixed inset-0 bg-black/50 backdrop-blur-2xs transition-opacity"
        onClick={onClose}
        aria-hidden="true"
      />
      <div className="relative flex flex-col w-64 max-w-[80vw] h-full bg-sidebar border-r border-sidebar-border z-10 animate-in slide-in-from-left duration-150">
        <div className="h-13 flex items-center justify-between px-4 border-b border-sidebar-border bg-sidebar">
          <div className="flex items-center gap-2">
            <div className="size-6 rounded-sm bg-foreground text-background flex items-center justify-center font-bold text-xs tracking-tight">
              FL
            </div>
            <span className="text-xs font-semibold tracking-tight text-foreground">
              Financial Ledger
            </span>
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close navigation menu"
            className="p-1 rounded-sm text-muted-foreground hover:text-foreground"
          >
            <X className="size-4" />
          </button>
        </div>

        <nav className="flex-1 px-3 py-4 space-y-4 overflow-y-auto">
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
                      onClick={onClose}
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
          <div className="flex items-center justify-between text-xs px-2 py-1">
            <div className="min-w-0 pr-2">
              <p className="text-xs font-medium text-foreground truncate">
                {user?.name || "Operator Session"}
              </p>
              <p className="text-[11px] text-muted-foreground font-mono truncate">
                {user?.email || "session@ledger"}
              </p>
            </div>
            {user && (
              <button
                type="button"
                onClick={() => {
                  onClose()
                  logout()
                }}
                title="Sign Out"
                aria-label="Sign Out"
                className="p-1 rounded-sm text-muted-foreground hover:text-foreground hover:bg-muted transition-colors"
              >
                <LogOut className="size-3.5" />
              </button>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}
