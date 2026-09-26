"use client"

import * as React from "react"
import Link from "next/link"
import {
  ArrowUpRight,
  ArrowRight,
  ArrowLeft,
  CheckCircle2,
  AlertCircle,
  Loader2,
  RotateCw,
  Plus,
  Landmark,
  ShieldCheck,
} from "lucide-react"
import { Section } from "@/components/ui/section"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { DataRow } from "@/components/ui/data-row"
import { StatusBadge } from "@/components/ui/status-badge"
import { AmountDisplay } from "@/components/ui/amount-display"
import { CreateAccountDialog } from "@/components/accounts/create-account-dialog"
import { useAccounts } from "@/hooks/api/use-accounts"
import { useExecuteWithdrawal } from "@/hooks/api/use-withdrawals"
import {
  validateWithdrawalForm,
  getWithdrawalErrorMessage,
  type WithdrawalFormValues,
} from "@/lib/validators/withdrawal"
import { formatDate } from "@/lib/formatters/date"
import { ROUTES } from "@/constants/routes"
import type { WithdrawalResponse } from "@/types/transaction"
import type { Account } from "@/types/account"

type WorkflowStep = "FORM" | "REVIEW" | "SUCCESS"

export function WithdrawalWorkflow({
  preselectedAccountId,
}: {
  preselectedAccountId?: string
}) {
  const [step, setStep] = React.useState<WorkflowStep>("FORM")
  const [isCreateAccountOpen, setIsCreateAccountOpen] = React.useState(false)

  const [values, setValues] = React.useState<WithdrawalFormValues>({
    accountId: preselectedAccountId || "",
    amount: "",
    currency: "USD",
    description: "",
  })

  const [idempotencyKey, setIdempotencyKey] = React.useState<string | null>(null)
  const [errors, setErrors] = React.useState<Record<string, string>>({})
  const [submitError, setSubmitError] = React.useState<string | null>(null)
  const [successResult, setSuccessResult] = React.useState<WithdrawalResponse | null>(null)

  const stepHeadingRef = React.useRef<HTMLHeadingElement>(null)
  const accountSelectRef = React.useRef<HTMLSelectElement>(null)
  const amountInputRef = React.useRef<HTMLInputElement>(null)
  const confirmButtonRef = React.useRef<HTMLButtonElement>(null)

  const {
    data: allAccounts,
    isLoading: isAccountsLoading,
    isError: isAccountsError,
    error: accountsError,
    refetch: refetchAccounts,
  } = useAccounts()

  const executeWithdrawalMutation = useExecuteWithdrawal()

  const checkingAccounts = React.useMemo(() => {
    if (!allAccounts) return []
    return allAccounts.filter((acc) => acc.accountType === "USER_CHECKING")
  }, [allAccounts])

  const selectedAccount = React.useMemo<Account | undefined>(() => {
    return checkingAccounts.find((a) => a.accountId === values.accountId)
  }, [checkingAccounts, values.accountId])


  const handleAccountChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    const newAccountId = e.target.value
    const matched = checkingAccounts.find((a) => a.accountId === newAccountId)
    setValues((prev) => ({
      ...prev,
      accountId: newAccountId,
      currency: matched ? matched.currency : prev.currency,
    }))
    if (errors.accountId) {
      setErrors((prev) => {
        const next = { ...prev }
        delete next.accountId
        return next
      })
    }
  }

  const handleAmountChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const newAmount = e.target.value
    setValues((prev) => ({
      ...prev,
      amount: newAmount,
    }))
    if (errors.amount) {
      setErrors((prev) => {
        const next = { ...prev }
        delete next.amount
        return next
      })
    }
  }

  const handleDescriptionChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const newDesc = e.target.value
    setValues((prev) => ({
      ...prev,
      description: newDesc,
    }))
    if (errors.description) {
      setErrors((prev) => {
        const next = { ...prev }
        delete next.description
        return next
      })
    }
  }

  const handleProceedToReview = (e: React.FormEvent) => {
    e.preventDefault()
    setSubmitError(null)

    const validation = validateWithdrawalForm(values, selectedAccount)
    if (!validation.isValid) {
      setErrors(validation.errors as Record<string, string>)
      if (validation.errors.accountId) {
        accountSelectRef.current?.focus()
      } else if (validation.errors.amount) {
        amountInputRef.current?.focus()
      }
      return
    }

    setErrors({})

    if (!idempotencyKey) {
      const generatedKey =
        typeof crypto !== "undefined" && crypto.randomUUID
          ? crypto.randomUUID()
          : `withdrawal-${Date.now()}-${Math.random().toString(36).substring(2, 10)}`
      setIdempotencyKey(generatedKey)
    }

    setStep("REVIEW")
    setTimeout(() => {
      stepHeadingRef.current?.focus()
    }, 50)
  }

  const handleBackToEdit = () => {
    setStep("FORM")
    setSubmitError(null)
    setIdempotencyKey(null)
    setTimeout(() => {
      amountInputRef.current?.focus()
    }, 50)
  }

  const handleExecuteWithdrawal = async () => {
    if (!idempotencyKey) return

    setSubmitError(null)
    try {
      const result = await executeWithdrawalMutation.mutateAsync({
        request: {
          accountId: values.accountId,
          amount: Number(values.amount),
          currency: values.currency,
          description: values.description.trim() ? values.description.trim() : undefined,
        },
        idempotencyKey,
      })

      setSuccessResult(result)
      setStep("SUCCESS")
      setTimeout(() => {
        stepHeadingRef.current?.focus()
      }, 50)
    } catch (err) {
      const friendlyMessage = getWithdrawalErrorMessage(err)
      setSubmitError(friendlyMessage)
    }
  }

  const handleStartAnotherWithdrawal = () => {
    setValues({
      accountId: values.accountId,
      amount: "",
      currency: selectedAccount?.currency || "USD",
      description: "",
    })
    setIdempotencyKey(null)
    setErrors({})
    setSubmitError(null)
    setSuccessResult(null)
    executeWithdrawalMutation.reset()
    setStep("FORM")
    setTimeout(() => {
      amountInputRef.current?.focus()
    }, 50)
  }

  const numAmount = Number(values.amount)
  const isAmountValid = !isNaN(numAmount) && numAmount > 0
  const postWithdrawalBalance =
    selectedAccount && isAmountValid ? selectedAccount.balance - numAmount : null

  return (
    <>
      <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
        <div className="lg:col-span-7">
          {step === "FORM" && (
            <Section
              title={
                <h2
                  ref={stepHeadingRef}
                  tabIndex={-1}
                  className="text-[17px] font-semibold text-foreground font-sans tracking-tight outline-none"
                >
                  Withdrawal Details
                </h2>
              }
              description="Withdraw funds from a user checking account to platform clearing."
              badge={
                <span className="text-[11px] font-medium px-2 py-0.5 rounded-sm bg-muted/60 text-muted-foreground border border-border/60">
                  Step 1 of 2
                </span>
              }
            >
              {isAccountsLoading ? (
                <div className="py-8 text-center space-y-3">
                  <Loader2 className="size-6 animate-spin text-muted-foreground mx-auto" />
                  <p className="text-[13.5px] text-muted-foreground">
                    Loading checking accounts...
                  </p>
                </div>
              ) : isAccountsError ? (
                <div className="p-6 text-center space-y-3 rounded-sm border border-destructive/20 bg-destructive/5">
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
                <div className="p-8 text-center space-y-3">
                  <Landmark className="size-8 text-muted-foreground/40 mx-auto" />
                  <p className="text-[15px] font-medium text-foreground">
                    No checking accounts available
                  </p>
                  <p className="text-[13px] text-muted-foreground max-w-sm mx-auto">
                    You do not have any active checking accounts to withdraw from.
                  </p>
                  <Button
                    type="button"
                    size="sm"
                    onClick={() => setIsCreateAccountOpen(true)}
                    className="gap-1.5 mt-2"
                  >
                    <Plus className="size-3.5" />
                    <span>Create Checking Account</span>
                  </Button>
                </div>
              ) : (
                <form onSubmit={handleProceedToReview} className="space-y-4" noValidate>
                  {errors.general && (
                    <div
                      role="alert"
                      aria-live="polite"
                      className="p-3 text-[13px] rounded-sm bg-destructive/10 border border-destructive/20 text-destructive flex items-center gap-2"
                    >
                      <AlertCircle className="size-4 shrink-0" />
                      <span>{errors.general}</span>
                    </div>
                  )}

                  <div className="space-y-1.5">
                    <label
                      htmlFor="withdrawal-source-select"
                      className="text-[13.5px] font-medium text-foreground flex items-center justify-between"
                    >
                      <span>Source Account</span>
                      <span className="text-xs text-muted-foreground">Debited</span>
                    </label>
                    <select
                      id="withdrawal-source-select"
                      ref={accountSelectRef}
                      value={values.accountId}
                      onChange={handleAccountChange}
                      aria-invalid={Boolean(errors.accountId)}
                      aria-describedby={errors.accountId ? "withdrawal-account-error" : undefined}
                      className="flex h-9 w-full rounded-sm border border-input bg-background px-3 py-1.5 text-[13.5px] text-foreground focus-visible:outline-none focus-visible:border-ring focus-visible:ring-1 focus-visible:ring-ring disabled:cursor-not-allowed disabled:opacity-50 transition-colors shadow-2xs font-sans"
                    >
                      <option value="">Select source checking account...</option>
                      {checkingAccounts.map((account) => (
                        <option
                          key={account.accountId}
                          value={account.accountId}
                          disabled={account.status !== "ACTIVE"}
                        >
                          {account.accountNumber} ({account.currency} · Balance:{" "}
                          {account.balance.toLocaleString("en-US", {
                            minimumFractionDigits: 2,
                            maximumFractionDigits: 2,
                          })}
                          ){account.status !== "ACTIVE" ? ` — [${account.status}]` : ""}
                        </option>
                      ))}
                    </select>

                    {errors.accountId && (
                      <p
                        id="withdrawal-account-error"
                        role="alert"
                        className="text-xs text-destructive mt-1"
                      >
                        {errors.accountId}
                      </p>
                    )}

                    {selectedAccount && (
                      <div className="flex items-center justify-between pt-1 px-1 text-xs text-muted-foreground">
                        <span>Available Balance:</span>
                        <AmountDisplay
                          amount={selectedAccount.balance}
                          currency={selectedAccount.currency}
                          size="sm"
                        />
                      </div>
                    )}
                  </div>

                  <div className="grid grid-cols-3 gap-3">
                    <div className="col-span-2 space-y-1.5">
                      <label
                        htmlFor="withdrawal-amount-input"
                        className="text-[13.5px] font-medium text-foreground block"
                      >
                        Amount
                      </label>
                      <Input
                        id="withdrawal-amount-input"
                        ref={amountInputRef}
                        type="text"
                        inputMode="decimal"
                        value={values.amount}
                        onChange={handleAmountChange}
                        placeholder="0.00"
                        monospace
                        aria-invalid={Boolean(errors.amount)}
                        aria-describedby={errors.amount ? "withdrawal-amount-error" : undefined}
                      />
                      {errors.amount && (
                        <p
                          id="withdrawal-amount-error"
                          role="alert"
                          className="text-xs text-destructive mt-1"
                        >
                          {errors.amount}
                        </p>
                      )}
                    </div>

                    <div className="col-span-1 space-y-1.5">
                      <label
                        htmlFor="withdrawal-currency-display"
                        className="text-[13.5px] font-medium text-foreground block"
                      >
                        Currency
                      </label>
                      <Input
                        id="withdrawal-currency-display"
                        value={values.currency}
                        disabled
                        monospace
                        className="bg-muted/40 text-center font-mono font-semibold"
                        aria-label="Account Currency"
                      />
                      {errors.currency && (
                        <p role="alert" className="text-xs text-destructive mt-1">
                          {errors.currency}
                        </p>
                      )}
                    </div>
                  </div>

                  {selectedAccount && isAmountValid && (
                    <div className="p-2.5 rounded-sm bg-muted/20 border border-border/60 text-xs space-y-1">
                      <div className="flex justify-between items-baseline">
                        <span className="text-muted-foreground">Est. Source Balance After:</span>
                        <span
                          className={`font-mono font-medium ${
                            postWithdrawalBalance !== null && postWithdrawalBalance < 0
                              ? "text-destructive"
                              : "text-foreground"
                          }`}
                        >
                          {postWithdrawalBalance !== null
                            ? `${selectedAccount.currency} ${postWithdrawalBalance.toLocaleString(
                                "en-US",
                                { minimumFractionDigits: 2, maximumFractionDigits: 2 }
                              )}`
                            : "—"}
                        </span>
                      </div>
                      <p className="text-[11px] text-muted-foreground/75 leading-tight">
                        Client-side preview only. The ledger engine is the authoritative source of truth upon execution.
                      </p>
                    </div>
                  )}

                  <div className="space-y-1.5">
                    <div className="flex items-center justify-between">
                      <label
                        htmlFor="withdrawal-description-input"
                        className="text-[13.5px] font-medium text-foreground block"
                      >
                        Description / Note
                      </label>
                      <span className="text-xs text-muted-foreground">
                        {values.description.length}/255
                      </span>
                    </div>
                    <Input
                      id="withdrawal-description-input"
                      type="text"
                      maxLength={255}
                      value={values.description}
                      onChange={handleDescriptionChange}
                      placeholder="Optional reference memo or withdrawal reason..."
                      aria-invalid={Boolean(errors.description)}
                      aria-describedby={
                        errors.description ? "withdrawal-description-error" : undefined
                      }
                    />
                    {errors.description && (
                      <p
                        id="withdrawal-description-error"
                        role="alert"
                        className="text-xs text-destructive mt-1"
                      >
                        {errors.description}
                      </p>
                    )}
                  </div>

                  <div className="pt-2">
                    <Button
                      type="submit"
                      className="w-full h-8.5 gap-2 justify-center font-medium text-[13.5px]"
                    >
                      <span>Review Withdrawal</span>
                      <ArrowRight className="size-3.5" />
                    </Button>
                  </div>
                </form>
              )}
            </Section>
          )}

          {step === "REVIEW" && (
            <Section
              title={
                <h2
                  ref={stepHeadingRef}
                  tabIndex={-1}
                  className="text-[17px] font-semibold text-foreground font-sans tracking-tight outline-none"
                >
                  Review & Confirm Withdrawal
                </h2>
              }
              description="Verify all transaction parameters before committing to the ledger."
              badge={
                <span className="text-[11px] font-medium px-2 py-0.5 rounded-sm bg-primary/10 text-primary border border-primary/20">
                  Step 2 of 2
                </span>
              }
            >
              <div className="space-y-4">
                {submitError && (
                  <div
                    role="alert"
                    aria-live="polite"
                    className="p-3 text-[13px] rounded-sm bg-destructive/10 border border-destructive/20 text-destructive space-y-1"
                  >
                    <div className="flex items-center gap-2 font-medium">
                      <AlertCircle className="size-4 shrink-0" />
                      <span>Withdrawal Execution Failed</span>
                    </div>
                    <p className="text-xs text-destructive/90 pl-6 leading-relaxed">
                      {submitError}
                    </p>
                  </div>
                )}

                <div className="rounded-sm border border-border/70 overflow-hidden divide-y divide-border/60 bg-card">
                  <div className="p-3.5 space-y-1">
                    <div className="flex items-center justify-between text-xs text-muted-foreground">
                      <span>Source Account (Debited)</span>
                      <StatusBadge status={selectedAccount?.status || "ACTIVE"} />
                    </div>
                    <div className="flex items-baseline justify-between pt-1">
                      <span className="font-mono text-[14.5px] font-semibold text-foreground">
                        {selectedAccount?.accountNumber}
                      </span>
                      <AmountDisplay
                        amount={selectedAccount?.balance ?? 0}
                        currency={selectedAccount?.currency ?? values.currency}
                        size="sm"
                      />
                    </div>
                    <span className="font-mono text-[11.5px] text-muted-foreground block truncate">
                      {selectedAccount?.accountId}
                    </span>
                  </div>

                  <div className="p-3.5 flex items-center justify-between bg-muted/20">
                    <span className="text-[13.5px] font-medium text-foreground">
                      Withdrawal Amount
                    </span>
                    <AmountDisplay
                      amount={values.amount}
                      currency={values.currency}
                      size="lg"
                    />
                  </div>

                  {values.description.trim() && (
                    <div className="p-3.5 flex items-start justify-between gap-4">
                      <span className="text-xs text-muted-foreground">Description</span>
                      <span className="text-[13px] text-foreground font-sans text-right max-w-xs break-words">
                        {values.description}
                      </span>
                    </div>
                  )}
                </div>

                <div className="p-3 rounded-sm border border-border/60 bg-muted/20 text-xs text-muted-foreground space-y-1">
                  <div className="flex items-center gap-1.5 font-medium text-foreground">
                    <ShieldCheck className="size-3.5 text-primary" />
                    <span>Authoritative Execution</span>
                  </div>
                  <p className="leading-relaxed text-[12px]">
                    Once confirmed, withdrawal funds will be debited from your checking account atomically through verified double-entry journal entries.
                  </p>
                </div>

                <div className="flex items-center gap-3 pt-2">
                  <Button
                    type="button"
                    variant="outline"
                    onClick={handleBackToEdit}
                    disabled={executeWithdrawalMutation.isPending}
                    className="flex-1 h-8.5 gap-1.5 justify-center text-[13.5px]"
                  >
                    <ArrowLeft className="size-3.5" />
                    <span>Back to Edit</span>
                  </Button>

                  <Button
                    type="button"
                    ref={confirmButtonRef}
                    onClick={handleExecuteWithdrawal}
                    disabled={executeWithdrawalMutation.isPending}
                    className="flex-1 h-8.5 gap-2 justify-center text-[13.5px] font-medium"
                  >
                    {executeWithdrawalMutation.isPending ? (
                      <>
                        <Loader2 className="size-3.5 animate-spin" />
                        <span>Executing Withdrawal...</span>
                      </>
                    ) : submitError ? (
                      <>
                        <RotateCw className="size-3.5" />
                        <span>Retry Withdrawal</span>
                      </>
                    ) : (
                      <>
                        <ArrowUpRight className="size-3.5" />
                        <span>Confirm & Withdraw</span>
                      </>
                    )}
                  </Button>
                </div>
              </div>
            </Section>
          )}

          {step === "SUCCESS" && successResult && (
            <Section
              title={
                <h2
                  ref={stepHeadingRef}
                  tabIndex={-1}
                  className="text-[17px] font-semibold text-foreground font-sans tracking-tight flex items-center gap-2 outline-none"
                >
                  <CheckCircle2 className="size-5 text-emerald-600 dark:text-emerald-400" />
                  <span>Withdrawal Completed</span>
                </h2>
              }
              description="Funds have been withdrawn atomically and recorded in the double-entry journal."
              badge={<StatusBadge status={successResult.status} />}
            >
              <div className="space-y-4">
                <div className="rounded-sm border border-emerald-500/20 bg-emerald-500/5 p-4 text-center space-y-1">
                  <span className="text-xs text-emerald-700 dark:text-emerald-400 font-medium uppercase tracking-wider">
                    Amount Withdrawn
                  </span>
                  <div>
                    <AmountDisplay
                      amount={successResult.amount}
                      currency={successResult.currency}
                      size="xl"
                      direction="debit"
                    />
                  </div>
                  <span className="text-xs text-muted-foreground font-mono">
                    Status: {successResult.status}
                  </span>
                </div>

                <div className="rounded-sm border border-border/70 divide-y divide-border/50 bg-card">
                  <DataRow
                    label="Transaction ID"
                    value={successResult.transactionId}
                    monospace
                  />
                  <DataRow
                    label="Source Account"
                    value={
                      selectedAccount
                        ? `${selectedAccount.accountNumber} (${successResult.sourceAccountId})`
                        : successResult.sourceAccountId
                    }
                    monospace
                  />
                  <DataRow
                    label="Timestamp"
                    value={formatDate(successResult.completedAt || successResult.createdAt)}
                  />
                  {successResult.description && (
                    <DataRow
                      label="Description"
                      value={successResult.description}
                    />
                  )}
                  <DataRow
                    label="Accounting Guarantee"
                    value="Balanced Double-Entry (DEBIT == CREDIT)"
                  />
                </div>

                <div className="flex items-center gap-3 pt-2">
                  <Link href={ROUTES.ACCOUNTS} className="flex-1">
                    <Button
                      type="button"
                      variant="outline"
                      className="w-full h-8.5 justify-center text-[13.5px]"
                    >
                      <span>View Accounts</span>
                    </Button>
                  </Link>

                  <Button
                    type="button"
                    onClick={handleStartAnotherWithdrawal}
                    className="flex-1 h-8.5 gap-1.5 justify-center text-[13.5px] font-medium"
                  >
                    <Plus className="size-3.5" />
                    <span>Start Another Withdrawal</span>
                  </Button>
                </div>
              </div>
            </Section>
          )}
        </div>

        <div className="lg:col-span-5 space-y-6">
          {selectedAccount && (
            <Section title="Source Account State" variant="bordered">
              <div className="space-y-2">
                <div className="flex items-center justify-between pb-2 border-b border-border/60">
                  <span className="font-mono text-sm font-semibold text-foreground">
                    {selectedAccount.accountNumber}
                  </span>
                  <StatusBadge status={selectedAccount.status} />
                </div>
                <div className="space-y-1">
                  <DataRow
                    label="Currency"
                    value={selectedAccount.currency}
                    monospace
                  />
                  <DataRow
                    label="Available Balance"
                    value={
                      <AmountDisplay
                        amount={selectedAccount.balance}
                        currency={selectedAccount.currency}
                        size="sm"
                      />
                    }
                  />
                  <DataRow
                    label="Account Type"
                    value="Checking"
                  />
                  <DataRow
                    label="Account UUID"
                    value={selectedAccount.accountId}
                    monospace
                  />
                </div>
              </div>
            </Section>
          )}

          <Section title="Transaction Invariants">
            <div className="space-y-1">
              <DataRow label="Transaction Atomicity" value="All-or-nothing (ACID)" />
              <DataRow label="Destination Entity" value="Platform clearing account" />
              <DataRow label="Lock Sequencing" value="Deterministic by account UUID" />
              <DataRow label="Idempotency Cache" value="Redis fast-path + database constraint" />
              <DataRow label="Balance Check" value="Source balance must cover amount" />
            </div>
          </Section>

          <Section title="Transaction Integrity" variant="subtle">
            <p className="text-[13px] text-muted-foreground leading-relaxed font-sans">
              Withdrawals debit your checking account and credit the system clearing account in a single atomic transaction. Ledger entries are permanently immutable once committed.
            </p>
          </Section>
        </div>
      </div>

      <CreateAccountDialog
        open={isCreateAccountOpen}
        onOpenChange={setIsCreateAccountOpen}
        onSuccess={(newAccountId) => {
          refetchAccounts()
          setValues((prev) => ({
            ...prev,
            accountId: newAccountId,
          }))
        }}
      />
    </>
  )
}
