"use client"

import * as React from "react"
import { useRouter } from "next/navigation"
import { useAuth } from "@/hooks/auth/use-auth"
import { AppShell } from "@/components/layout/app-shell"
import { ROUTES } from "@/constants/routes"

export function AuthBoundary({ children }: { children: React.ReactNode }) {
  const router = useRouter()
  const { user, isLoading, error } = useAuth()

  React.useEffect(() => {
    if (!isLoading && (!user || error)) {
      router.replace(ROUTES.LOGIN)
    }
  }, [isLoading, user, error, router])

  if (isLoading || !user || error) {
    return (
      <div className="flex h-screen w-full items-center justify-center bg-background select-none">
        <div className="flex flex-col items-center gap-2.5">
          <div className="size-8 rounded-sm bg-foreground text-background flex items-center justify-center font-bold text-xs tracking-tight animate-pulse">
            FL
          </div>
          <span className="text-xs text-muted-foreground font-sans">
            {isLoading ? "Verifying operator session..." : "Redirecting to sign in..."}
          </span>
        </div>
      </div>
    )
  }

  return <AppShell>{children}</AppShell>
}
