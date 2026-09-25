import { BarChart3, TrendingUp, IndianRupee, Activity, Percent } from "lucide-react"
import { Section } from "@/components/ui/section"
import { Button } from "@/components/ui/button"
import { DataRow } from "@/components/ui/data-row"

export default function AnalyticsPage() {
  return (
    <div className="space-y-6 select-none font-sans">
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 pb-3 border-b border-border/70">
        <div>
          <h1 className="text-[26px] font-semibold tracking-tight text-foreground font-sans leading-tight">
            Analytics
          </h1>
          <p className="text-[14px] text-muted-foreground font-sans mt-0.5">
            Transaction throughput, monetary volume, and settlement success metrics.
          </p>
        </div>

        <div className="flex items-center gap-1.5">
          <Button variant="outline" size="sm" className="text-[13px]">
            Past 24 Hours
          </Button>
          <Button variant="outline" size="sm" className="text-[13px]">
            Past 7 Days
          </Button>
          <Button variant="outline" size="sm" className="text-[13px]">
            Past 30 Days
          </Button>
        </div>
      </div>

      <Section
        title="Transaction Metrics"
        description="Unified analytics switcher toggles between key financial metrics without shifting layout."
        actions={
          <div className="flex items-center gap-1 border border-border/70 rounded-sm p-0.5 bg-muted/20">
            <button
              type="button"
              className="flex items-center gap-1.5 px-2.5 py-1 text-[13px] font-sans rounded-xs bg-foreground text-background font-medium"
              title="Transaction Volume"
            >
              <BarChart3 className="size-3" />
              <span>Volume</span>
            </button>
            <button
              type="button"
              className="flex items-center gap-1.5 px-2.5 py-1 text-[13px] font-sans rounded-xs text-muted-foreground hover:text-foreground"
              title="Transaction Value"
            >
              <IndianRupee className="size-3" />
              <span>Value</span>
            </button>
            <button
              type="button"
              className="flex items-center gap-1.5 px-2.5 py-1 text-[13px] font-sans rounded-xs text-muted-foreground hover:text-foreground"
              title="Balance Trend"
            >
              <TrendingUp className="size-3" />
              <span>Balance</span>
            </button>
            <button
              type="button"
              className="flex items-center gap-1.5 px-2.5 py-1 text-[13px] font-sans rounded-xs text-muted-foreground hover:text-foreground"
              title="Success Rate"
            >
              <Percent className="size-3" />
              <span>Success Rate</span>
            </button>
          </div>
        }
      >
        <div className="h-72 border border-dashed border-border/70 rounded-sm flex flex-col items-center justify-center p-6 bg-muted/10 text-center space-y-2">
          <Activity className="size-8 text-muted-foreground/40 mb-1" />
          <p className="text-[14px] font-medium text-foreground font-sans">
            Metrics Visualization Engine
          </p>
          <p className="text-[13px] text-muted-foreground font-sans max-w-md">
            Charts will visualize transaction activity, value flow, and settlement success once transactions are recorded in the ledger.
          </p>
        </div>
      </Section>

      <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
        <Section title="Throughput & Concurrency">
          <div className="space-y-1">
            <DataRow label="Locking Strategy" value="Pessimistic row-level" />
            <DataRow label="Lock Order" value="Deterministic by account ID" />
            <DataRow label="Deadlock Prevention" value="Mathematical guarantee" />
          </div>
        </Section>

        <Section title="Idempotency Cache">
          <div className="space-y-1">
            <DataRow label="Fast-Path Cache" value="Redis" />
            <DataRow label="Authoritative Deduplication" value="PostgreSQL unique index" />
            <DataRow label="Retention Window" value="24 Hours (86,400s)" />
          </div>
        </Section>

        <Section title="Financial Precision">
          <div className="space-y-1">
            <DataRow label="Number Precision" value="PostgreSQL NUMERIC / BigDecimal" />
            <DataRow label="Floating Point Math" value="Zero client-side calculations" />
            <DataRow label="Balance Calculation" value="Authoritative backend derivation" />
          </div>
        </Section>
      </div>
    </div>
  )
}
