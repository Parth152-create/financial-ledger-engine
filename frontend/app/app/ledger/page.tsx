import { BookOpenText, Filter, Download, Calendar } from "lucide-react"
import { Section } from "@/components/ui/section"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"

export default function LedgerPage() {
  return (
    <div className="space-y-6 select-none font-sans">
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 pb-3 border-b border-border/70">
        <div>
          <h1 className="text-lg font-semibold tracking-tight text-foreground font-sans">
            Ledger Journal
          </h1>
          <p className="text-xs text-muted-foreground font-sans">
            Immutable double-entry journal entries and chronological transaction records.
          </p>
        </div>

        <div className="flex items-center gap-2">
          <Button variant="outline" size="sm" className="gap-1.5">
            <Download className="size-3.5" />
            <span>Export Statement</span>
          </Button>
        </div>
      </div>

      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-2 flex-1 max-w-md">
          <Input
            placeholder="Search by transaction ID or account reference..."
            className="h-8 text-xs font-mono"
            monospace
          />
        </div>
        <div className="flex items-center gap-2">
          <Button variant="outline" size="sm" className="gap-1.5">
            <Calendar className="size-3.5" />
            <span>Date Range</span>
          </Button>
          <Button variant="outline" size="sm" className="gap-1.5">
            <Filter className="size-3.5" />
            <span>Filter Type</span>
          </Button>
        </div>
      </div>

      <Section
        title="Journal Entries"
        description="Immutable record entries derived from committed database transactions."
      >
        <div className="border border-border/70 rounded-sm overflow-hidden">
          <div className="grid grid-cols-12 gap-3 px-4 py-2 bg-muted/30 text-xs text-muted-foreground font-medium border-b border-border/70">
            <span className="col-span-2">Date & Time</span>
            <span className="col-span-2">Type</span>
            <span className="col-span-4">Account Flow</span>
            <span className="col-span-1 text-center">Direction</span>
            <span className="col-span-2 text-right">Amount</span>
            <span className="col-span-1 text-right">Status</span>
          </div>

          <div className="p-12 text-center space-y-3 bg-card">
            <BookOpenText className="size-8 text-muted-foreground/40 mx-auto" />
            <p className="text-xs font-medium text-foreground">
              No journal entries recorded
            </p>
            <p className="text-xs text-muted-foreground max-w-sm mx-auto">
              Transactions posted to accounts will append immutable debit and credit entries to this ledger.
            </p>
          </div>
        </div>
      </Section>
    </div>
  )
}
