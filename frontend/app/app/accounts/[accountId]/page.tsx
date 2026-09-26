"use client"

import * as React from "react"
import Link from "next/link"
import { useParams } from "next/navigation"
import {
  ArrowLeft,
  ArrowLeftRight,
  ArrowDownLeft,
  ArrowUpRight,
  ReceiptText,
  History,
  AlertCircle,
  RotateCw,
} from "lucide-react"
import { AccountHeader } from "@/components/accounts/account-header"
import { AccountMeta } from "@/components/accounts/account-meta"
import { Section } from "@/components/ui/section"
import { Button } from "@/components/ui/button"
import { AccountTransactionHistory } from "@/components/transactions/account-transaction-history"
import { AccountStatementView } from "@/components/statement/account-statement-view"
import { ROUTES } from "@/constants/routes"
import { useAccount } from "@/hooks/api/use-accounts"
import { formatDate } from "@/lib/formatters/date"
import { cn } from "@/lib/utils"

export default function AccountDetailPage() {
  const params = useParams()
  const accountId = typeof params?.accountId === "string" ? params.accountId : ""
  const [activeTab, setActiveTab] = React.useState<"transactions" | "statement">("transactions")

  const { data: account, isLoading, isError, error, refetch } = useAccount(accountId)

  if (isLoading) {
    return (
      <div className="space-y-6 select-none font-sans">
        <div className="flex items-center justify-between gap-3 pb-3 border-b border-border/70">
          <div className="flex items-center gap-2">
            <Link href={ROUTES.ACCOUNTS}>
              <Button variant="ghost" size="xs" className="gap-1 text-[13px]">
                <ArrowLeft className="size-3.5" />
                <span>Accounts</span>
              </Button>
            </Link>
            <span className="text-muted-foreground/40">/</span>
            <span className="text-[13px] font-mono text-muted-foreground truncate max-w-xs">
              {accountId}
            </span>
          </div>
        </div>

        <div className="rounded-sm border border-border bg-card p-6 animate-pulse space-y-3">
          <div className="h-4 w-48 bg-muted rounded-xs" />
          <div className="h-3 w-72 bg-muted/60 rounded-xs" />
        </div>

        <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
          <div className="md:col-span-1 rounded-sm border border-border bg-card p-4 animate-pulse space-y-3">
            <div className="h-4 w-28 bg-muted rounded-xs" />
            <div className="h-3 w-full bg-muted/60 rounded-xs" />
            <div className="h-3 w-full bg-muted/60 rounded-xs" />
            <div className="h-3 w-3/4 bg-muted/60 rounded-xs" />
          </div>

          <div className="md:col-span-2 rounded-sm border border-border bg-card p-4 animate-pulse space-y-3">
            <div className="h-4 w-40 bg-muted rounded-xs" />
            <div className="h-24 w-full bg-muted/30 rounded-xs" />
          </div>
        </div>
      </div>
    )
  }

  if (isError || !account) {
    return (
      <div className="space-y-6 select-none font-sans">
        <div className="flex items-center gap-2 pb-3 border-b border-border/70">
          <Link href={ROUTES.ACCOUNTS}>
            <Button variant="ghost" size="xs" className="gap-1 text-[13px]">
              <ArrowLeft className="size-3.5" />
              <span>Accounts</span>
            </Button>
          </Link>
          <span className="text-muted-foreground/40">/</span>
          <span className="text-[13px] font-mono text-muted-foreground truncate max-w-xs">
            {accountId}
          </span>
        </div>

        <div className="p-12 text-center space-y-3 bg-card border border-destructive/20 rounded-sm">
          <AlertCircle className="size-8 text-destructive mx-auto" />
          <p className="text-[15px] font-semibold text-foreground">
            Account Not Found or Access Unauthorized
          </p>
          <p className="text-[13.5px] text-muted-foreground max-w-md mx-auto">
            {error?.message ||
              "The requested checking account does not exist or you do not have permission to view it."}
          </p>
          <div className="flex items-center justify-center gap-2 pt-2">
            <Link href={ROUTES.ACCOUNTS}>
              <Button variant="outline" size="sm">
                Back to Accounts
              </Button>
            </Link>
            <Button
              variant="default"
              size="sm"
              onClick={() => refetch()}
              className="gap-1.5"
            >
              <RotateCw className="size-3.5" />
              <span>Retry</span>
            </Button>
          </div>
        </div>
      </div>
    )
  }

  return (
    <div className="space-y-6 select-none font-sans">
      <div className="flex items-center justify-between gap-3 pb-3 border-b border-border/70">
        <div className="flex items-center gap-2">
          <Link href={ROUTES.ACCOUNTS}>
            <Button variant="ghost" size="xs" className="gap-1 text-[13px]">
              <ArrowLeft className="size-3.5" />
              <span>Accounts</span>
            </Button>
          </Link>
          <span className="text-muted-foreground/40">/</span>
          <span className="text-[13px] font-mono text-muted-foreground truncate max-w-xs">
            {account.accountNumber}
          </span>
        </div>

        <div className="flex items-center gap-2">
          <Link href={`${ROUTES.TRANSFERS}?tab=deposit&accountId=${account.accountId}`}>
            <Button variant="outline" size="sm" className="gap-1.5">
              <ArrowDownLeft className="size-3.5" />
              <span>Deposit</span>
            </Button>
          </Link>
          <Link href={`${ROUTES.TRANSFERS}?tab=withdrawal&accountId=${account.accountId}`}>
            <Button variant="outline" size="sm" className="gap-1.5">
              <ArrowUpRight className="size-3.5" />
              <span>Withdraw</span>
            </Button>
          </Link>
          <Link href={`${ROUTES.TRANSFERS}?tab=transfer&accountId=${account.accountId}`}>
            <Button size="sm" className="gap-1.5">
              <ArrowLeftRight className="size-3.5" />
              <span>Transfer Funds</span>
            </Button>
          </Link>
        </div>
      </div>

      <AccountHeader
        accountId={account.accountId}
        accountNumber={account.accountNumber}
        accountType={account.accountType === "USER_CHECKING" ? "Checking" : account.accountType}
        currency={account.currency}
        status={account.status}
        balance={account.balance}
        actions={
          <Button
            variant={activeTab === "statement" ? "default" : "outline"}
            size="sm"
            onClick={() => setActiveTab((prev) => (prev === "statement" ? "transactions" : "statement"))}
            className="gap-1.5"
          >
            <ReceiptText className="size-3.5" />
            <span>{activeTab === "statement" ? "View Transactions" : "Account Statement"}</span>
          </Button>
        }
      />

      <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
        <div className="md:col-span-1">
          <AccountMeta
            accountId={account.accountId}
            currency={account.currency}
            accountType={account.accountType}
            status={account.status}
            createdAt={formatDate(account.createdAt)}
            reconciliationStatus="CONSISTENT"
          />
        </div>

        <div className="md:col-span-2 space-y-3">
          <div className="flex items-center justify-between border-b border-border/70 pb-2">
            <div className="flex items-center gap-2">
              <button
                type="button"
                onClick={() => setActiveTab("transactions")}
                className={cn(
                  "flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium rounded-xs transition-colors",
                  activeTab === "transactions"
                    ? "bg-muted text-foreground font-semibold"
                    : "text-muted-foreground hover:text-foreground hover:bg-muted/50"
                )}
              >
                <History className="size-3.5" />
                <span>Transaction History</span>
              </button>
              <button
                type="button"
                onClick={() => setActiveTab("statement")}
                className={cn(
                  "flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium rounded-xs transition-colors",
                  activeTab === "statement"
                    ? "bg-muted text-foreground font-semibold"
                    : "text-muted-foreground hover:text-foreground hover:bg-muted/50"
                )}
              >
                <ReceiptText className="size-3.5" />
                <span>Account Statement</span>
              </button>
            </div>
          </div>

          {activeTab === "transactions" ? (
            <Section
              title="Account Transaction History"
              description="All debit and credit movements affecting this account."
            >
              <AccountTransactionHistory
                accountId={account.accountId}
                currency={account.currency}
              />
            </Section>
          ) : (
            <Section
              title="Account Statement"
              description="Chronological record of transactions with opening, closing, and running balances."
            >
              <AccountStatementView
                accountId={account.accountId}
                currency={account.currency}
              />
            </Section>
          )}
        </div>
      </div>
    </div>
  )
}
