import * as React from "react"
import { AuthBoundary } from "@/components/auth/auth-boundary"

export default function AppLayout({ children }: { children: React.ReactNode }) {
  return <AuthBoundary>{children}</AuthBoundary>
}
