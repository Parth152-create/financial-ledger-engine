"use client"

import * as React from "react"
import Link from "next/link"
import {
  Landmark,
  Plus,
  RotateCw,
  AlertCircle,
  Loader2,
} from "lucide-react"
import { Section } from "@/components/ui/section"
import { Button } from "@/components/ui/button"
import { CreateAccountDialog } from "@/components/accounts/create-account-dialog"
import { TransferForm } from "@/components/transfers/transfer-form"
import { TransferResult } from "@/components/transfers/transfer-result"
import { useAccounts } from "@/hooks/api/use-accounts"
import { ROUTES } from "@/constants/routes"
import type { TransferResponse } from "@/types/transaction"
import type { Account } from "@/types/account"

interface TransferWorkflowProps {
  preselectedAccountId?: string
}

export function TransferWorkflow({ preselectedAccountId }: TransferWorkflowProps) {
  const [successResult, setSuccessResult] = React.useState<TransferResponse | null>(null)
  const [lastSourceAccount, setLastSourceAccount] = React.useState<Account | undefined>(undefined)
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

  const handleTransferSuccess = (
    result: TransferResponse,
    sourceAccount?: Account,
    destAccount?: Account
  ) => {
    setSuccessResult(result)
    setLastSourceAccount(sourceAccount)
    setLastDestAccount(destAccount)
    setTimeout(() => {
      headingRef.current?.focus()
    }, 50)
  }

  const handleStartAnother = () => {
    setSuccessResult(null)
    setLastSourceAccount(undefined)
    setLastDestAccount(undefined)
    refetchAccounts()
    setTimeout(() => {
      headingRef.current?.focus()
    }, 50)
  }

  return (
    <>
      <div className="max-w-xl mx-auto font-sans space-y-3">
        <Section
          title={
            <h2
              ref={headingRef}
              tabIndex={-1}
              className="text-[17px] font-semibold text-foreground font-sans tracking-tight outline-none"
            >
              {successResult ? "Transfer Receipt" : "Transfer Funds"}
            </h2>
          }
          description={
            successResult
              ? "The funds have been transferred successfully."
              : "Transfer funds directly between your checking accounts."
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
                Create an account before making a transfer.
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
          ) : checkingAccounts.length === 1 ? (
            /* Single Account State */
            <div className="p-6 space-y-4 rounded-sm border border-border/80 bg-muted/20 font-sans">
              <div className="flex items-start gap-3">
                <AlertCircle className="size-5 text-amber-600 dark:text-amber-400 shrink-0 mt-0.5" />
                <div className="space-y-1">
                  <p className="text-[14px] font-medium text-foreground">
                    Additional account required
                  </p>
                  <p className="text-[13px] text-muted-foreground leading-relaxed">
                    You need at least two eligible accounts to transfer funds. You currently have 1 account provisioned in the ledger.
                  </p>
                </div>
              </div>
              <div className="flex flex-col sm:flex-row items-center gap-2 pt-1">
                <Button
                  type="button"
                  size="sm"
                  onClick={() => setIsCreateAccountOpen(true)}
                  className="gap-1.5 w-full sm:w-auto"
                >
                  <Plus className="size-3.5" />
                  <span>Create Second Account</span>
                </Button>
                <Link href={ROUTES.ACCOUNTS} className="w-full sm:w-auto">
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    className="w-full sm:w-auto"
                  >
                    <span>Manage Accounts</span>
                  </Button>
                </Link>
              </div>
            </div>
          ) : successResult ? (
            /* Success State */
            <TransferResult
              result={successResult}
              sourceAccount={lastSourceAccount}
              destinationAccount={lastDestAccount}
              onStartAnother={handleStartAnother}
            />
          ) : (
            /* Active Transfer Form */
            <TransferForm
              accounts={checkingAccounts}
              initialSourceAccountId={preselectedAccountId}
              onSuccess={handleTransferSuccess}
            />
          )}
        </Section>

        {!successResult && checkingAccounts.length > 1 && (
          <p className="text-center text-xs text-muted-foreground">
            Transfers between your checking accounts settle immediately.
          </p>
        )}
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
