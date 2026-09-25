import { Landmark, Plus, Filter } from "lucide-react"
import { Section } from "@/components/ui/section"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"

export default function AccountsPage() {
  return (
    <div className="space-y-6 select-none font-sans">
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 pb-3 border-b border-border/70">
        <div>
          <h1 className="text-lg font-semibold tracking-tight text-foreground font-sans">
            Accounts
          </h1>
          <p className="text-xs text-muted-foreground font-sans">
            Manage checking accounts, review lifecycle states, and create new financial instruments.
          </p>
        </div>

        <div className="flex items-center gap-2">
          <Button size="sm" className="gap-1.5">
            <Plus className="size-3.5" />
            <span>Create Account</span>
          </Button>
        </div>
      </div>

      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-2 flex-1 max-w-sm">
          <Input
            placeholder="Search accounts by ID or number..."
            className="h-8 text-xs font-mono"
            monospace
          />
        </div>
        <div className="flex items-center gap-2">
          <Button variant="outline" size="sm" className="gap-1.5">
            <Filter className="size-3.5" />
            <span>Filter Status</span>
          </Button>
        </div>
      </div>

      <Section
        title="Checking Accounts"
        description="All user-owned checking accounts provisioned in the ledger."
      >
        <div className="border border-border/70 rounded-sm overflow-hidden">
          <div className="grid grid-cols-12 gap-3 px-4 py-2 bg-muted/30 text-xs text-muted-foreground font-medium border-b border-border/70">
            <span className="col-span-4">Account ID</span>
            <span className="col-span-2">Type</span>
            <span className="col-span-2">Currency</span>
            <span className="col-span-2">Status</span>
            <span className="col-span-2 text-right">Available Balance</span>
          </div>

          <div className="p-12 text-center space-y-3 bg-card">
            <Landmark className="size-8 text-muted-foreground/40 mx-auto" />
            <p className="text-xs font-medium text-foreground">
              No accounts created yet
            </p>
            <p className="text-xs text-muted-foreground max-w-sm mx-auto">
              Create a checking account to initiate transfers, deposit funds, and view statements.
            </p>
          </div>
        </div>
      </Section>
    </div>
  )
}
