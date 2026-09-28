"use client"

import * as React from "react"
import Link from "next/link"
import {
  ArrowLeftRight,
  ArrowDownLeft,
  Plus,
  ArrowRight,
  CheckCircle2,
  Landmark,
  Loader2,
  AlertCircle,
} from "lucide-react"
import { Button } from "@/components/ui/button"
import { AmountDisplay } from "@/components/ui/amount-display"
import { StatusBadge } from "@/components/ui/status-badge"
import { CreateAccountDialog } from "@/components/accounts/create-account-dialog"
import { useAccounts } from "@/hooks/api/use-accounts"
import { useAccountTransactions } from "@/hooks/api/use-transactions"
import { useReconciliation } from "@/hooks/api/use-reconciliation"
import { formatINR } from "@/lib/formatters/currency"
import { maskAccountNumber, formatAccountFlowLabel } from "@/lib/formatters/ledger"
import { formatDate } from "@/lib/formatters/date"
import { ROUTES } from "@/constants/routes"

export default function DashboardPage() {
  const [isCreateOpen, setIsCreateOpen] = React.useState(false)

  const {
    data: accounts,
    isLoading: isAccountsLoading,
    isError: isAccountsError,
    refetch: refetchAccounts,
  } = useAccounts()

  const checkingAccounts = React.useMemo(() => {
    if (!accounts) return []
    return accounts.filter((acc) => acc.accountType === "USER_CHECKING")
  }, [accounts])

  const totalBalance = React.useMemo(() => {
    return checkingAccounts.reduce((sum, acc) => {
      const balanceNum = typeof acc.balance === "number" ? acc.balance : parseFloat(acc.balance) || 0
      return sum + balanceNum
    }, 0)
  }, [checkingAccounts])

  const primaryAccountId = checkingAccounts[0]?.accountId ?? ""

  const {
    data: txData,
    isLoading: isTxLoading,
  } = useAccountTransactions(
    primaryAccountId,
    { page: 0, size: 5 },
    { enabled: Boolean(primaryAccountId) }
  )

  const { data: reconData } = useReconciliation()

  const recentTransactions = txData?.content ?? []

  return (
    <div className="space-y-8 select-none font-sans max-w-6xl">
      {/* Page Header & Actions */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 pb-4 border-b border-border/60">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight text-foreground font-sans">
            Overview
          </h1>
          <p className="text-sm text-muted-foreground mt-0.5">
            Your balances, accounts, and recent ledger activity.
          </p>
        </div>

        <div className="flex items-center gap-2">
          <Link href={ROUTES.TRANSFERS}>
            <Button size="sm" className="gap-1.5 text-xs font-medium h-8 px-3">
              <ArrowLeftRight className="size-3.5" />
              <span>Transfer</span>
            </Button>
          </Link>
          <Link href={`${ROUTES.TRANSFERS}?tab=deposit`}>
            <Button variant="outline" size="sm" className="gap-1.5 text-xs font-medium h-8 px-3">
              <ArrowDownLeft className="size-3.5" />
              <span>Deposit</span>
            </Button>
          </Link>
          <Button
            variant="ghost"
            size="sm"
            onClick={() => setIsCreateOpen(true)}
            className="gap-1.5 text-xs font-medium h-8 px-3 text-muted-foreground hover:text-foreground"
          >
            <Plus className="size-3.5" />
            <span>New Account</span>
          </Button>
        </div>
      </div>

      {/* Dominant Total Balance Banner */}
      <div className="rounded-sm border border-border/80 bg-card p-6 shadow-2xs">
        <div className="flex flex-col md:flex-row md:items-end justify-between gap-4">
          <div className="space-y-1.5">
            <span className="text-xs font-medium text-muted-foreground uppercase tracking-wider block">
              Total Available Balance
            </span>
            <div className="text-3xl sm:text-4xl font-semibold tracking-tight text-foreground font-mono">
              {isAccountsLoading ? (
                <div className="h-9 w-44 bg-muted animate-pulse rounded-xs" />
              ) : (
                formatINR(totalBalance)
              )}
            </div>
            <p className="text-xs text-muted-foreground">
              {checkingAccounts.length === 0
                ? "No active checking accounts"
                : `Across ${checkingAccounts.length} ${checkingAccounts.length === 1 ? "checking account" : "checking accounts"}`}
            </p>
          </div>

          {/* Calm Ledger Health Indicator */}
          <div className="flex items-center gap-2 px-3 py-1.5 rounded-sm bg-muted/30 border border-border/60 text-xs text-muted-foreground">
            <CheckCircle2 className="size-3.5 text-emerald-600 dark:text-emerald-400 shrink-0" />
            <span>
              {reconData?.consistentAccounts
                ? `Ledger Verified • ${reconData.consistentAccounts} accounts reconciled`
                : "Ledger Consistent • Double-entry balanced"}
            </span>
          </div>
        </div>
      </div>

      {/* Accounts & Recent Activity Grid */}
      <div className="grid grid-cols-1 lg:grid-cols-12 gap-8">
        {/* Left Column: Accounts List */}
        <div className="lg:col-span-6 space-y-4">
          <div className="flex items-center justify-between">
            <h2 className="text-sm font-semibold text-foreground uppercase tracking-wider">
              Accounts
            </h2>
            <Link
              href={ROUTES.ACCOUNTS}
              className="text-xs font-medium text-muted-foreground hover:text-foreground transition-colors flex items-center gap-1"
            >
              <span>View all</span>
              <ArrowRight className="size-3" />
            </Link>
          </div>

          {isAccountsLoading ? (
            <div className="space-y-2.5">
              {[1, 2].map((i) => (
                <div
                  key={i}
                  className="rounded-sm border border-border/60 bg-card p-4 space-y-2 animate-pulse"
                >
                  <div className="h-3 w-28 bg-muted rounded-xs" />
                  <div className="h-5 w-36 bg-muted/70 rounded-xs" />
                </div>
              ))}
            </div>
          ) : isAccountsError ? (
            <div className="rounded-sm border border-destructive/20 bg-destructive/5 p-6 text-center space-y-2">
              <AlertCircle className="size-5 text-destructive mx-auto" />
              <p className="text-xs text-muted-foreground">Unable to load accounts</p>
              <Button variant="outline" size="xs" onClick={() => refetchAccounts()}>
                Retry
              </Button>
            </div>
          ) : checkingAccounts.length === 0 ? (
            <div className="rounded-sm border border-border/60 bg-card p-6 text-center space-y-3">
              <Landmark className="size-6 text-muted-foreground/50 mx-auto" />
              <p className="text-xs font-medium text-foreground">No accounts yet</p>
              <p className="text-xs text-muted-foreground max-w-xs mx-auto">
                Create your first checking account to deposit funds and initiate transfers.
              </p>
              <Button
                size="sm"
                onClick={() => setIsCreateOpen(true)}
                className="gap-1.5 text-xs h-7 mt-1"
              >
                <Plus className="size-3" />
                <span>Create Account</span>
              </Button>
            </div>
          ) : (
            <div className="space-y-2.5">
              {checkingAccounts.map((acc) => (
                <Link
                  key={acc.accountId}
                  href={ROUTES.ACCOUNT_DETAILS(acc.accountId)}
                  className="block rounded-sm border border-border/70 bg-card p-4 hover:border-border hover:bg-muted/20 transition-colors group"
                >
                  <div className="flex items-center justify-between gap-3">
                    <div className="space-y-1 min-w-0">
                      <div className="flex items-center gap-2">
                        <span className="text-xs font-medium text-foreground">
                          Checking Account
                        </span>
                        {acc.status !== "ACTIVE" && (
                          <StatusBadge status={acc.status} />
                        )}
                      </div>
                      <p className="text-xs font-mono text-muted-foreground">
                        {maskAccountNumber(acc.accountNumber)}
                      </p>
                    </div>

                    <div className="text-right shrink-0">
                      <span className="text-xs text-muted-foreground block mb-0.5">
                        Balance
                      </span>
                      <AmountDisplay
                        amount={acc.balance}
                        currency={acc.currency}
                        size="default"
                        align="right"
                      />
                    </div>
                  </div>
                </Link>
              ))}
            </div>
          )}
        </div>

        {/* Right Column: Recent Activity Feed */}
        <div className="lg:col-span-6 space-y-4">
          <div className="flex items-center justify-between">
            <h2 className="text-sm font-semibold text-foreground uppercase tracking-wider">
              Recent Activity
            </h2>
            <Link
              href={ROUTES.LEDGER}
              className="text-xs font-medium text-muted-foreground hover:text-foreground transition-colors flex items-center gap-1"
            >
              <span>View ledger</span>
              <ArrowRight className="size-3" />
            </Link>
          </div>

          {isTxLoading ? (
            <div className="rounded-sm border border-border/60 bg-card p-8 text-center">
              <Loader2 className="size-4 animate-spin text-muted-foreground mx-auto" />
            </div>
          ) : recentTransactions.length === 0 ? (
            <div className="rounded-sm border border-border/60 bg-card p-6 text-center space-y-2">
              <p className="text-xs font-medium text-foreground">No recent transactions</p>
              <p className="text-xs text-muted-foreground max-w-xs mx-auto">
                Transactions and deposits will appear here as money moves through the ledger.
              </p>
              <div className="pt-2 flex items-center justify-center gap-2">
                <Link href={ROUTES.TRANSFERS}>
                  <Button variant="outline" size="xs" className="text-xs">
                    Transfer
                  </Button>
                </Link>
                <Link href={`${ROUTES.TRANSFERS}?tab=deposit`}>
                  <Button variant="outline" size="xs" className="text-xs">
                    Deposit
                  </Button>
                </Link>
              </div>
            </div>
          ) : (
            <div className="rounded-sm border border-border/70 bg-card divide-y divide-border/50 overflow-hidden">
              {recentTransactions.map((tx) => {
                const isCredit = tx.direction === "CREDIT"
                const flowLabel =
                  tx.transactionType === "DEPOSIT"
                    ? "Deposit from Clearing"
                    : tx.transactionType === "WITHDRAWAL"
                    ? "Withdrawal to Clearing"
                    : formatAccountFlowLabel(tx.destinationAccountId, primaryAccountId, checkingAccounts)

                return (
                  <div
                    key={tx.transactionId}
                    className="flex items-center justify-between gap-3 px-4 py-3 hover:bg-muted/20 transition-colors text-xs"
                  >
                    <div className="space-y-0.5 min-w-0">
                      <p className="font-medium text-foreground truncate">
                        {flowLabel}
                      </p>
                      <p className="text-[11px] text-muted-foreground">
                        {formatDate(tx.createdAt)}
                      </p>
                    </div>

                    <div className="text-right shrink-0">
                      <AmountDisplay
                        amount={tx.amount}
                        currency={tx.currency}
                        direction={isCredit ? "credit" : "debit"}
                        size="sm"
                        align="right"
                        showSign
                      />
                    </div>
                  </div>
                )
              })}
            </div>
          )}
        </div>
      </div>

      <CreateAccountDialog
        open={isCreateOpen}
        onOpenChange={setIsCreateOpen}
        onSuccess={() => refetchAccounts()}
      />
    </div>
  )
}
