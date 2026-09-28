"use client"

import * as React from "react"
import { useSearchParams } from "next/navigation"
import Link from "next/link"
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
  ShieldCheck,
  Plus,
} from "lucide-react"
import { Button } from "@/components/ui/button"
import { AmountDisplay } from "@/components/ui/amount-display"
import { StatusBadge } from "@/components/ui/status-badge"
import { TransactionTypeBadge } from "@/components/ledger/transaction-type-badge"
import { TransactionDetailDialog } from "@/components/transactions/transaction-detail-dialog"
import { CreateAccountDialog } from "@/components/accounts/create-account-dialog"
import { useAccounts } from "@/hooks/api/use-accounts"
import { useAccountStatement } from "@/hooks/api/use-statements"
import { useAccountTransactions } from "@/hooks/api/use-transactions"
import { formatDate } from "@/lib/formatters/date"
import { formatAccountFlowLabel, getLedgerErrorMessage, maskAccountNumber } from "@/lib/formatters/ledger"
import { ROUTES } from "@/constants/routes"
import type { StatementEntry, AccountStatementParams } from "@/types/statement"
import type { TransactionHistoryItem, TransactionHistoryParams, TransactionType, TransactionStatus } from "@/types/transaction"
import { cn } from "@/lib/utils"

type ViewMode = "transactions" | "journal"

