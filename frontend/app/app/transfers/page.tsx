import { ArrowLeftRight, KeyRound } from "lucide-react"
import { Section } from "@/components/ui/section"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { DataRow } from "@/components/ui/data-row"

export default function TransfersPage() {
  return (
    <div className="space-y-6 select-none font-sans">
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 pb-3 border-b border-border/70">
        <div>
          <h1 className="text-[26px] font-semibold tracking-tight text-foreground font-sans leading-tight">
            Transfers & Transactions
          </h1>
          <p className="text-[14px] text-muted-foreground font-sans mt-0.5">
            Execute atomic double-entry fund transfers, deposits, and withdrawals.
          </p>
        </div>

        <div className="flex items-center gap-1 border border-border/70 rounded-sm p-0.5 bg-muted/20">
          <button
            type="button"
            className="px-3 py-1 text-[13px] font-sans rounded-xs bg-foreground text-background font-medium"
          >
            Transfer
          </button>
          <button
            type="button"
            className="px-3 py-1 text-[13px] font-sans rounded-xs text-muted-foreground hover:text-foreground"
          >
            Deposit
          </button>
          <button
            type="button"
            className="px-3 py-1 text-[13px] font-sans rounded-xs text-muted-foreground hover:text-foreground"
          >
            Withdrawal
          </button>
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
        <div className="lg:col-span-7">
          <Section
            title="Transfer Details"
            description="Atomic transfer between two checking accounts."
          >
            <div className="space-y-4">
              <div className="space-y-1.5">
                <label className="text-[13.5px] font-medium text-foreground flex items-center justify-between">
                  <span>Source Account</span>
                  <span className="text-xs text-muted-foreground">Debited</span>
                </label>
                <Input placeholder="Enter or select source account ID..." monospace />
              </div>

              <div className="space-y-1.5">
                <label className="text-[13.5px] font-medium text-foreground flex items-center justify-between">
                  <span>Destination Account</span>
                  <span className="text-xs text-muted-foreground">Credited</span>
                </label>
                <Input placeholder="Enter or select destination account ID..." monospace />
              </div>

              <div className="grid grid-cols-3 gap-3">
                <div className="col-span-2 space-y-1.5">
                  <label className="text-[13.5px] font-medium text-foreground">
                    Amount
                  </label>
                  <Input placeholder="0.00" monospace />
                </div>
                <div className="col-span-1 space-y-1.5">
                  <label className="text-[13.5px] font-medium text-foreground">
                    Currency
                  </label>
                  <Input value="INR" disabled monospace className="bg-muted/40 text-center" />
                </div>
              </div>

              <div className="space-y-1.5">
                <label className="text-[13.5px] font-medium text-foreground">
                  Description / Note
                </label>
                <Input placeholder="Optional reference note..." />
              </div>

              <div className="space-y-1.5 pt-1">
                <label className="text-[13.5px] font-medium text-muted-foreground flex items-center gap-1.5">
                  <KeyRound className="size-3 text-muted-foreground" />
                  <span>Idempotency Key</span>
                </label>
                <Input
                  value="Generated per user-initiated transfer"
                  readOnly
                  className="bg-muted/30 text-muted-foreground text-[13px] font-sans"
                />
              </div>

              <div className="pt-2">
                <Button className="w-full h-8.5 gap-2 justify-center font-medium text-[13.5px]">
                  <ArrowLeftRight className="size-3.5" />
                  <span>Execute Transfer</span>
                </Button>
              </div>
            </div>
          </Section>
        </div>

        <div className="lg:col-span-5 space-y-6">
          <Section title="Transaction Invariants">
            <div className="space-y-1">
              <DataRow label="Transaction Atomicity" value="All-or-nothing (ACID)" />
              <DataRow label="Lock Sequencing" value="Deterministic by account ID" />
              <DataRow label="Idempotency Cache" value="Redis fast-path + database constraint" />
              <DataRow label="Currency Validation" value="Strict currency match" />
              <DataRow label="Balance Check" value="Source balance must cover amount" />
            </div>
          </Section>

          <Section title="Transaction Integrity" variant="subtle">
            <p className="text-[13px] text-muted-foreground leading-relaxed font-sans">
              Transfers acquire pessimistic row-level locks in deterministic account order to prevent concurrency deadlocks. Every transaction records an equal debit and credit journal entry.
            </p>
          </Section>
        </div>
      </div>
    </div>
  )
}
