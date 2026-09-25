import { Scale, RefreshCw } from "lucide-react"
import { Section } from "@/components/ui/section"
import { StatusBadge } from "@/components/ui/status-badge"
import { Button } from "@/components/ui/button"
import { DataRow } from "@/components/ui/data-row"

export default function ReconciliationPage() {
  return (
    <div className="space-y-6 select-none font-sans">
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 pb-3 border-b border-border/70">
        <div>
          <h1 className="text-[26px] font-semibold tracking-tight text-foreground font-sans leading-tight">
            Reconciliation
          </h1>
          <p className="text-[14px] text-muted-foreground font-sans mt-0.5">
            Verify balance integrity between cached account snapshots and the sum of immutable ledger entries.
          </p>
        </div>

        <div className="flex items-center gap-2">
          <Button size="sm" className="gap-1.5 text-[13px]">
            <RefreshCw className="size-3.5" />
            <span>Run Reconciliation</span>
          </Button>
        </div>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        <div className="p-4 border border-border rounded-sm bg-card space-y-1 shadow-2xs">
          <span className="text-[13px] text-muted-foreground font-sans block">
            Cached Snapshot Total
          </span>
          <div className="font-mono text-xl font-bold tracking-tight text-foreground">
            <span className="text-muted-foreground font-sans font-normal text-sm mr-1">₹</span>—
          </div>
          <span className="text-[12px] text-muted-foreground/75 font-sans block">Aggregate balance from account rows</span>
        </div>

        <div className="p-4 border border-border rounded-sm bg-card space-y-1 shadow-2xs">
          <span className="text-[13px] text-muted-foreground font-sans block">
            Ledger-Derived Total
          </span>
          <div className="font-mono text-xl font-bold tracking-tight text-foreground">
            <span className="text-muted-foreground font-sans font-normal text-sm mr-1">₹</span>—
          </div>
          <span className="text-[12px] text-muted-foreground/75 font-sans block">Authoritative sum of all journal entries</span>
        </div>

        <div className="p-4 border border-border rounded-sm bg-card space-y-1 shadow-2xs">
          <span className="text-[13px] text-muted-foreground font-sans block">
            Net Discrepancy
          </span>
          <div className="font-mono text-xl font-bold tracking-tight text-foreground flex items-center justify-between">
            <div>
              <span className="text-muted-foreground font-sans font-normal text-sm mr-1">₹</span>0.00
            </div>
            <StatusBadge status="CONSISTENT" />
          </div>
          <span className="text-[12px] text-muted-foreground/75 font-sans block">Zero discrepancy baseline</span>
        </div>
      </div>

      <Section
        title="Account Reconciliation"
        description="Individual account balance comparison against ledger-derived totals."
      >
        <div className="border border-border/70 rounded-sm overflow-hidden">
          <div className="grid grid-cols-12 gap-3 px-4 py-2.5 bg-muted/30 text-[13px] text-muted-foreground font-medium border-b border-border/70">
            <span className="col-span-4">Account ID</span>
            <span className="col-span-2 text-right">Snapshot Balance</span>
            <span className="col-span-2 text-right">Ledger Derived</span>
            <span className="col-span-2 text-right">Difference</span>
            <span className="col-span-2 text-right">Status</span>
          </div>

          <div className="p-12 text-center space-y-3 bg-card">
            <Scale className="size-8 text-muted-foreground/40 mx-auto" />
            <p className="text-[15px] font-medium text-foreground">
              No reconciliation records retrieved
            </p>
            <p className="text-[13.5px] text-muted-foreground max-w-sm mx-auto">
              Run reconciliation to verify balance integrity across all checking accounts against the ledger source of truth.
            </p>
          </div>
        </div>
      </Section>

      <Section title="Reconciliation Protocol">
        <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
          <div className="space-y-1">
            <DataRow label="Reconciliation Invariant" value="Snapshot Balance == Sum(Journal Entries)" />
            <DataRow label="Reconciliation Endpoint" value="GET /api/v1/reconciliation" />
            <DataRow label="Audit Granularity" value="Per-account and aggregate totals" />
          </div>
          <div className="space-y-1">
            <DataRow label="Discrepancy Handling" value="Immediate flag and audit log generation" />
            <DataRow label="Tolerance" value="0.00 (Zero floating-point rounding tolerance)" />
            <DataRow label="Locking Behavior" value="Row-level pessimistic read locks" />
          </div>
        </div>
      </Section>
    </div>
  )
}
