"use client"

import * as React from "react"
import Link from "next/link"
import { Landmark, Plus, Filter, AlertCircle, RotateCw, Search } from "lucide-react"
import { Section } from "@/components/ui/section"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { StatusBadge } from "@/components/ui/status-badge"
import { AmountDisplay } from "@/components/ui/amount-display"
import { CreateAccountDialog } from "@/components/accounts/create-account-dialog"
import { useAccounts } from "@/hooks/api/use-accounts"
import { ROUTES } from "@/constants/routes"
import type { AccountStatus } from "@/types/account"

export default function AccountsPage() {
  const [searchTerm, setSearchTerm] = React.useState("")
  const [statusFilter, setStatusFilter] = React.useState<"ALL" | AccountStatus>("ALL")
  const [isFilterOpen, setIsFilterOpen] = React.useState(false)
  const [isCreateOpen, setIsCreateOpen] = React.useState(false)

  const { data: accounts, isLoading, isError, error, refetch } = useAccounts()

  const filteredAccounts = React.useMemo(() => {
    if (!accounts) return []
    return accounts.filter((account) => {
      const matchesSearch =
        !searchTerm.trim() ||
        account.accountNumber.toLowerCase().includes(searchTerm.toLowerCase().trim()) ||
        account.accountId.toLowerCase().includes(searchTerm.toLowerCase().trim())

      const matchesStatus =
        statusFilter === "ALL" || account.status === statusFilter

      return matchesSearch && matchesStatus
    })
  }, [accounts, searchTerm, statusFilter])

  return (
    <div className="space-y-6 select-none font-sans">
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 pb-3 border-b border-border/70">
        <div>
          <h1 className="text-[26px] font-semibold tracking-tight text-foreground font-sans leading-tight">
            Accounts
          </h1>
          <p className="text-[14px] text-muted-foreground font-sans mt-0.5">
            Manage checking accounts, review lifecycle states, and create new financial instruments.
          </p>
        </div>

        <div className="flex items-center gap-2">
          <Button
            size="sm"
            onClick={() => setIsCreateOpen(true)}
            className="gap-1.5 text-[13px]"
          >
            <Plus className="size-3.5" />
            <span>Create Account</span>
          </Button>
        </div>
      </div>

      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-2 flex-1 max-w-sm">
          <Input
            value={searchTerm}
            onChange={(e) => setSearchTerm(e.target.value)}
            placeholder="Search accounts by ID or number..."
            className="h-8.5 text-[13.5px] font-mono"
            monospace
          />
        </div>
        <div className="relative flex items-center gap-2">
          <Button
            variant={statusFilter === "ALL" ? "outline" : "secondary"}
            size="sm"
            onClick={() => setIsFilterOpen((prev) => !prev)}
            className="gap-1.5 text-[13px]"
          >
            <Filter className="size-3.5" />
            <span>
              {statusFilter === "ALL" ? "Filter Status" : `Status: ${statusFilter}`}
            </span>
          </Button>

          {isFilterOpen && (
            <div className="absolute right-0 top-9.5 z-20 w-36 rounded-sm border border-border bg-card p-1 shadow-md text-[13px] font-sans space-y-0.5">
              {(["ALL", "ACTIVE", "FROZEN", "CLOSED"] as const).map((status) => (
                <button
                  key={status}
                  type="button"
                  onClick={() => {
                    setStatusFilter(status)
                    setIsFilterOpen(false)
                  }}
                  className={`w-full text-left px-2.5 py-1.5 rounded-xs transition-colors text-[13px] ${
                    statusFilter === status
                      ? "bg-primary text-primary-foreground font-medium"
                      : "text-foreground hover:bg-muted"
                  }`}
                >
                  {status === "ALL" ? "All Statuses" : status}
                </button>
              ))}
            </div>
          )}
        </div>
      </div>

      <Section
        title="Checking Accounts"
        description="All user-owned checking accounts provisioned in the ledger."
      >
        <div className="border border-border/70 rounded-sm overflow-hidden">
          <div className="grid grid-cols-12 gap-3 px-4 py-2.5 bg-muted/30 text-[13px] text-muted-foreground font-medium border-b border-border/70">
            <span className="col-span-4">Account ID & Number</span>
            <span className="col-span-2">Type</span>
            <span className="col-span-2">Currency</span>
            <span className="col-span-2">Status</span>
            <span className="col-span-2 text-right">Available Balance</span>
          </div>

          {isLoading ? (
            <div className="divide-y divide-border/50 bg-card">
              {[1, 2, 3].map((idx) => (
                <div
                  key={idx}
                  className="grid grid-cols-12 gap-3 px-4 py-3 items-center animate-pulse"
                >
                  <div className="col-span-4 space-y-1.5">
                    <div className="h-3 w-32 bg-muted rounded-xs" />
                    <div className="h-2.5 w-48 bg-muted/60 rounded-xs" />
                  </div>
                  <div className="col-span-2">
                    <div className="h-3 w-16 bg-muted rounded-xs" />
                  </div>
                  <div className="col-span-2">
                    <div className="h-4 w-10 bg-muted rounded-xs" />
                  </div>
                  <div className="col-span-2">
                    <div className="h-4 w-14 bg-muted rounded-xs" />
                  </div>
                  <div className="col-span-2 flex justify-end">
                    <div className="h-3 w-20 bg-muted rounded-xs" />
                  </div>
                </div>
              ))}
            </div>
          ) : isError ? (
            <div className="p-8 text-center space-y-3 bg-card">
              <AlertCircle className="size-8 text-destructive mx-auto" />
              <p className="text-[15px] font-semibold text-foreground">
                Failed to load accounts
              </p>
              <p className="text-[13.5px] text-muted-foreground max-w-sm mx-auto">
                {error?.message || "An error occurred while fetching accounts from the ledger."}
              </p>
              <Button
                variant="outline"
                size="sm"
                onClick={() => refetch()}
                className="gap-1.5"
              >
                <RotateCw className="size-3.5" />
                <span>Retry</span>
              </Button>
            </div>
          ) : !accounts || accounts.length === 0 ? (
            <div className="p-12 text-center space-y-3 bg-card">
              <Landmark className="size-8 text-muted-foreground/40 mx-auto" />
              <p className="text-[15px] font-medium text-foreground">
                No accounts created yet
              </p>
              <p className="text-[13.5px] text-muted-foreground max-w-sm mx-auto">
                Create a checking account to initiate transfers, deposit funds, and view statements.
              </p>
              <Button
                size="sm"
                onClick={() => setIsCreateOpen(true)}
                className="gap-1.5 mt-2"
              >
                <Plus className="size-3.5" />
                <span>Create First Account</span>
              </Button>
            </div>
          ) : filteredAccounts.length === 0 ? (
            <div className="p-10 text-center space-y-2 bg-card">
              <Search className="size-6 text-muted-foreground/40 mx-auto" />
              <p className="text-[15px] font-medium text-foreground">
                No matching accounts found
              </p>
              <p className="text-[13.5px] text-muted-foreground max-w-xs mx-auto">
                No accounts match your current filter or search criteria.
              </p>
              <Button
                variant="outline"
                size="xs"
                onClick={() => {
                  setSearchTerm("")
                  setStatusFilter("ALL")
                }}
                className="mt-1"
              >
                Clear Filters
              </Button>
            </div>
          ) : (
            <div className="divide-y divide-border/60 bg-card">
              {filteredAccounts.map((account) => (
                <Link
                  key={account.accountId}
                  href={ROUTES.ACCOUNT_DETAILS(account.accountId)}
                  className="grid grid-cols-12 gap-3 px-4 py-3 items-center hover:bg-muted/40 transition-colors text-foreground group"
                >
                  <div className="col-span-4 min-w-0">
                    <span className="font-mono text-[15px] font-semibold block tracking-tight group-hover:text-primary transition-colors truncate">
                      {account.accountNumber}
                    </span>
                    <span
                      className="font-mono text-[12.5px] text-muted-foreground truncate block mt-0.5"
                      title={account.accountId}
                    >
                      {account.accountId}
                    </span>
                  </div>

                  <div className="col-span-2 text-[14px] text-muted-foreground font-sans truncate">
                    {account.accountType === "USER_CHECKING"
                      ? "Checking"
                      : account.accountType}
                  </div>

                  <div className="col-span-2">
                    <span className="text-xs font-mono uppercase px-2 py-0.5 rounded-sm border border-border/80 bg-muted/40 text-muted-foreground">
                      {account.currency}
                    </span>
                  </div>

                  <div className="col-span-2">
                    <StatusBadge status={account.status} />
                  </div>

                  <div className="col-span-2 text-right">
                    <AmountDisplay
                      amount={account.balance}
                      currency={account.currency}
                      align="right"
                    />
                  </div>
                </Link>
              ))}
            </div>
          )}
        </div>
      </Section>

      <CreateAccountDialog
        open={isCreateOpen}
        onOpenChange={setIsCreateOpen}
      />
    </div>
  )
}
