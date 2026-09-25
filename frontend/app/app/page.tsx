import Link from "next/link"
import { ArrowLeftRight, Plus, BarChart3, IndianRupee, TrendingUp, Percent } from "lucide-react"
import { Section } from "@/components/ui/section"
import { StatusBadge } from "@/components/ui/status-badge"
import { Button } from "@/components/ui/button"
import { ROUTES } from "@/constants/routes"

export default function DashboardPage() {
  return (
    <div className="space-y-6 select-none font-sans">
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 pb-3 border-b border-border/70">
        <div>
          <h1 className="text-[26px] font-semibold tracking-tight text-foreground font-sans leading-tight">
            Overview
          </h1>
          <p className="text-[14px] text-muted-foreground font-sans mt-0.5">
            Real-time balance derivation, recent transaction activity, and ledger reconciliation.
          </p>
        </div>

        <div className="flex items-center gap-2">
          <Link href={ROUTES.TRANSFERS}>
            <Button size="sm" className="gap-1.5 text-[13px]">
              <ArrowLeftRight className="size-3.5" />
              <span>Transfer Funds</span>
            </Button>
          </Link>
          <Link href={ROUTES.ACCOUNTS}>
            <Button variant="outline" size="sm" className="gap-1.5 text-[13px]">
              <Plus className="size-3.5" />
              <span>New Account</span>
            </Button>
          </Link>
        </div>
      </div>

      <div className="grid grid-cols-2 md:grid-cols-4 border border-border rounded-sm bg-card divide-x divide-y md:divide-y-0 divide-border shadow-2xs">
        <div className="p-4 space-y-1">
          <span className="text-[13px] text-muted-foreground font-sans block">
            Available Balance
          </span>
          <div className="font-mono text-xl font-bold tracking-tight text-foreground">
            <span className="text-muted-foreground font-sans font-normal text-sm mr-1">₹</span>—
          </div>
          <span className="text-[12px] text-muted-foreground/75 font-sans block">Aggregated checking total</span>
        </div>

        <div className="p-4 space-y-1">
          <span className="text-[13px] text-muted-foreground font-sans block">
            Checking Accounts
          </span>
          <div className="font-mono text-xl font-bold tracking-tight text-foreground">
            —
          </div>
          <span className="text-[12px] text-muted-foreground/75 font-sans block">Active retail instruments</span>
        </div>

        <div className="p-4 space-y-1">
          <span className="text-[13px] text-muted-foreground font-sans block">
            Recent Activity
          </span>
          <div className="font-mono text-xl font-bold tracking-tight text-foreground">
            —
          </div>
          <span className="text-[12px] text-muted-foreground/75 font-sans block">Settled today</span>
        </div>

        <div className="p-4 space-y-1">
          <span className="text-[13px] text-muted-foreground font-sans block">
            Reconciliation
          </span>
          <div className="pt-0.5">
            <StatusBadge status="CONSISTENT" />
          </div>
          <span className="text-[12px] text-muted-foreground/75 font-sans block">Snapshot equals ledger sum</span>
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
        <div className="lg:col-span-8 space-y-6">
          <Section
            title="Recent Transactions"
            actions={
              <Link href={ROUTES.LEDGER}>
                <Button variant="ghost" size="xs" className="text-[13px]">
                  View Full Ledger
                </Button>
              </Link>
            }
          >
            <div className="border border-border/70 rounded-sm overflow-hidden">
              <div className="grid grid-cols-12 gap-3 px-4 py-2.5 bg-muted/30 text-[13px] text-muted-foreground font-medium border-b border-border/70">
                <span className="col-span-2">Time</span>
                <span className="col-span-2">Type</span>
                <span className="col-span-4">Account / Flow</span>
                <span className="col-span-2 text-right">Amount</span>
                <span className="col-span-2 text-right">Status</span>
              </div>

              <div className="p-10 text-center space-y-3 bg-card">
                <p className="text-[13.5px] text-muted-foreground font-sans">
                  No transactions recorded for the active session.
                </p>
                <p className="text-[13px] text-muted-foreground/75 font-sans max-w-sm mx-auto">
                  Execute a transfer or deposit to generate double-entry records in the journal.
                </p>
                <div className="pt-1">
                  <Link href={ROUTES.TRANSFERS}>
                    <Button variant="outline" size="sm" className="text-[13px]">
                      Execute Transfer
                    </Button>
                  </Link>
                </div>
              </div>
            </div>
          </Section>

          <Section
            title="Financial Velocity & Volume"
            actions={
              <div className="flex items-center gap-1 border border-border/70 rounded-sm p-0.5 bg-muted/20">
                <button
                  type="button"
                  className="flex items-center gap-1 px-2.5 py-1 text-xs font-sans rounded-xs bg-foreground text-background font-medium"
                  title="Transaction Volume"
                >
                  <BarChart3 className="size-3" />
                  <span>Volume</span>
                </button>
                <button
                  type="button"
                  className="flex items-center gap-1 px-2.5 py-1 text-xs font-sans rounded-xs text-muted-foreground hover:text-foreground"
                  title="Transaction Value"
                >
                  <IndianRupee className="size-3" />
                  <span>Value</span>
                </button>
                <button
                  type="button"
                  className="flex items-center gap-1 px-2.5 py-1 text-xs font-sans rounded-xs text-muted-foreground hover:text-foreground"
                  title="Balance Trend"
                >
                  <TrendingUp className="size-3" />
                  <span>Balance</span>
                </button>
                <button
                  type="button"
                  className="flex items-center gap-1 px-2.5 py-1 text-xs font-sans rounded-xs text-muted-foreground hover:text-foreground"
                  title="Success Rate"
                >
                  <Percent className="size-3" />
                  <span>Rate</span>
                </button>
              </div>
            }
          >
            <div className="h-48 border border-dashed border-border/70 rounded-sm flex flex-col items-center justify-center p-6 bg-muted/10 text-center space-y-1">
              <p className="text-[14px] font-medium text-foreground font-sans">
                Activity Visualization
              </p>
              <p className="text-[13px] text-muted-foreground font-sans max-w-sm">
                Single-card metric switcher toggles between Volume, Value, Balance Trend, and Success Rate once account history is populated.
              </p>
            </div>
          </Section>
        </div>

        <div className="lg:col-span-4 space-y-6">
          <Section
            title="Checking Accounts"
            actions={
              <Link href={ROUTES.ACCOUNTS}>
                <Button variant="ghost" size="xs" className="text-[13px]">
                  Manage All
                </Button>
              </Link>
            }
          >
            <div className="border border-border/70 rounded-sm p-6 text-center space-y-2 bg-card">
              <p className="text-[13.5px] text-muted-foreground font-sans">
                No accounts discovered under current session.
              </p>
              <p className="text-[13px] text-muted-foreground/75 font-sans">
                Create a retail checking account to begin initiating ledger movements.
              </p>
              <div className="pt-2">
                <Link href={ROUTES.ACCOUNTS}>
                  <Button variant="outline" size="sm" className="text-[13px]">
                    Open Accounts Manager
                  </Button>
                </Link>
              </div>
            </div>
          </Section>

          <Section title="Double-Entry Invariants">
            <div className="space-y-1 text-[13.5px]">
              <div className="flex justify-between py-2 border-b border-border/40">
                <span className="text-muted-foreground">Pessimistic Locking</span>
                <span className="font-medium text-foreground">Row-level, ordered by ID</span>
              </div>
              <div className="flex justify-between py-2 border-b border-border/40">
                <span className="text-muted-foreground">Idempotency</span>
                <span className="font-medium text-foreground">Redis fast-path + PostgreSQL</span>
              </div>
              <div className="flex justify-between py-2 border-b border-border/40">
                <span className="text-muted-foreground">Debit / Credit Match</span>
                <span className="font-mono text-foreground">∑(Debits) = ∑(Credits)</span>
              </div>
              <div className="flex justify-between py-2">
                <span className="text-muted-foreground">Negative Balance</span>
                <span className="font-medium text-foreground">Disallowed (&ge; 0)</span>
              </div>
            </div>
          </Section>
        </div>
      </div>
    </div>
  )
}
