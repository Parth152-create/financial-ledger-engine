"use client"

import * as React from "react"
import {
  BookOpenText,
  Building2,
  ReceiptText,
  History,
  RotateCw,
  AlertCircle,
  Inbox,
  FilterX,
  ChevronLeft,
  ChevronRight,
} from "lucide-react"
import { Button } from "@/components/ui/button"
import { AmountDisplay } from "@/components/ui/amount-display"
import { StatusBadge } from "@/components/ui/status-badge"
import { TransactionTypeBadge } from "@/components/ledger/transaction-type-badge"
import { TransactionDetailDialog } from "@/components/transactions/transaction-detail-dialog"
import { useAccounts } from "@/hooks/api/use-accounts"
import { useAccountStatement } from "@/hooks/api/use-statements"
import { useAccountTransactions } from "@/hooks/api/use-transactions"
import { formatDate } from "@/lib/formatters/date"
import type { StatementEntry, AccountStatementParams } from "@/types/statement"
import type { TransactionHistoryItem, TransactionHistoryParams, TransactionType, TransactionStatus } from "@/types/transaction"
import { cn } from "@/lib/utils"

export default function LedgerPage() {
  const { data: accounts, isLoading: isLoadingAccounts, isError: isErrorAccounts, error: accountsError, refetch: refetchAccounts } = useAccounts()

  const [selectedAccountId, setSelectedAccountId] = React.useState<string>("")
  const [viewMode, setViewMode] = React.useState<"journal" | "transactions">("journal")
  const [page, setPage] = React.useState(0)
  const pageSize = 20

  const [typeFilter, setTypeFilter] = React.useState<string>("")
  const [statusFilter, setStatusFilter] = React.useState<string>("")
  const [fromDate, setFromDate] = React.useState<string>("")
  const [toDate, setToDate] = React.useState<string>("")
  const [searchQuery, setSearchQuery] = React.useState<string>("")

  const [selectedItem, setSelectedItem] = React.useState<StatementEntry | TransactionHistoryItem | null>(null)

  const activeAccountId = selectedAccountId || (accounts && accounts.length > 0 ? accounts[0].accountId : "")
  const currentAccount = accounts?.find((a) => a.accountId === activeAccountId)

  const statementParams: AccountStatementParams = React.useMemo(() => {
    const params: AccountStatementParams = { page, size: pageSize }
    if (typeFilter) params.transactionType = typeFilter as TransactionType
    if (statusFilter) params.status = statusFilter as TransactionStatus
    if (fromDate) params.from = `${fromDate}T00:00:00Z`
    if (toDate) params.to = `${toDate}T23:59:59Z`
    return params
  }, [page, pageSize, typeFilter, statusFilter, fromDate, toDate])

  const transactionParams: TransactionHistoryParams = React.useMemo(() => {
    const params: TransactionHistoryParams = { page, size: pageSize }
    if (typeFilter) params.transactionType = typeFilter as TransactionType
    if (statusFilter) params.status = statusFilter as TransactionStatus
    if (fromDate) params.from = `${fromDate}T00:00:00Z`
    if (toDate) params.to = `${toDate}T23:59:59Z`
    return params
  }, [page, pageSize, typeFilter, statusFilter, fromDate, toDate])

  const {
    data: statementData,
    isLoading: isLoadingStatement,
    isError: isErrorStatement,
    error: statementError,
    refetch: refetchStatement,
    isFetching: isFetchingStatement,
  } = useAccountStatement(activeAccountId, statementParams, { enabled: Boolean(activeAccountId) && viewMode === "journal" })

  const {
    data: txData,
    isLoading: isLoadingTx,
    isError: isErrorTx,
    error: txError,
    refetch: refetchTx,
    isFetching: isFetchingTx,
  } = useAccountTransactions(activeAccountId, transactionParams, { enabled: Boolean(activeAccountId) && viewMode === "transactions" })

  const hasActiveFilters = Boolean(typeFilter || statusFilter || fromDate || toDate || searchQuery)

  const handleResetFilters = () => {
    setTypeFilter("")
    setStatusFilter("")
    setFromDate("")
    setToDate("")
    setSearchQuery("")
    setPage(0)
  }

  const handleAccountChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    setSelectedAccountId(e.target.value)
    setPage(0)
  }

  const handleViewModeChange = (mode: "journal" | "transactions") => {
    setViewMode(mode)
    setPage(0)
  }

  const statementEntries = statementData?.entries ?? []
  const filteredStatementEntries = searchQuery.trim()
    ? statementEntries.filter(
        (entry) =>
          entry.transactionId.toLowerCase().includes(searchQuery.trim().toLowerCase()) ||
          (entry.description && entry.description.toLowerCase().includes(searchQuery.trim().toLowerCase())) ||
          entry.transactionType.toLowerCase().includes(searchQuery.trim().toLowerCase())
      )
    : statementEntries

  const txEntries = txData?.content ?? []
  const filteredTxEntries = searchQuery.trim()
    ? txEntries.filter(
        (tx) =>
          tx.transactionId.toLowerCase().includes(searchQuery.trim().toLowerCase()) ||
          (tx.description && tx.description.toLowerCase().includes(searchQuery.trim().toLowerCase())) ||
          tx.transactionType.toLowerCase().includes(searchQuery.trim().toLowerCase()) ||
          (tx.sourceAccountId && tx.sourceAccountId.toLowerCase().includes(searchQuery.trim().toLowerCase())) ||
          (tx.destinationAccountId && tx.destinationAccountId.toLowerCase().includes(searchQuery.trim().toLowerCase()))
      )
    : txEntries

  const isLoadingData = viewMode === "journal" ? isLoadingStatement : isLoadingTx
  const isErrorData = viewMode === "journal" ? isErrorStatement : isErrorTx
  const dataError = viewMode === "journal" ? statementError : txError
  const isFetchingData = viewMode === "journal" ? isFetchingStatement : isFetchingTx

  return (
    <div className="space-y-6 select-none font-sans">
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 pb-3 border-b border-border/70">
        <div>
          <h1 className="text-[26px] font-semibold tracking-tight text-foreground font-sans leading-tight">
            Ledger Journal
          </h1>
          <p className="text-[14px] text-muted-foreground font-sans mt-0.5">
            Immutable double-entry journal entries and chronological transaction records.
          </p>
        </div>

        {accounts && accounts.length > 0 && (
          <div className="flex items-center gap-2">
            <span className="text-xs font-medium text-muted-foreground whitespace-nowrap">
              Account Instrument:
            </span>
            <select
              value={activeAccountId}
              onChange={handleAccountChange}
              className="h-8.5 rounded-xs border border-border bg-card px-2.5 text-xs text-foreground font-mono focus:outline-hidden focus:ring-1 focus:ring-ring"
            >
              {accounts.map((acc) => (
                <option key={acc.accountId} value={acc.accountId}>
                  {acc.accountNumber} ({acc.currency})
                </option>
              ))}
            </select>
          </div>
        )}
      </div>

      {isLoadingAccounts ? (
        <div className="rounded-sm border border-border bg-card p-6 animate-pulse space-y-3">
          <div className="h-4 w-48 bg-muted rounded-xs" />
          <div className="h-3 w-72 bg-muted/60 rounded-xs" />
        </div>
      ) : isErrorAccounts ? (
        <div className="p-8 text-center space-y-2 bg-card border border-destructive/20 rounded-sm">
          <AlertCircle className="size-6 text-destructive mx-auto" />
          <p className="text-sm font-semibold text-foreground">Failed to load accounts</p>
          <p className="text-xs text-muted-foreground">{accountsError?.message || "Error fetching account instruments."}</p>
          <Button variant="outline" size="xs" onClick={() => refetchAccounts()} className="mt-2">
            Retry
          </Button>
        </div>
      ) : !accounts || accounts.length === 0 ? (
        <div className="p-12 text-center space-y-3 bg-card border border-border/70 rounded-sm">
          <Building2 className="size-8 text-muted-foreground/40 mx-auto" />
          <p className="text-base font-semibold text-foreground">No Checking Accounts Found</p>
          <p className="text-xs text-muted-foreground max-w-sm mx-auto">
            You must have at least one active checking account to inspect ledger entries.
          </p>
        </div>
      ) : (
        <div className="space-y-4">
          {/* Account Overview Bar */}
          {currentAccount && (
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 p-3.5 rounded-sm border border-border/70 bg-card text-xs">
              <div className="space-y-1">
                <span className="text-[11px] font-medium text-muted-foreground uppercase tracking-wider">
                  Account Reference
                </span>
                <div className="font-mono text-sm font-semibold text-foreground">
                  {currentAccount.accountNumber}
                </div>
              </div>

              <div className="space-y-1">
                <span className="text-[11px] font-medium text-muted-foreground uppercase tracking-wider">
                  Current Balance
                </span>
                <div>
                  <AmountDisplay
                    amount={currentAccount.balance}
                    currency={currentAccount.currency}
                    size="sm"
                  />
                </div>
              </div>

              <div className="space-y-1">
                <span className="text-[11px] font-medium text-muted-foreground uppercase tracking-wider">
                  Status
                </span>
                <div>
                  <StatusBadge status={currentAccount.status} />
                </div>
              </div>

              <div className="space-y-1">
                <span className="text-[11px] font-medium text-muted-foreground uppercase tracking-wider">
                  Ledger Mode
                </span>
                <div className="text-xs text-muted-foreground font-mono">
                  DOUBLE_ENTRY_V1
                </div>
              </div>
            </div>
          )}

          {/* Statement Running Totals (if journal mode and available) */}
          {viewMode === "journal" && statementData && (
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 p-3 rounded-sm border border-border/60 bg-muted/20 text-xs">
              <div>
                <span className="text-[11px] text-muted-foreground block">Opening Balance</span>
                <AmountDisplay amount={statementData.openingBalance} currency={currentAccount?.currency} size="xs" />
              </div>
              <div>
                <span className="text-[11px] text-muted-foreground block">Total Credits</span>
                <AmountDisplay amount={statementData.totalCredits} currency={currentAccount?.currency} direction="credit" size="xs" showSign />
              </div>
              <div>
                <span className="text-[11px] text-muted-foreground block">Total Debits</span>
                <AmountDisplay amount={statementData.totalDebits} currency={currentAccount?.currency} direction="debit" size="xs" showSign />
              </div>
              <div>
                <span className="text-[11px] text-muted-foreground block">Closing Balance</span>
                <AmountDisplay amount={statementData.closingBalance} currency={currentAccount?.currency} size="xs" />
              </div>
            </div>
          )}

          {/* View Mode & Filter Controls */}
          <div className="space-y-2.5">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <div className="flex items-center gap-1.5 p-0.5 rounded-sm bg-muted/60 border border-border">
                <button
                  type="button"
                  onClick={() => handleViewModeChange("journal")}
                  className={cn(
                    "flex items-center gap-1.5 px-3 py-1 text-xs font-medium rounded-xs transition-colors",
                    viewMode === "journal"
                      ? "bg-card text-foreground shadow-xs font-semibold"
                      : "text-muted-foreground hover:text-foreground"
                  )}
                >
                  <ReceiptText className="size-3.5" />
                  <span>Journal & Balance</span>
                </button>
                <button
                  type="button"
                  onClick={() => handleViewModeChange("transactions")}
                  className={cn(
                    "flex items-center gap-1.5 px-3 py-1 text-xs font-medium rounded-xs transition-colors",
                    viewMode === "transactions"
                      ? "bg-card text-foreground shadow-xs font-semibold"
                      : "text-muted-foreground hover:text-foreground"
                  )}
                >
                  <History className="size-3.5" />
                  <span>Transaction Journal</span>
                </button>
              </div>

              <div className="flex items-center gap-1.5 flex-1 max-w-sm">
                <input
                  type="text"
                  placeholder="Filter by transaction ID, description..."
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                  className="h-8 w-full rounded-xs border border-border bg-card px-2.5 text-xs text-foreground font-mono focus:outline-hidden focus:ring-1 focus:ring-ring"
                />
              </div>
            </div>

            <div className="flex flex-wrap items-center justify-between gap-2.5 p-2.5 rounded-sm border border-border/70 bg-card text-xs">
              <div className="flex flex-wrap items-center gap-2">
                <div className="flex items-center gap-1.5">
                  <span className="text-muted-foreground text-[11px] font-medium">Type:</span>
                  <select
                    value={typeFilter}
                    onChange={(e) => {
                      setTypeFilter(e.target.value)
                      setPage(0)
                    }}
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
                    onChange={(e) => {
                      setStatusFilter(e.target.value)
                      setPage(0)
                    }}
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
                    onChange={(e) => {
                      setFromDate(e.target.value)
                      setPage(0)
                    }}
                    className="h-7 rounded-xs border border-border bg-background px-2 text-xs text-foreground focus:outline-hidden focus:ring-1 focus:ring-ring font-mono"
                  />
                </div>

                <div className="flex items-center gap-1.5">
                  <span className="text-muted-foreground text-[11px] font-medium">To:</span>
                  <input
                    type="date"
                    value={toDate}
                    onChange={(e) => {
                      setToDate(e.target.value)
                      setPage(0)
                    }}
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
                {isFetchingData && !isLoadingData && (
                  <RotateCw className="size-3 animate-spin text-muted-foreground mr-1" />
                )}
                <span className="text-[11px]">
                  {viewMode === "journal" && statementData
                    ? `${statementData.totalElements} entries`
                    : viewMode === "transactions" && txData
                    ? `${txData.totalElements} transactions`
                    : ""}
                </span>
              </div>
            </div>
          </div>

          {/* Ledger Table */}
          <div className="border border-border/70 rounded-sm overflow-hidden bg-card">
            <div className="grid grid-cols-12 gap-3 px-3.5 py-2.5 bg-muted/40 text-[11px] font-medium text-muted-foreground uppercase tracking-wider border-b border-border/70">
              <span className="col-span-2">Date & Time</span>
              <span className="col-span-2">Type</span>
              <span className="col-span-3">Description / Flow</span>
              <span className="col-span-1 text-center">Flow</span>
              <span className="col-span-2 text-right">Amount</span>
              <span className="col-span-2 text-right">{viewMode === "journal" ? "Balance After" : "Status"}</span>
            </div>

            {isLoadingData ? (
              <div className="divide-y divide-border/50">
                {Array.from({ length: 6 }).map((_, i) => (
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
            ) : isErrorData ? (
              <div className="p-8 text-center space-y-2.5">
                <AlertCircle className="size-6 text-destructive mx-auto" />
                <p className="text-sm font-semibold text-foreground">
                  Failed to load ledger records
                </p>
                <p className="text-xs text-muted-foreground max-w-sm mx-auto">
                  {dataError?.message || "An error occurred while fetching ledger data."}
                </p>
                <Button
                  variant="outline"
                  size="xs"
                  onClick={() => (viewMode === "journal" ? refetchStatement() : refetchTx())}
                  className="gap-1 mt-1 text-xs"
                >
                  <RotateCw className="size-3" />
                  <span>Retry</span>
                </Button>
              </div>
            ) : viewMode === "journal" ? (
              filteredStatementEntries.length === 0 ? (
                <div className="p-12 text-center space-y-3 bg-card">
                  <BookOpenText className="size-8 text-muted-foreground/40 mx-auto" />
                  <p className="text-sm font-medium text-foreground">
                    {hasActiveFilters ? "No entries match your filters" : "No journal entries recorded"}
                  </p>
                  <p className="text-xs text-muted-foreground max-w-sm mx-auto">
                    {hasActiveFilters
                      ? "Try adjusting search or filter parameters to locate entries."
                      : "Transactions posted to this account will append double-entry records here."}
                  </p>
                  {hasActiveFilters && (
                    <Button variant="outline" size="xs" onClick={handleResetFilters} className="mt-2 text-xs">
                      Reset Filters
                    </Button>
                  )}
                </div>
              ) : (
                <div className="divide-y divide-border/50">
                  {filteredStatementEntries.map((entry) => {
                    const isCredit = entry.direction === "CREDIT"
                    return (
                      <div
                        key={entry.transactionId}
                        onClick={() => setSelectedItem(entry)}
                        className="grid grid-cols-12 items-center gap-3 px-3.5 py-2.5 hover:bg-muted/30 transition-colors cursor-pointer select-none text-xs"
                      >
                        <div className="col-span-2 flex flex-col font-mono text-[11px] text-muted-foreground leading-tight">
                          <span>{formatDate(entry.createdAt)}</span>
                          <span className="text-[10px] text-muted-foreground/60 truncate" title={entry.transactionId}>
                            {entry.transactionId.slice(0, 8)}
                          </span>
                        </div>

                        <div className="col-span-2">
                          <TransactionTypeBadge type={entry.transactionType} />
                        </div>

                        <div className="col-span-3 text-muted-foreground truncate text-xs">
                          {entry.description || "—"}
                        </div>

                        <div className="col-span-1 text-center">
                          <span
                            className={cn(
                              "text-[10px] font-mono font-medium px-1.5 py-0.5 rounded-sm border",
                              isCredit
                                ? "text-emerald-700 dark:text-emerald-400 border-emerald-500/20 bg-emerald-500/5"
                                : "text-foreground border-border bg-muted/40"
                            )}
                          >
                            {isCredit ? "CR" : "DR"}
                          </span>
                        </div>

                        <div className="col-span-2 text-right">
                          <AmountDisplay
                            amount={entry.amount}
                            currency={entry.currency}
                            direction={isCredit ? "credit" : "debit"}
                            size="sm"
                            align="right"
                            showSign
                          />
                        </div>

                        <div className="col-span-2 text-right font-mono text-xs text-foreground font-medium">
                          <AmountDisplay
                            amount={entry.balanceAfter}
                            currency={entry.currency}
                            size="sm"
                            align="right"
                          />
                        </div>
                      </div>
                    )
                  })}
                </div>
              )
            ) : filteredTxEntries.length === 0 ? (
              <div className="p-12 text-center space-y-3 bg-card">
                <Inbox className="size-8 text-muted-foreground/40 mx-auto" />
                <p className="text-sm font-medium text-foreground">
                  {hasActiveFilters ? "No transactions match your filters" : "No transactions recorded"}
                </p>
                <p className="text-xs text-muted-foreground max-w-sm mx-auto">
                  {hasActiveFilters
                    ? "Try adjusting search or filter parameters to locate transactions."
                    : "Committed transactions affecting this checking instrument will appear here."}
                </p>
                {hasActiveFilters && (
                  <Button variant="outline" size="xs" onClick={handleResetFilters} className="mt-2 text-xs">
                    Reset Filters
                  </Button>
                )}
              </div>
            ) : (
              <div className="divide-y divide-border/50">
                {filteredTxEntries.map((tx) => {
                  const isCredit = tx.direction === "CREDIT"
                  return (
                    <div
                      key={tx.transactionId}
                      onClick={() => setSelectedItem(tx)}
                      className="grid grid-cols-12 items-center gap-3 px-3.5 py-2.5 hover:bg-muted/30 transition-colors cursor-pointer select-none text-xs"
                    >
                      <div className="col-span-2 flex flex-col font-mono text-[11px] text-muted-foreground leading-tight">
                        <span>{formatDate(tx.createdAt)}</span>
                        <span className="text-[10px] text-muted-foreground/60 truncate" title={tx.transactionId}>
                          {tx.transactionId.slice(0, 8)}
                        </span>
                      </div>

                      <div className="col-span-2">
                        <TransactionTypeBadge type={tx.transactionType} />
                      </div>

                      <div className="col-span-3 text-muted-foreground truncate text-xs">
                        {tx.description || (
                          <span className="font-mono text-[11px]">
                            {tx.sourceAccountId?.slice(0, 8)} → {tx.destinationAccountId?.slice(0, 8)}
                          </span>
                        )}
                      </div>

                      <div className="col-span-1 text-center">
                        <span
                          className={cn(
                            "text-[10px] font-mono font-medium px-1.5 py-0.5 rounded-sm border",
                            isCredit
                              ? "text-emerald-700 dark:text-emerald-400 border-emerald-500/20 bg-emerald-500/5"
                              : "text-foreground border-border bg-muted/40"
                          )}
                        >
                          {isCredit ? "CR" : "DR"}
                        </span>
                      </div>

                      <div className="col-span-2 text-right">
                        <AmountDisplay
                          amount={tx.amount}
                          currency={tx.currency}
                          direction={isCredit ? "credit" : "debit"}
                          size="sm"
                          align="right"
                          showSign
                        />
                      </div>

                      <div className="col-span-2 flex justify-end">
                        <StatusBadge status={tx.status} />
                      </div>
                    </div>
                  )
                })}
              </div>
            )}

            {/* Pagination Controls */}
            {((viewMode === "journal" && statementData && statementData.totalPages > 1) ||
              (viewMode === "transactions" && txData && txData.totalPages > 1)) && (
              <div className="flex items-center justify-between px-3.5 py-2.5 bg-muted/20 border-t border-border/70 text-xs text-muted-foreground">
                <div>
                  <span>
                    Page{" "}
                    <span className="font-medium text-foreground">
                      {viewMode === "journal" ? (statementData ? statementData.page + 1 : 1) : txData ? txData.page + 1 : 1}
                    </span>{" "}
                    of{" "}
                    <span className="font-medium text-foreground">
                      {viewMode === "journal" ? statementData?.totalPages : txData?.totalPages}
                    </span>
                  </span>
                </div>

                <div className="flex items-center gap-1">
                  <Button
                    variant="outline"
                    size="xs"
                    onClick={() => setPage((p) => Math.max(0, p - 1))}
                    disabled={
                      page === 0 ||
                      (viewMode === "journal" ? statementData?.first : txData?.first) ||
                      isLoadingData
                    }
                    className="gap-1 h-7 px-2"
                  >
                    <ChevronLeft className="size-3" />
                    <span>Previous</span>
                  </Button>
                  <Button
                    variant="outline"
                    size="xs"
                    onClick={() => setPage((p) => p + 1)}
                    disabled={
                      (viewMode === "journal"
                        ? statementData?.last || page >= (statementData?.totalPages ?? 1) - 1
                        : txData?.last || page >= (txData?.totalPages ?? 1) - 1) || isLoadingData
                    }
                    className="gap-1 h-7 px-2"
                  >
                    <span>Next</span>
                    <ChevronRight className="size-3" />
                  </Button>
                </div>
              </div>
            )}
          </div>
        </div>
      )}

      <TransactionDetailDialog
        open={Boolean(selectedItem)}
        onOpenChange={(open) => {
          if (!open) setSelectedItem(null)
        }}
        transaction={selectedItem}
      />
    </div>
  )
}
