"use client"

import * as React from "react"
import {
  ShieldCheck,
  RotateCw,
  Filter,
  FilterX,
  ChevronLeft,
  ChevronRight,
  AlertCircle,
} from "lucide-react"
import { Button } from "@/components/ui/button"
import { AuditEventTable } from "@/components/audit/audit-event-table"
import { AuditDetailDialog } from "@/components/audit/audit-detail-dialog"
import { useAuditEvents } from "@/hooks/api/use-audit-events"
import type { AuditEvent, AuditEventType } from "@/types/audit"

type CategoryFilter = "ALL" | "FINANCIAL" | "ACCOUNT" | "SECURITY"

const EVENT_TYPE_OPTIONS: { label: string; value: AuditEventType; category: CategoryFilter }[] = [
  { label: "Transfer Completed", value: "TRANSFER_COMPLETED", category: "FINANCIAL" },
  { label: "Deposit Completed", value: "DEPOSIT_COMPLETED", category: "FINANCIAL" },
  { label: "Withdrawal Completed", value: "WITHDRAWAL_COMPLETED", category: "FINANCIAL" },
  { label: "Account Created", value: "ACCOUNT_CREATED", category: "ACCOUNT" },
  { label: "Account Frozen", value: "ACCOUNT_FROZEN", category: "ACCOUNT" },
  { label: "Account Unfrozen", value: "ACCOUNT_UNFROZEN", category: "ACCOUNT" },
  { label: "Account Closed", value: "ACCOUNT_CLOSED", category: "ACCOUNT" },
  { label: "User Registered", value: "AUTH_SIGNUP", category: "SECURITY" },
  { label: "Login Succeeded", value: "AUTH_LOGIN", category: "SECURITY" },
  { label: "User Logged Out", value: "AUTH_LOGOUT", category: "SECURITY" },
  { label: "Password Changed", value: "PASSWORD_CHANGED", category: "SECURITY" },
]

