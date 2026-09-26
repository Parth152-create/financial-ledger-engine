"use client"

import * as React from "react"
import { useSearchParams, useRouter } from "next/navigation"
import { TransferWorkflow } from "@/components/transfers/transfer-workflow"
import { DepositWorkflow } from "@/components/deposits/deposit-workflow"
import { WithdrawalWorkflow } from "@/components/withdrawals/withdrawal-workflow"
import { cn } from "@/lib/utils"

type TabType = "transfer" | "deposit" | "withdrawal"

function TransfersContent() {
  const searchParams = useSearchParams()
  const router = useRouter()

  const tabParam = searchParams.get("tab")
  const activeTab: TabType =
    tabParam === "deposit" ? "deposit" : tabParam === "withdrawal" ? "withdrawal" : "transfer"
  const accountId = searchParams.get("accountId") || undefined

  const handleTabChange = (tab: TabType) => {
    const newParams = new URLSearchParams(searchParams.toString())
    newParams.set("tab", tab)
    router.replace(`/app/transfers?${newParams.toString()}`)
  }

  const tabDescriptions: Record<TabType, string> = {
    transfer: "Execute atomic double-entry fund transfers between user accounts.",
    deposit: "Fund checking accounts with atomic double-entry deposits from platform clearing.",
    withdrawal: "Withdraw funds from checking accounts to platform clearing atomically.",
  }

  return (
    <div className="space-y-6 select-none font-sans">
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 pb-3 border-b border-border/70">
        <div>
          <h1 className="text-[26px] font-semibold tracking-tight text-foreground font-sans leading-tight">
            Transfers & Transactions
          </h1>
          <p className="text-[14px] text-muted-foreground font-sans mt-0.5">
            {tabDescriptions[activeTab]}
          </p>
        </div>

        <div className="flex items-center gap-1 border border-border/70 rounded-sm p-0.5 bg-muted/20">
          <button
            type="button"
            onClick={() => handleTabChange("transfer")}
            className={cn(
              "px-3 py-1 text-[13px] font-sans rounded-xs font-medium transition-colors",
              activeTab === "transfer"
                ? "bg-foreground text-background"
                : "text-muted-foreground hover:text-foreground"
            )}
          >
            Transfer
          </button>
          <button
            type="button"
            onClick={() => handleTabChange("deposit")}
            className={cn(
              "px-3 py-1 text-[13px] font-sans rounded-xs font-medium transition-colors",
              activeTab === "deposit"
                ? "bg-foreground text-background"
                : "text-muted-foreground hover:text-foreground"
            )}
          >
            Deposit
          </button>
          <button
            type="button"
            onClick={() => handleTabChange("withdrawal")}
            className={cn(
              "px-3 py-1 text-[13px] font-sans rounded-xs font-medium transition-colors",
              activeTab === "withdrawal"
                ? "bg-foreground text-background"
                : "text-muted-foreground hover:text-foreground"
            )}
          >
            Withdrawal
          </button>
        </div>
      </div>

      {activeTab === "transfer" && (
        <TransferWorkflow preselectedAccountId={accountId} />
      )}
      {activeTab === "deposit" && (
        <DepositWorkflow preselectedAccountId={accountId} />
      )}
      {activeTab === "withdrawal" && (
        <WithdrawalWorkflow preselectedAccountId={accountId} />
      )}
    </div>
  )
}

export default function TransfersPage() {
  return (
    <React.Suspense
      fallback={
        <div className="py-12 text-center text-sm text-muted-foreground font-sans">
          Loading operations console...
        </div>
      }
    >
      <TransfersContent />
    </React.Suspense>
  )
}
