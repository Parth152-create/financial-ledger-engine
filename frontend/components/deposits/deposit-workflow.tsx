"use client"

import * as React from "react"
import Link from "next/link"
import {
  Landmark,
  Plus,
  RotateCw,
  AlertCircle,
  Loader2,
  ShieldCheck,
  Lock,
  RefreshCw,
} from "lucide-react"
import { Section } from "@/components/ui/section"
import { Button } from "@/components/ui/button"
import { DataRow } from "@/components/ui/data-row"
import { CreateAccountDialog } from "@/components/accounts/create-account-dialog"
import { DepositForm } from "@/components/deposits/deposit-form"
import { DepositResult } from "@/components/deposits/deposit-result"
import { useAccounts } from "@/hooks/api/use-accounts"
import { ROUTES } from "@/constants/routes"
import type { DepositResponse } from "@/types/transaction"
import type { Account } from "@/types/account"

interface DepositWorkflowProps {
  preselectedAccountId?: string
}

export function DepositWorkflow({ preselectedAccountId }: DepositWorkflowProps) {
  const [successResult, setSuccessResult] = React.useState<DepositResponse | null>(null)
  const [lastDestAccount, setLastDestAccount] = React.useState<Account | undefined>(undefined)
  const [isCreateAccountOpen, setIsCreateAccountOpen] = React.useState(false)

  // Focus ref for state transitions
  const headingRef = React.useRef<HTMLHeadingElement>(null)

  // Fetch accounts from API
  const {
    data: accounts,
    isLoading: isAccountsLoading,
    isError: isAccountsError,
    error: accountsError,
    refetch: refetchAccounts,
  } = useAccounts()

  // Filter strictly to user-owned checking accounts
  const checkingAccounts = React.useMemo(() => {
    if (!accounts) return []
    return accounts.filter((acc) => acc.accountType === "USER_CHECKING")
  }, [accounts])

  const handleDepositSuccess = (
    result: DepositResponse,
    destAccount?: Account
  ) => {
    setSuccessResult(result)
    setLastDestAccount(destAccount)
    setTimeout(() => {
      headingRef.current?.focus()
    }, 50)
  }

  const handleStartAnother = () => {
    setSuccessResult(null)
    setLastDestAccount(undefined)
    refetchAccounts()
    setTimeout(() => {
      headingRef.current?.focus()
    }, 50)
  }

  return (
    <>
      <div className="grid grid-cols-1 lg:grid-cols-12 gap-6 font-sans">
        {/* Main Operational Column */}
        <div className="lg:col-span-7">
          <Section
            title={
              <h2
                ref={headingRef}
                tabIndex={-1}
                className="text-[17px] font-semibold text-foreground font-sans tracking-tight outline-none"
              >
                {successResult ? "Deposit Receipt" : "Deposit Funds"}
              </h2>
            }
            description={
              successResult
                ? "The deposit was committed atomically to the PostgreSQL ledger."
                : "Fund your checking account directly from platform clearing."
            }
            badge={
              <span className="text-[11px] font-medium px-2 py-0.5 rounded-sm bg-muted/60 text-muted-foreground border border-border/60">
                INR Only
              </span>
            }
          >
            {/* Loading State Skeleton */}
            {isAccountsLoading ? (
              <div className="py-10 text-center space-y-3 font-sans">
                <Loader2 className="size-6 animate-spin text-muted-foreground mx-auto" />
                <p className="text-[13.5px] text-muted-foreground">
                  Loading checking accounts...
                </p>
              </div>
            ) : isAccountsError ? (
              /* Accounts Load Error */
              <div className="p-6 text-center space-y-3 rounded-sm border border-destructive/20 bg-destructive/5 font-sans">
                <AlertCircle className="size-7 text-destructive mx-auto" />
                <p className="text-[14px] font-semibold text-foreground">
                  Failed to load accounts
                </p>
                <p className="text-xs text-muted-foreground max-w-sm mx-auto">
                  {accountsError?.message || "Could not retrieve your accounts from the ledger."}
                </p>
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={() => refetchAccounts()}
                  className="gap-1.5"
                >
                  <RotateCw className="size-3.5" />
                  <span>Retry</span>
                </Button>
              </div>
            ) : checkingAccounts.length === 0 ? (
              /* Zero Accounts Empty State */
              <div className="p-8 text-center space-y-3 font-sans">
                <Landmark className="size-8 text-muted-foreground/40 mx-auto" />
                <h3 className="text-[15px] font-semibold text-foreground">
                  No accounts available
                </h3>
                <p className="text-[13px] text-muted-foreground max-w-sm mx-auto">
                  Create an account before making a deposit.
                </p>
                <div className="pt-2 flex flex-col sm:flex-row items-center justify-center gap-2">
                  <Button
                    type="button"
                    size="sm"
                    onClick={() => setIsCreateAccountOpen(true)}
                    className="gap-1.5 w-full sm:w-auto"
                  >
                    <Plus className="size-3.5" />
                    <span>Create Account</span>
                  </Button>
                  <Link href={ROUTES.ACCOUNTS} className="w-full sm:w-auto">
                    <Button
                      type="button"
                      variant="outline"
                      size="sm"
                      className="w-full sm:w-auto"
                    >
                      <span>Go to Accounts</span>
                    </Button>
                  </Link>
                </div>
              </div>
            ) : successResult ? (
              /* Success State */
              <DepositResult
                result={successResult}
                destinationAccount={lastDestAccount}
                onStartAnother={handleStartAnother}
              />
            ) : (
              /* Active Deposit Form */
              <DepositForm
                accounts={checkingAccounts}
                initialDestinationAccountId={preselectedAccountId}
                onSuccess={handleDepositSuccess}
              />
            )}
          </Section>
        </div>

        {/* Operational Sidebar Column */}
        <div className="lg:col-span-5 space-y-5 font-sans">
          <Section title="Financial Ledger Invariants" variant="bordered">
            <div className="space-y-1">
              <DataRow
                label="Currency"
                value="INR (₹)"
              />
              <DataRow
                label="Deposit Model"
                value="Platform Clearing (Double-Entry)"
              />
              <DataRow
                label="Source Entity"
                value="SYSTEM_CLEARING"
              />
              <DataRow
                label="Accounting Guarantee"
                value="Balanced Debit & Credit"
              />
              <DataRow
                label="Atomicity"
                value="PostgreSQL Row-Locking (ACID)"
              />
              <DataRow
                label="Idempotency Protection"
                value="Header-Enforced Unique Key"
              />
            </div>
          </Section>

          <Section title="Operational Guarantees" variant="subtle">
            <div className="space-y-2 text-[12.5px] text-muted-foreground leading-relaxed font-sans">
              <div className="flex items-start gap-2">
                <Lock className="size-3.5 text-primary shrink-0 mt-0.5" />
                <p>
                  Platform clearing and user accounts are locked deterministically during transaction commit.
                </p>
              </div>
              <div className="flex items-start gap-2">
                <RefreshCw className="size-3.5 text-primary shrink-0 mt-0.5" />
                <p>
                  Repeated submissions reuse the identical Idempotency-Key to prevent duplicate ledger transactions.
                </p>
              </div>
              <div className="flex items-start gap-2">
                <ShieldCheck className="size-3.5 text-primary shrink-0 mt-0.5" />
                <p>
                  The engine debits SYSTEM_CLEARING and credits user checking with strict double-entry balance.
                </p>
              </div>
            </div>
          </Section>
        </div>
      </div>

      <CreateAccountDialog
        open={isCreateAccountOpen}
        onOpenChange={setIsCreateAccountOpen}
        onSuccess={() => {
          refetchAccounts()
        }}
      />
    </>
  )
}