export default function AuditPage() {
  const [category, setCategory] = React.useState<CategoryFilter>("ALL")
  const [eventType, setEventType] = React.useState<string>("")
  const [entityType, setEntityType] = React.useState<string>("")
  const [page, setPage] = React.useState(0)
  const pageSize = 20

  const [selectedEvent, setSelectedEvent] = React.useState<AuditEvent | null>(null)
  const [isDetailOpen, setIsDetailOpen] = React.useState(false)

  // Query params
  const queryParams = React.useMemo(() => {
    return {
      eventType: eventType || undefined,
      entityType: entityType || undefined,
      page,
      size: pageSize,
    }
  }, [eventType, entityType, page, pageSize])

  const { data, isLoading, isError, error, refetch, isFetching } = useAuditEvents(queryParams)

  // Handle category tab change
  const handleCategoryChange = (newCategory: CategoryFilter) => {
    setCategory(newCategory)
    setEventType("")
    setPage(0)
  }

  // Filter options based on selected category
  const filteredEventOptions = React.useMemo(() => {
    if (category === "ALL") return EVENT_TYPE_OPTIONS
    return EVENT_TYPE_OPTIONS.filter((opt) => opt.category === category)
  }, [category])

  const handleEventTypeChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    setEventType(e.target.value)
    setPage(0)
  }

  const handleEntityTypeChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    setEntityType(e.target.value)
    setPage(0)
  }

  const handleClearFilters = () => {
    setCategory("ALL")
    setEventType("")
    setEntityType("")
    setPage(0)
  }

  const hasActiveFilters = category !== "ALL" || Boolean(eventType) || Boolean(entityType)

  const handleOpenDetail = (event: AuditEvent) => {
    setSelectedEvent(event)
    setIsDetailOpen(true)
  }

  const totalPages = data?.totalPages || 0
  const totalElements = data?.totalElements || 0
  const events = data?.content || []

  return (
    <div className="space-y-6 select-none font-sans">
      {/* Page Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 pb-3 border-b border-border/60">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight text-foreground font-sans">
            Audit Trail
          </h1>
          <p className="text-sm text-muted-foreground mt-0.5">
            Operational accountability and immutable event history for security and financial actions.
          </p>
        </div>

        <div className="flex items-center gap-2">
          <Button
            size="sm"
            variant="outline"
            onClick={() => refetch()}
            disabled={isFetching}
            className="gap-1.5 text-xs font-medium h-8 px-3"
          >
            <RotateCw className={`size-3.5 ${isFetching ? "animate-spin" : ""}`} />
            <span>{isFetching ? "Refreshing..." : "Refresh"}</span>
          </Button>
        </div>
      </div>

      {/* Architecture Invariant Banner */}
      <div className="flex items-center gap-2 p-2.5 rounded-sm border border-border/60 bg-muted/20 text-xs text-muted-foreground">
        <ShieldCheck className="size-3.5 text-primary shrink-0" />
        <span>
          Audit events are immutable operational records. Financial events commit atomically with the authoritative ledger.
        </span>
      </div>

      {/* Filter and Control Bar */}
      <div className="flex flex-col lg:flex-row items-stretch lg:items-center justify-between gap-3 p-3 rounded-sm border border-border/70 bg-card">
        {/* Category Tabs */}
        <div className="flex items-center gap-1 overflow-x-auto pb-1 lg:pb-0">
          {(["ALL", "FINANCIAL", "ACCOUNT", "SECURITY"] as const).map((cat) => (
            <button
              key={cat}
              type="button"
              onClick={() => handleCategoryChange(cat)}
              className={`px-2.5 py-1 text-xs font-medium rounded-xs transition-colors whitespace-nowrap ${
                category === cat
                  ? "bg-primary text-primary-foreground shadow-xs"
                  : "text-muted-foreground hover:text-foreground hover:bg-muted/60"
              }`}
            >
              {cat === "ALL"
                ? "All Events"
                : cat === "FINANCIAL"
                ? "Financial Actions"
                : cat === "ACCOUNT"
                ? "Account Lifecycle"
                : "Security & Auth"}
            </button>
          ))}
        </div>

        {/* Detailed Filters */}
        <div className="flex flex-wrap items-center gap-2">
          <div className="flex items-center gap-1.5">
            <Filter className="size-3 text-muted-foreground shrink-0" />
            <select
              value={eventType}
              onChange={handleEventTypeChange}
              className="text-xs h-7 px-2 border border-border rounded-xs bg-background text-foreground focus:outline-hidden focus:ring-1 focus:ring-ring"
            >
              <option value="">All Event Types</option>
              {filteredEventOptions.map((opt) => (
                <option key={opt.value} value={opt.value}>
                  {opt.label}
                </option>
              ))}
            </select>
          </div>

          <select
            value={entityType}
            onChange={handleEntityTypeChange}
            className="text-xs h-7 px-2 border border-border rounded-xs bg-background text-foreground focus:outline-hidden focus:ring-1 focus:ring-ring"
          >
            <option value="">All Entities</option>
            <option value="TRANSACTION">Transaction</option>
            <option value="ACCOUNT">Account</option>
            <option value="USER">User</option>
            <option value="SYSTEM">System</option>
          </select>

          {hasActiveFilters && (
            <Button
              type="button"
              variant="ghost"
              size="xs"
              onClick={handleClearFilters}
              className="text-xs text-muted-foreground hover:text-foreground gap-1 h-7 px-2"
            >
              <FilterX className="size-3" />
              <span>Clear</span>
            </Button>
          )}
        </div>
      </div>

      {/* Content State */}
      {isError ? (
        <div className="p-8 text-center space-y-3 bg-card border border-destructive/20 rounded-sm">
          <AlertCircle className="size-8 text-destructive mx-auto" />
          <p className="text-base font-semibold text-foreground">Failed to Load Audit Events</p>
          <p className="text-xs text-muted-foreground max-w-md mx-auto">
            {error?.message || "An unexpected error occurred while fetching audit trail records."}
          </p>
          <div className="pt-2">
            <Button
              variant="outline"
              size="sm"
              onClick={() => refetch()}
              disabled={isFetching}
              className="gap-1.5"
            >
              <RotateCw className={`size-3.5 ${isFetching ? "animate-spin" : ""}`} />
              <span>Retry</span>
            </Button>
          </div>
        </div>
      ) : (
        <div className="space-y-4">
          <AuditEventTable
            events={events}
            isLoading={isLoading}
            onSelectEvent={handleOpenDetail}
          />

          {/* Pagination Controls */}
          {totalPages > 1 && (
            <div className="flex items-center justify-between px-1 py-2 text-xs text-muted-foreground">
              <span>
                Showing {page * pageSize + 1}–{Math.min((page + 1) * pageSize, totalElements)} of{" "}
                {totalElements} events
              </span>

              <div className="flex items-center gap-2">
                <Button
                  type="button"
                  variant="outline"
                  size="xs"
                  onClick={() => setPage((prev) => Math.max(prev - 1, 0))}
                  disabled={page === 0 || isLoading}
                  className="gap-1 h-7 px-2"
                >
                  <ChevronLeft className="size-3.5" />
                  <span>Previous</span>
                </Button>

                <span className="font-mono">
                  {page + 1} / {totalPages}
                </span>

                <Button
                  type="button"
                  variant="outline"
                  size="xs"
                  onClick={() => setPage((prev) => Math.min(prev + 1, totalPages - 1))}
                  disabled={page >= totalPages - 1 || isLoading}
                  className="gap-1 h-7 px-2"
                >
                  <span>Next</span>
                  <ChevronRight className="size-3.5" />
                </Button>
              </div>
            </div>
          )}
        </div>
      )}

      {/* Detail Inspection Dialog */}
      <AuditDetailDialog
        open={isDetailOpen}
        onOpenChange={setIsDetailOpen}
        event={selectedEvent}
      />
    </div>
  )
}