function LedgerContent() {
  const searchParams = useSearchParams()
  const paramAccountId = searchParams.get("accountId")

  const {
    data: accounts,
    isLoading: isLoadingAccounts,
    isError: isErrorAccounts,
    error: accountsError,
    refetch: refetchAccounts,
  } = useAccounts()

  // Filter strictly to user checking accounts
  const checkingAccounts = React.useMemo(() => {
    if (!accounts) return []
    return accounts.filter((acc) => acc.accountType === "USER_CHECKING")
  }, [accounts])

  const [selectedAccountId, setSelectedAccountId] = React.useState<string>("")
  const [viewMode, setViewMode] = React.useState<ViewMode>("transactions")
  const [page, setPage] = React.useState(0)
  const pageSize = 20

  const [isCreateAccountOpen, setIsCreateAccountOpen] = React.useState(false)

  // Filters
  const [typeFilter, setTypeFilter] = React.useState<string>("")
  const [statusFilter, setStatusFilter] = React.useState<string>("")
  const [fromDate, setFromDate] = React.useState<string>("")
  const [toDate, setToDate] = React.useState<string>("")
  const [searchQuery, setSearchQuery] = React.useState<string>("")

  // Selected transaction for detail dialog
  const [selectedItem, setSelectedItem] = React.useState<StatementEntry | TransactionHistoryItem | null>(null)

  // Resolve active account ID: query parameter first, then selected, then first checking account
  const activeAccountId = React.useMemo(() => {
    if (paramAccountId && checkingAccounts.some((a) => a.accountId === paramAccountId)) {
      return paramAccountId
    }
    if (selectedAccountId && checkingAccounts.some((a) => a.accountId === selectedAccountId)) {
      return selectedAccountId
    }
    return checkingAccounts[0]?.accountId ?? ""
  }, [paramAccountId, selectedAccountId, checkingAccounts])

  const currentAccount = React.useMemo(() => {
    return checkingAccounts.find((a) => a.accountId === activeAccountId)
  }, [checkingAccounts, activeAccountId])

  // Query parameter builders
  const transactionParams: TransactionHistoryParams = React.useMemo(() => {
    const params: TransactionHistoryParams = { page, size: pageSize }
    if (typeFilter) params.transactionType = typeFilter as TransactionType
    if (statusFilter) params.status = statusFilter as TransactionStatus
    if (fromDate) params.from = `${fromDate}T00:00:00Z`
    if (toDate) params.to = `${toDate}T23:59:59Z`
    return params
  }, [page, pageSize, typeFilter, statusFilter, fromDate, toDate])

  const statementParams: AccountStatementParams = React.useMemo(() => {
    const params: AccountStatementParams = { page, size: pageSize }
    if (typeFilter) params.transactionType = typeFilter as TransactionType
    if (statusFilter) params.status = statusFilter as TransactionStatus
    if (fromDate) params.from = `${fromDate}T00:00:00Z`
    if (toDate) params.to = `${toDate}T23:59:59Z`
    return params
  }, [page, pageSize, typeFilter, statusFilter, fromDate, toDate])

  // Queries
  const {
    data: txData,
    isLoading: isLoadingTx,
    isError: isErrorTx,
    error: txError,
    refetch: refetchTx,
    isFetching: isFetchingTx,
  } = useAccountTransactions(activeAccountId, transactionParams, {
    enabled: Boolean(activeAccountId) && viewMode === "transactions",
  })

  const {
    data: statementData,
    isLoading: isLoadingStatement,
    isError: isErrorStatement,
    error: statementError,
    refetch: refetchStatement,
    isFetching: isFetchingStatement,
  } = useAccountStatement(activeAccountId, statementParams, {
    enabled: Boolean(activeAccountId) && viewMode === "journal",
  })

  const hasActiveFilters = Boolean(typeFilter || statusFilter || fromDate || toDate || searchQuery.trim())

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

  const handleViewModeChange = (mode: ViewMode) => {
    setViewMode(mode)
    setPage(0)
  }

  // Filter items matching client-side search query
  const txEntries = txData?.content ?? []
  const filteredTxEntries = searchQuery.trim()
    ? txEntries.filter((tx) => {
        const query = searchQuery.trim().toLowerCase()
        return (
          tx.transactionId.toLowerCase().includes(query) ||
          (tx.description && tx.description.toLowerCase().includes(query)) ||
          tx.transactionType.toLowerCase().includes(query)
        )
      })
    : txEntries

  const statementEntries = statementData?.entries ?? []
  const filteredStatementEntries = searchQuery.trim()
    ? statementEntries.filter((entry) => {
        const query = searchQuery.trim().toLowerCase()
        return (
          entry.transactionId.toLowerCase().includes(query) ||
          (entry.description && entry.description.toLowerCase().includes(query)) ||
          entry.transactionType.toLowerCase().includes(query)
        )
      })
    : statementEntries

  const isLoadingData = viewMode === "transactions" ? isLoadingTx : isLoadingStatement
  const isErrorData = viewMode === "transactions" ? isErrorTx : isErrorStatement
  const dataError = viewMode === "transactions" ? txError : statementError
  const isFetchingData = viewMode === "transactions" ? isFetchingTx : isFetchingStatement

  const activeTotalElements =
    viewMode === "transactions" ? txData?.totalElements ?? 0 : statementData?.totalElements ?? 0
  const activeTotalPages =
    viewMode === "transactions" ? txData?.totalPages ?? 1 : statementData?.totalPages ?? 1
  const isFirstPage =
    viewMode === "transactions" ? txData?.first ?? page === 0 : statementData?.first ?? page === 0
  const isLastPage =
    viewMode === "transactions"
      ? txData?.last ?? page >= activeTotalPages - 1
      : statementData?.last ?? page >= activeTotalPages - 1

  return (
    <div className="space-y-6 select-none font-sans">
      {/* Page Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 pb-3 border-b border-border/60">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight text-foreground font-sans">
            Ledger
          </h1>
          <p className="text-sm text-muted-foreground mt-0.5">
            Authoritative financial transaction history and statements.
          </p>
        </div>

        {/* Account Selector Dropdown */}
        {checkingAccounts.length > 0 && (
          <div className="flex items-center gap-2">
            <span className="text-xs font-medium text-muted-foreground whitespace-nowrap">
              Account Instrument:
            </span>
            <select
              value={activeAccountId}
              onChange={handleAccountChange}
              className="h-8.5 rounded-xs border border-border bg-card px-2.5 text-xs text-foreground font-mono focus:outline-hidden focus:ring-1 focus:ring-ring"
            >
              {checkingAccounts.map((acc) => (
                <option key={acc.accountId} value={acc.accountId}>
                  Checking {maskAccountNumber(acc.accountNumber)} ({acc.currency})
                </option>
              ))}
            </select>
          </div>
        )}
      </div>

      {/* Subtle Immutability Informational Indicator */}
      <div className="flex items-center gap-2 p-2.5 rounded-sm border border-border/60 bg-muted/20 text-xs text-muted-foreground">
        <ShieldCheck className="size-3.5 text-primary shrink-0" />
        <span>Ledger entries are immutable. Corrections are recorded as compensating transactions.</span>
      </div>

      {/* Loading Accounts */}
      {isLoadingAccounts ? (
        <div className="rounded-sm border border-border bg-card p-6 animate-pulse space-y-3">
          <div className="h-4 w-48 bg-muted rounded-xs" />
          <div className="h-3 w-72 bg-muted/60 rounded-xs" />
        </div>
      ) : isErrorAccounts ? (
        <div className="p-8 text-center space-y-2 bg-card border border-destructive/20 rounded-sm">
          <AlertCircle className="size-6 text-destructive mx-auto" />
          <p className="text-sm font-semibold text-foreground">Failed to load accounts</p>
          <p className="text-xs text-muted-foreground">
            {getLedgerErrorMessage(accountsError)}
          </p>
          <Button variant="outline" size="xs" onClick={() => refetchAccounts()} className="mt-2">
            Retry
          </Button>
        </div>
      ) : checkingAccounts.length === 0 ? (
        /* Zero Accounts Empty State */
        <div className="p-12 text-center space-y-3 bg-card border border-border/70 rounded-sm">
          <Building2 className="size-8 text-muted-foreground/40 mx-auto" />
          <p className="text-base font-semibold text-foreground">No Checking Accounts Found</p>
          <p className="text-xs text-muted-foreground max-w-sm mx-auto">
            You must have at least one active checking account to inspect ledger entries.
          </p>
          <div className="pt-2 flex items-center justify-center gap-2">
            <Button
              type="button"
              size="sm"
              onClick={() => setIsCreateAccountOpen(true)}
              className="gap-1.5"
            >
              <Plus className="size-3.5" />
              <span>Create Account</span>
            </Button>
            <Link href={ROUTES.ACCOUNTS}>
              <Button type="button" variant="outline" size="sm">
                Manage Accounts
              </Button>
            </Link>
          </div>
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
                  Checking {maskAccountNumber(currentAccount.accountNumber)}
                </div>
              </div>

              <div className="space-y-1">
                <span className="text-[11px] font-medium text-muted-foreground uppercase tracking-wider">
                  Available Balance
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
                  Integrity
                </span>
                <div className="text-xs text-emerald-600 dark:text-emerald-400 font-medium flex items-center gap-1">
                  <ShieldCheck className="size-3" />
                  <span>Verified</span>
                </div>
              </div>
            </div>
          )}

          {/* Statement Running Totals (if in journal mode) */}
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
              {/* View Mode Switcher */}
              <div className="flex items-center gap-1.5 p-0.5 rounded-sm bg-muted/60 border border-border">
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
                  <span>Transaction Ledger</span>
                </button>
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
                  <span>Statement & Balance</span>
                </button>
              </div>

              {/* Search Box */}
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

            {/* Filter Toolbar */}
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
                  {activeTotalElements} {viewMode === "journal" ? "entries" : "transactions"}
                </span>
              </div>
            </div>
          </div>

          {/* Ledger Table Container */}
          <div className="border border-border/70 rounded-sm overflow-hidden bg-card">
            <div className="overflow-x-auto">
              <div className="min-w-[720px]">
                {/* Table Header */}
                <div className="grid grid-cols-12 gap-3 px-3.5 py-2.5 bg-muted/40 text-[11px] font-medium text-muted-foreground uppercase tracking-wider border-b border-border/70">
                  <span className="col-span-2">Date & Time</span>
                  <span className="col-span-2">Type</span>
                  <span className="col-span-3">Counterparty / Description</span>
                  <span className="col-span-1 text-center">Flow</span>
                  <span className="col-span-2 text-right">Amount</span>
                  <span className="col-span-2 text-right">
                    {viewMode === "journal" ? "Balance After" : "Status"}
                  </span>
                </div>

                {/* Table Body */}
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
                      {getLedgerErrorMessage(dataError)}
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
                ) : viewMode === "transactions" ? (
                  filteredTxEntries.length === 0 ? (
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
                        const sourceLabel = formatAccountFlowLabel(tx.sourceAccountId, activeAccountId, checkingAccounts)
                        const destLabel = formatAccountFlowLabel(tx.destinationAccountId, activeAccountId, checkingAccounts)

                        return (
                          <div
                            key={tx.transactionId}
                            onClick={() => setSelectedItem(tx)}
                            className="grid grid-cols-12 items-center gap-3 px-3.5 py-2.5 hover:bg-muted/30 transition-colors cursor-pointer select-none text-xs"
                          >
                            {/* Date & ID */}
                            <div className="col-span-2 flex flex-col font-mono text-[11px] text-muted-foreground leading-tight">
                              <span>{formatDate(tx.createdAt)}</span>
                              <span className="text-[10px] text-muted-foreground/60 truncate" title={tx.transactionId}>
                                {tx.transactionId.slice(0, 8)}
                              </span>
                            </div>

                            {/* Type */}
                            <div className="col-span-2">
                              <TransactionTypeBadge type={tx.transactionType} />
                            </div>

                            {/* Counterparty Flow / Description */}
                            <div className="col-span-3 text-muted-foreground truncate text-xs space-y-0.5">
                              <div className="text-foreground font-medium truncate">
                                {tx.transactionType === "DEPOSIT" ? (
                                  <span>Platform Clearing → This Account</span>
                                ) : tx.transactionType === "WITHDRAWAL" ? (
                                  <span>This Account → Platform Clearing</span>
                                ) : (
                                  <span>{sourceLabel} → {destLabel}</span>
                                )}
                              </div>
                              {tx.description && (
                                <p className="text-[11px] text-muted-foreground truncate">
                                  {tx.description}
                                </p>
                              )}
                            </div>

                            {/* Flow CR/DR */}
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

                            {/* Amount */}
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

                            {/* Status */}
                            <div className="col-span-2 flex justify-end">
                              <StatusBadge status={tx.status} />
                            </div>
                          </div>
                        )
                      })}
                    </div>
                  )
                ) : filteredStatementEntries.length === 0 ? (
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
                )}
              </div>
            </div>

            {/* Pagination Controls */}
            {activeTotalPages > 1 && (
              <div className="flex items-center justify-between px-3.5 py-2.5 bg-muted/20 border-t border-border/70 text-xs text-muted-foreground">
                <div>
                  <span>
                    Page <span className="font-medium text-foreground">{page + 1}</span> of{" "}
                    <span className="font-medium text-foreground">{activeTotalPages}</span>{" "}
                    <span className="text-muted-foreground/70">
                      ({activeTotalElements} total {viewMode === "journal" ? "entries" : "transactions"})
                    </span>
                  </span>
                </div>

                <div className="flex items-center gap-1">
                  <Button
                    variant="outline"
                    size="xs"
                    onClick={() => setPage((p) => Math.max(0, p - 1))}
                    disabled={isFirstPage || isLoadingData}
                    className="gap-1 h-7 px-2"
                  >
                    <ChevronLeft className="size-3" />
                    <span>Previous</span>
                  </Button>
                  <Button
                    variant="outline"
                    size="xs"
                    onClick={() => setPage((p) => p + 1)}
                    disabled={isLastPage || isLoadingData}
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

      {/* Transaction Details Dialog with Double-Entry Flow */}
      <TransactionDetailDialog
        open={Boolean(selectedItem)}
        onOpenChange={(open) => {
          if (!open) setSelectedItem(null)
        }}
        transaction={selectedItem}
        currentAccountId={activeAccountId}
        accounts={checkingAccounts}
      />

      {/* Create Account Dialog for empty state */}
      <CreateAccountDialog
        open={isCreateAccountOpen}
        onOpenChange={setIsCreateAccountOpen}
        onSuccess={() => {
          refetchAccounts()
        }}
      />
    </div>
  )
}

export default function LedgerPage() {
  return (
    <React.Suspense
      fallback={
        <div className="py-12 text-center text-sm text-muted-foreground font-sans">
          Loading ledger...
        </div>
      }
    >
      <LedgerContent />
    </React.Suspense>
  )
}
