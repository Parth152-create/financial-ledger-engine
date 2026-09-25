import Link from "next/link"
import { ArrowRight, Database, ShieldCheck, Layers, GitCommit } from "lucide-react"
import { ROUTES } from "@/constants/routes"
import { ThemeToggle } from "@/components/layout/theme-toggle"
import { Button } from "@/components/ui/button"

export default function HomePage() {
  return (
    <div className="min-h-screen flex flex-col bg-background text-foreground select-none font-sans">
      <header className="h-13 border-b border-border px-4 sm:px-8 flex items-center justify-between bg-background sticky top-0 z-20">
        <div className="flex items-center gap-2.5">
          <div className="size-6.5 rounded-sm bg-foreground text-background flex items-center justify-center font-bold text-xs tracking-tight">
            FL
          </div>
          <span className="text-xs font-semibold tracking-tight">
            Financial Ledger Engine
          </span>
        </div>

        <div className="flex items-center gap-3">
          <ThemeToggle />
          <Link href={ROUTES.LOGIN}>
            <Button variant="ghost" size="sm">
              Sign In
            </Button>
          </Link>
          <Link href={ROUTES.DASHBOARD}>
            <Button size="sm" className="gap-1.5">
              <span>Open Workspace</span>
              <ArrowRight className="size-3.5" />
            </Button>
          </Link>
        </div>
      </header>

      <main className="flex-1 flex flex-col items-center justify-center px-4 py-16 sm:py-24">
        <div className="max-w-3xl w-full space-y-10">
          <div className="space-y-4">
            <div className="inline-flex items-center gap-2 px-2.5 py-1 rounded-sm border border-border bg-muted/40 text-xs text-muted-foreground font-medium">
              <span className="size-1.5 rounded-full bg-emerald-500" />
              <span>Double-Entry Financial Platform</span>
            </div>

            <h1 className="text-3xl sm:text-4xl font-semibold tracking-tight text-foreground leading-tight">
              A reliable double-entry ledger platform for processing, tracking, and reconciling financial transactions.
            </h1>

            <p className="text-sm text-muted-foreground leading-relaxed max-w-2xl font-sans">
              Designed for transaction integrity with row-level pessimistic locking, deterministic account lock ordering, dual-layer Redis idempotency, and PostgreSQL as the single source of truth.
            </p>

            <div className="flex items-center gap-3 pt-2">
              <Link href={ROUTES.DASHBOARD}>
                <Button size="default" className="gap-2">
                  <span>Access Platform</span>
                  <ArrowRight className="size-3.5" />
                </Button>
              </Link>
              <Link href={ROUTES.LOGIN}>
                <Button variant="outline" size="default">
                  Sign In
                </Button>
              </Link>
            </div>
          </div>

          <div className="border border-border rounded-sm bg-card divide-y divide-border shadow-2xs">
            <div className="px-4 py-3 bg-muted/20 flex items-center justify-between text-xs text-muted-foreground font-medium">
              <span>Core Architectural Capabilities</span>
              <span>Audit-Ready</span>
            </div>

            <div className="grid grid-cols-1 md:grid-cols-2 divide-y md:divide-y-0 md:divide-x divide-border">
              <div className="p-5 space-y-2">
                <div className="flex items-center gap-2 text-xs font-semibold text-foreground">
                  <Database className="size-4 text-muted-foreground" />
                  <span>PostgreSQL Source of Truth</span>
                </div>
                <p className="text-xs text-muted-foreground leading-relaxed">
                  ACID-compliant transactions, immutable ledger records, and check constraints to prevent balance inconsistencies at the database level.
                </p>
              </div>

              <div className="p-5 space-y-2">
                <div className="flex items-center gap-2 text-xs font-semibold text-foreground">
                  <Layers className="size-4 text-muted-foreground" />
                  <span>Dual-Layer Idempotency</span>
                </div>
                <p className="text-xs text-muted-foreground leading-relaxed">
                  Redis fast-path caching combined with authoritative PostgreSQL unique constraints to guarantee zero duplicate executions across retries.
                </p>
              </div>
            </div>

            <div className="grid grid-cols-1 md:grid-cols-2 divide-y md:divide-y-0 md:divide-x divide-border">
              <div className="p-5 space-y-2">
                <div className="flex items-center gap-2 text-xs font-semibold text-foreground">
                  <ShieldCheck className="size-4 text-muted-foreground" />
                  <span>Deterministic Lock Sequencing</span>
                </div>
                <p className="text-xs text-muted-foreground leading-relaxed">
                  Pessimistic row locks acquired strictly in sorted account order to mathematically eliminate database deadlocks under concurrent load.
                </p>
              </div>

              <div className="p-5 space-y-2">
                <div className="flex items-center gap-2 text-xs font-semibold text-foreground">
                  <GitCommit className="size-4 text-muted-foreground" />
                  <span>Continuous Reconciliation</span>
                </div>
                <p className="text-xs text-muted-foreground leading-relaxed">
                  Automated background reconciliation verifying that cached account snapshot balances match the sum of immutable ledger entries.
                </p>
              </div>
            </div>
          </div>
        </div>
      </main>

      <footer className="h-11 border-t border-border px-4 sm:px-8 flex items-center justify-between text-xs text-muted-foreground bg-muted/10">
        <div>Spring Boot Backend · Port 8085</div>
        <div>Strict Financial Separation</div>
      </footer>
    </div>
  )
}
