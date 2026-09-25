"use client"

import * as React from "react"
import { useAuth } from "@/hooks/auth/use-auth"
import { AppShell } from "@/components/layout/app-shell"
import { Lock, ArrowRight } from "lucide-react"
import { Button } from "@/components/ui/button"

export function AuthBoundary({ children }: { children: React.ReactNode }) {
  const { user, isLoading, error, loginWithGoogle } = useAuth()

  if (isLoading) {
    return (
      <div className="flex h-screen w-full items-center justify-center bg-background select-none">
        <div className="flex flex-col items-center gap-2.5">
          <div className="size-8 rounded-sm bg-foreground text-background flex items-center justify-center font-bold text-xs tracking-tight animate-pulse">
            FL
          </div>
          <span className="text-xs text-muted-foreground font-sans">
            Loading financial workspace...
          </span>
        </div>
      </div>
    )
  }

  if (error || !user) {
    return (
      <div className="flex min-h-screen w-full items-center justify-center bg-background p-4 select-none">
        <div className="w-full max-w-sm rounded-sm border border-border bg-card p-6 text-card-foreground shadow-2xs">
          <div className="flex items-center gap-3 pb-3 mb-4 border-b border-border/70">
            <div className="size-8 rounded-sm bg-muted/60 flex items-center justify-center text-muted-foreground">
              <Lock className="size-4" />
            </div>
            <div>
              <h2 className="text-sm font-semibold text-foreground font-sans">
                Sign in to Ledger Engine
              </h2>
              <p className="text-xs text-muted-foreground font-sans">
                Authentication required to continue
              </p>
            </div>
          </div>

          <p className="text-xs text-muted-foreground mb-5 leading-relaxed font-sans">
            Access to financial accounts and transaction journals requires an authenticated session.
          </p>

          <Button
            type="button"
            onClick={loginWithGoogle}
            className="w-full h-8.5 justify-center gap-2 text-xs font-sans"
          >
            <span>Continue with Google</span>
            <ArrowRight className="size-3.5" />
          </Button>

          <div className="mt-5 pt-3 border-t border-border/50 text-center">
            <span className="text-[11px] text-muted-foreground font-sans">
              Google OAuth · Session-based authentication
            </span>
          </div>
        </div>
      </div>
    )
  }

  return <AppShell>{children}</AppShell>
}
