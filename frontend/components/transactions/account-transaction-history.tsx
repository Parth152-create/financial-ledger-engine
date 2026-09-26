"use client"

import * as React from "react"
import {
  RotateCw,
  AlertCircle,
  FilterX,
  ChevronLeft,
  ChevronRight,
  Inbox,
} from "lucide-react"
import { Button } from "@/components/ui/button"
import { LedgerRow } from "@/components/ledger/ledger-row"
import { TransactionDetailDialog } from "@/components/transactions/transaction-detail-dialog"
import { useAccountTransactions } from "@/hooks/api/use-transactions"
import { formatDate } from "@/lib/formatters/date"
import type {
  TransactionHistoryItem,
  TransactionHistoryParams,
  TransactionType,
  TransactionStatus,
} from "@/types/transaction"

interface AccountTransactionHistoryProps {
  accountId: string
  currency?: string
}

export function AccountTransactionHistory({
  accountId,
}: AccountTransactionHistoryProps) {
  const [page, setPage] = React.useState(0)
  const pageSize = 20

  const [typeFilter, setTypeFilter] = React.useState<string>("")
  const [statusFilter, setStatusFilter] = React.useState<string>("")
  const [fromDate, setFromDate] = React.useState<string>("")
  const [toDate, setToDate] = React.useState<string>("")

  const [selectedTx, setSelectedTx] = React.useState<TransactionHistoryItem | null>(null)

  const queryParams: TransactionHistoryParams = React.useMemo(() => {
    const params: TransactionHistoryParams = {
      page,
      size: pageSize,
    }
    if (typeFilter) params.transactionType = typeFilter as TransactionType
    if (statusFilter) params.status = statusFilter as TransactionStatus
    if (fromDate) {
      params.from = `${fromDate}T00:00:00Z`
    }
    if (toDate) {
      params.to = `${toDate}T23:59:59Z`
    }
    return params
  }, [page, pageSize, typeFilter, statusFilter, fromDate, toDate])

  const {
    data,
    isLoading,
    isError,
    error,
    refetch,
    isFetching,
  } = useAccountTransactions(accountId, queryParams)

  const hasActiveFilters = Boolean(typeFilter || statusFilter || fromDate || toDate)

  const handleResetFilters = () => {
    setTypeFilter("")
    setStatusFilter("")
    setFromDate("")
    setToDate("")
    setPage(0)
  }

  const handleTypeChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    setTypeFilter(e.target.value)
    setPage(0)
  }

  const handleStatusChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    setStatusFilter(e.target.value)
    setPage(0)
  }

  const handleFromDateChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    setFromDate(e.target.value)
    setPage(0)
  }

  const handleToDateChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    setToDate(e.target.value)
    setPage(0)
  }

  return (
    <div className="space-y-3 font-sans select-none">
      <div className="flex flex-wrap items-center justify-between gap-2.5 p-2.5 rounded-sm border border-border/70 bg-card text-xs">
        <div className="flex flex-wrap items-center gap-2">
          <div className="flex items-center gap-1.5">
            <span className="text-muted-foreground text-[11px] font-medium">Type:</span>
            <select
              value={typeFilter}
              onChange={handleTypeChange}
              className="h-7 rounded-xs border border-border bg-background px-2 text-xs text-foreground focus:outline-hidden focus:ring-1 focus:ring-ring"
            >
              <option value="">All Types</option>
              <option value="TRANSFER">Transfer</option>
              <option value="DEPOSIT">Deposit</option>
              <option value="WITHDRAWAL">Withdrawal</option>
            </select>
          </div>

          <div className="flex items-center gap-1.5">
            <span className="text-muted-foreground text-[11px] font-medium">Status:</span>
            <select
              value={statusFilter}
              onChange={handleStatusChange}
              className="h-7 rounded-xs border border-border bg-background px-2 text-xs text-foreground focus:outline-hidden focus:ring-1 focus:ring-ring"
            >
              <option value="">All Statuses</option>
              <option value="COMPLETED">Completed</option>
              <option value="PENDING">Pending</option>
              <option value="FAILED">Failed</option>
            </select>
          </div>

          <div className="flex items-center gap-1.5">
            <span className="text-muted-foreground text-[11px] font-medium">From:</span>
            <input
              type="date"
              value={fromDate}
              onChange={handleFromDateChange}
              className="h-7 rounded-xs border border-border bg-background px-2 text-xs text-foreground focus:outline-hidden focus:ring-1 focus:ring-ring font-mono"
            />
          </div>

          <div className="flex items-center gap-1.5">
            <span className="text-muted-foreground text-[11px] font-medium">To:</span>
            <input
              type="date"
              value={toDate}
              onChange={handleToDateChange}
              className="h-7 rounded-xs border border-border bg-background px-2 text-xs text-foreground focus:outline-hidden focus:ring-1 focus:ring-ring font-mono"
            />
          </div>

          {hasActiveFilters && (
            <Button
              variant="ghost"
              size="xs"
              onClick={handleResetFilters}
              className="gap-1 text-muted-foreground hover:text-foreground text-[11px] h-7"
            >
              <FilterX className="size-3" />
              <span>Reset</span>
            </Button>
          )}
        </div>

        <div className="flex items-center gap-1 text-muted-foreground">
          {isFetching && !isLoading && (
            <RotateCw className="size-3 animate-spin text-muted-foreground mr-1" />
          )}
          <span className="text-[11px]">
            {data ? `${data.totalElements} transaction${data.totalElements === 1 ? "" : "s"}` : ""}
          </span>
        </div>
      </div>

      <div className="border border-border/70 rounded-sm overflow-hidden bg-card">
        <div className="grid grid-cols-12 gap-3 px-3.5 py-2 bg-muted/40 text-[11px] font-medium text-muted-foreground uppercase tracking-wider border-b border-border/70">
          <span className="col-span-2">Date & Time</span>
          <span className="col-span-2">Type</span>
          <span className="col-span-3">Account Flow</span>
          <span className="col-span-1 text-center">Flow</span>
          <span className="col-span-2 text-right">Amount</span>
          <span className="col-span-2 text-right">Status</span>
        </div>

        {isLoading ? (
          <div className="divide-y divide-border/50">
            {Array.from({ length: 5 }).map((_, i) => (
              <div
                key={i}
                className="grid grid-cols-12 items-center gap-3 px-3.5 py-3 animate-pulse"
              >
                <div className="col-span-2 space-y-1">
                  <div className="h-3 w-20 bg-muted rounded-xs" />
                  <div className="h-2 w-14 bg-muted/60 rounded-xs" />
                </div>
                <div className="col-span-2">
                  <div className="h-4 w-16 bg-muted rounded-xs" />
                </div>
                <div className="col-span-3">
                  <div className="h-3 w-28 bg-muted rounded-xs" />
                </div>
                <div className="col-span-1 flex justify-center">
                  <div className="h-4 w-7 bg-muted rounded-xs" />
                </div>
                <div className="col-span-2 flex justify-end">
                  <div className="h-3 w-16 bg-muted rounded-xs" />
                </div>
                <div className="col-span-2 flex justify-end">
                  <div className="h-4 w-18 bg-muted rounded-xs" />
                </div>
              </div>
            ))}
          </div>
        ) : isError ? (
          <div className="p-8 text-center space-y-2.5">
            <AlertCircle className="size-6 text-destructive mx-auto" />
            <p className="text-sm font-semibold text-foreground">
              Failed to load transaction history
            </p>
            <p className="text-xs text-muted-foreground max-w-sm mx-auto">
              {error?.message || "An unexpected error occurred while fetching transactions."}
            </p>
            <Button
              variant="outline"
              size="xs"
              onClick={() => refetch()}
              className="gap-1 mt-1 text-xs"
            >
              <RotateCw className="size-3" />
              <span>Retry</span>
            </Button>
          </div>
        ) : !data || data.content.length === 0 ? (
          <div className="p-10 text-center space-y-2">
            <Inbox className="size-7 text-muted-foreground/40 mx-auto" />
            <p className="text-sm font-medium text-foreground">
              {hasActiveFilters ? "No transactions match your filters" : "No transactions recorded"}
            </p>
            <p className="text-xs text-muted-foreground max-w-xs mx-auto">
              {hasActiveFilters
                ? "Try adjusting or clearing your filters to see more results."
                : "Transactions will appear here once deposits, withdrawals, or transfers are processed."}
            </p>
            {hasActiveFilters && (
              <Button
                variant="outline"
                size="xs"
                onClick={handleResetFilters}
                className="gap-1 mt-2 text-xs"
              >
                <FilterX className="size-3" />
                <span>Reset Filters</span>
              </Button>
            )}
          </div>
        ) : (
          <div className="divide-y divide-border/50">
            {data.content.map((tx) => (
              <LedgerRow
                key={tx.transactionId}
                id={tx.transactionId}
                timestamp={formatDate(tx.createdAt)}
                type={tx.transactionType}
                sourceAccount={tx.sourceAccountId ? tx.sourceAccountId.slice(0, 8) : undefined}
                destinationAccount={tx.destinationAccountId ? tx.destinationAccountId.slice(0, 8) : undefined}
                direction={tx.direction === "CREDIT" ? "credit" : "debit"}
                amount={tx.amount}
                currency={tx.currency}
                status={tx.status}
                onClick={() => setSelectedTx(tx)}
              />
            ))}
          </div>
        )}

        {data && data.totalPages > 1 && (
          <div className="flex items-center justify-between px-3.5 py-2.5 bg-muted/20 border-t border-border/70 text-xs text-muted-foreground">
            <div>
              <span>
                Page <span className="font-medium text-foreground">{data.page + 1}</span> of{" "}
                <span className="font-medium text-foreground">{data.totalPages}</span>
              </span>
            </div>

            <div className="flex items-center gap-1">
              <Button
                variant="outline"
                size="xs"
                onClick={() => setPage((p) => Math.max(0, p - 1))}
                disabled={data.first || page === 0 || isLoading}
                className="gap-1 h-7 px-2"
              >
                <ChevronLeft className="size-3" />
                <span>Previous</span>
              </Button>
              <Button
                variant="outline"
                size="xs"
                onClick={() => setPage((p) => p + 1)}
                disabled={data.last || page >= data.totalPages - 1 || isLoading}
                className="gap-1 h-7 px-2"
              >
                <span>Next</span>
                <ChevronRight className="size-3" />
              </Button>
            </div>
          </div>
        )}
      </div>

      <TransactionDetailDialog
        open={Boolean(selectedTx)}
        onOpenChange={(open) => {
          if (!open) setSelectedTx(null)
        }}
        transaction={selectedTx}
      />
    </div>
  )
}
