"use client"

import * as React from "react"
import { Sidebar } from "@/components/layout/sidebar"
import { Header } from "@/components/layout/header"
import { MobileNav } from "@/components/layout/mobile-nav"

export function AppShell({ children }: { children: React.ReactNode }) {
  const [mobileNavOpen, setMobileNavOpen] = React.useState(false)

  return (
    <div className="flex h-screen w-full bg-background overflow-hidden text-foreground">
      <div className="hidden md:flex md:w-56 lg:w-60 shrink-0 h-full">
        <Sidebar className="w-full" />
      </div>

      <MobileNav isOpen={mobileNavOpen} onClose={() => setMobileNavOpen(false)} />

      <div className="flex-1 flex flex-col min-w-0 h-full overflow-hidden">
        <Header onOpenMobileNav={() => setMobileNavOpen(true)} />
        <main className="flex-1 overflow-y-auto p-4 sm:p-6 lg:p-8 bg-background">
          <div className="max-w-7xl mx-auto w-full space-y-6">{children}</div>
        </main>
      </div>
    </div>
  )
}
