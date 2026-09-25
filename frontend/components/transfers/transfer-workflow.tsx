"use client"

import * as React from "react"
import Link from "next/link"
import {
  ArrowLeftRight,
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
import { useExecuteTransfer } from "@/hooks/api/use-transfers"
import {
  validateTransferForm,
  getTransferErrorMessage,
  type TransferFormValues,
} from "@/lib/validators/transfer"
import { formatDate } from "@/lib/formatters/date"
import { ROUTES } from "@/constants/routes"
import type { TransferResponse } from "@/types/transaction"
import type { Account } from "@/types/account"

type WorkflowStep = "FORM" | "REVIEW" | "SUCCESS"

export function TransferWorkflow() {
  const [step, setStep] = React.useState<WorkflowStep>("FORM")
  const [isCreateAccountOpen, setIsCreateAccountOpen] = React.useState(false)

  // Form state
  const [values, setValues] = React.useState<TransferFormValues>({
    sourceAccountId: "",
    destinationAccountId: "",
    amount: "",
    currency: "USD",
    description: "",
  })

  // Idempotency state: associated with the logical transfer submission
  const [idempotencyKey, setIdempotencyKey] = React.useState<string | null>(null)

  // Validation and API error states
  const [errors, setErrors] = React.useState<Record<string, string>>({})
  const [submitError, setSubmitError] = React.useState<string | null>(null)
  const [successResult, setSuccessResult] = React.useState<TransferResponse | null>(null)

  // Focus management refs for accessibility
  const stepHeadingRef = React.useRef<HTMLHeadingElement>(null)
  const sourceSelectRef = React.useRef<HTMLSelectElement>(null)
  const amountInputRef = React.useRef<HTMLInputElement>(null)
  const confirmButtonRef = React.useRef<HTMLButtonElement>(null)

  // Queries & Mutations
  const {
    data: allAccounts,
    isLoading: isAccountsLoading,
    isError: isAccountsError,
    error: accountsError,
    refetch: refetchAccounts,
  } = useAccounts()

  const executeTransferMutation = useExecuteTransfer()

  // Filter to checking accounts owned by authenticated user
  const checkingAccounts = React.useMemo(() => {
    if (!allAccounts) return []
    return allAccounts.filter((acc) => acc.accountType === "USER_CHECKING")
  }, [allAccounts])

  // Look up selected account objects
  const selectedSourceAccount = React.useMemo<Account | undefined>(() => {
    return checkingAccounts.find((a) => a.accountId === values.sourceAccountId)
  }, [checkingAccounts, values.sourceAccountId])

  const selectedDestinationAccount = React.useMemo<Account | undefined>(() => {
    return checkingAccounts.find((a) => a.accountId === values.destinationAccountId)
  }, [checkingAccounts, values.destinationAccountId])

  // When source account is selected, automatically synchronize currency
  const handleSourceAccountChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    const newSourceId = e.target.value
    const matchedAccount = checkingAccounts.find((a) => a.accountId === newSourceId)
    setValues((prev) => ({
      ...prev,
      sourceAccountId: newSourceId,
      currency: matchedAccount ? matchedAccount.currency : prev.currency,
      // Clear destination if same was selected
      destinationAccountId:
        prev.destinationAccountId === newSourceId ? "" : prev.destinationAccountId,
    }))
    // Clear error for field
    if (errors.sourceAccountId) {
      setErrors((prev) => {
        const next = { ...prev }
        delete next.sourceAccountId
        return next
      })
    }
  }

  const handleDestinationAccountChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    const newDestId = e.target.value
    setValues((prev) => ({
      ...prev,
      destinationAccountId: newDestId,
    }))
    if (errors.destinationAccountId) {
      setErrors((prev) => {
        const next = { ...prev }
        delete next.destinationAccountId
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

  // Step 1 -> Step 2: Validate and advance to Review
  const handleProceedToReview = (e: React.FormEvent) => {
    e.preventDefault()
    setSubmitError(null)

    const validation = validateTransferForm(
      values,
      selectedSourceAccount,
      selectedDestinationAccount
    )

    if (!validation.isValid) {
      setErrors(validation.errors as Record<string, string>)
      // Accessibility: focus the first invalid field
      if (validation.errors.sourceAccountId) {
        sourceSelectRef.current?.focus()
      } else if (validation.errors.amount) {
        amountInputRef.current?.focus()
      }
      return
    }

    setErrors({})

    // Assign a unique idempotency key for this logical transfer submission
    if (!idempotencyKey) {
      const generatedKey =
        typeof crypto !== "undefined" && crypto.randomUUID
          ? crypto.randomUUID()
          : `transfer-${Date.now()}-${Math.random().toString(36).substring(2, 10)}`
      setIdempotencyKey(generatedKey)
    }

    setStep("REVIEW")
    setTimeout(() => {
      stepHeadingRef.current?.focus()
    }, 50)
  }

  // Step 2 -> Step 1: Return to Edit
  const handleBackToEdit = () => {
    setStep("FORM")
    setSubmitError(null)
    // Invalidate the idempotency key so edits will trigger a fresh submission key
    setIdempotencyKey(null)
    setTimeout(() => {
      amountInputRef.current?.focus()
    }, 50)
  }

  // Step 2 -> Execute Transfer
  const handleExecuteTransfer = async () => {
    if (!idempotencyKey) return

    setSubmitError(null)
    try {
      const result = await executeTransferMutation.mutateAsync({
        request: {
          sourceAccountId: values.sourceAccountId,
          destinationAccountId: values.destinationAccountId,
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
      const friendlyMessage = getTransferErrorMessage(err)
      setSubmitError(friendlyMessage)
    }
  }

  // Step 3 -> Reset for a new transfer
  const handleStartAnotherTransfer = () => {
    setValues({
      sourceAccountId: values.sourceAccountId, // Keep source account selected for convenience
      destinationAccountId: "",
      amount: "",
      currency: selectedSourceAccount?.currency || "USD",
      description: "",
    })
    setIdempotencyKey(null)
    setErrors({})
    setSubmitError(null)
    setSuccessResult(null)
    executeTransferMutation.reset()
    setStep("FORM")
    setTimeout(() => {
      amountInputRef.current?.focus()
    }, 50)
  }

  // Available destination accounts: owned checking accounts except the chosen source
  const availableDestinationAccounts = React.useMemo(() => {
    return checkingAccounts.filter((a) => a.accountId !== values.sourceAccountId)
  }, [checkingAccounts, values.sourceAccountId])

  // Parse amount for client balance preview
  const numAmount = Number(values.amount)
  const isAmountValid = !isNaN(numAmount) && numAmount > 0
  const postTransferSourceBalance =
    selectedSourceAccount && isAmountValid
      ? selectedSourceAccount.balance - numAmount
      : null

  return (
    <>
      <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
        {/* Main Workflow Column */}
        <div className="lg:col-span-7">
          {/* STEP 1: FORM */}
          {step === "FORM" && (
            <Section
              title={
                <h2
                  ref={stepHeadingRef}
                  tabIndex={-1}
                  className="text-[17px] font-semibold text-foreground font-sans tracking-tight outline-none"
                >
                  Transfer Details
                </h2>
              }
              description="Initiate an atomic double-entry balance transfer between accounts."
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
                    You need at least two checking accounts in the ledger to execute transfers.
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
              ) : checkingAccounts.length === 1 ? (
                <div className="p-6 space-y-4 rounded-sm border border-border/80 bg-muted/20">
                  <div className="flex items-start gap-3">
                    <AlertCircle className="size-5 text-amber-600 dark:text-amber-400 shrink-0 mt-0.5" />
                    <div className="space-y-1">
                      <p className="text-[14px] font-medium text-foreground">
                        Additional Account Required
                      </p>
                      <p className="text-[13px] text-muted-foreground leading-relaxed">
                        Transfers require at least two checking accounts. You currently have 1 account provisioned in the ledger.
                      </p>
                    </div>
                  </div>
                  <Button
                    type="button"
                    size="sm"
                    onClick={() => setIsCreateAccountOpen(true)}
                    className="gap-1.5"
                  >
                    <Plus className="size-3.5" />
                    <span>Create Second Account</span>
                  </Button>
                </div>
              ) : (
                <form onSubmit={handleProceedToReview} className="space-y-4" noValidate>
                  {/* General error banner if present */}
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

                  {/* Source Account Selector */}
                  <div className="space-y-1.5">
                    <label
                      htmlFor="source-account-select"
                      className="text-[13.5px] font-medium text-foreground flex items-center justify-between"
                    >
                      <span>Source Account</span>
                      <span className="text-xs text-muted-foreground">Debited</span>
                    </label>
                    <select
                      id="source-account-select"
                      ref={sourceSelectRef}
                      value={values.sourceAccountId}
                      onChange={handleSourceAccountChange}
                      aria-invalid={Boolean(errors.sourceAccountId)}
                      aria-describedby={
                        errors.sourceAccountId ? "source-account-error" : undefined
                      }
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

                    {errors.sourceAccountId && (
                      <p
                        id="source-account-error"
                        role="alert"
                        className="text-xs text-destructive mt-1"
                      >
                        {errors.sourceAccountId}
                      </p>
                    )}

                    {selectedSourceAccount && (
                      <div className="flex items-center justify-between pt-1 px-1 text-xs text-muted-foreground">
                        <span>Available Balance:</span>
                        <AmountDisplay
                          amount={selectedSourceAccount.balance}
                          currency={selectedSourceAccount.currency}
                          size="sm"
                        />
                      </div>
                    )}
                  </div>

                  {/* Destination Account Selector */}
                  <div className="space-y-1.5">
                    <label
                      htmlFor="destination-account-select"
                      className="text-[13.5px] font-medium text-foreground flex items-center justify-between"
                    >
                      <span>Destination Account</span>
                      <span className="text-xs text-muted-foreground">Credited</span>
                    </label>
                    <select
                      id="destination-account-select"
                      value={values.destinationAccountId}
                      onChange={handleDestinationAccountChange}
                      disabled={!values.sourceAccountId}
                      aria-invalid={Boolean(errors.destinationAccountId)}
                      aria-describedby={
                        errors.destinationAccountId ? "destination-account-error" : undefined
                      }
                      className="flex h-9 w-full rounded-sm border border-input bg-background px-3 py-1.5 text-[13.5px] text-foreground focus-visible:outline-none focus-visible:border-ring focus-visible:ring-1 focus-visible:ring-ring disabled:cursor-not-allowed disabled:opacity-50 transition-colors shadow-2xs font-sans"
                    >
                      <option value="">
                        {!values.sourceAccountId
                          ? "Select a source account first..."
                          : "Select destination checking account..."}
                      </option>
                      {availableDestinationAccounts.map((account) => (
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

                    {errors.destinationAccountId && (
                      <p
                        id="destination-account-error"
                        role="alert"
                        className="text-xs text-destructive mt-1"
                      >
                        {errors.destinationAccountId}
                      </p>
                    )}
                  </div>

                  {/* Amount and Currency */}
                  <div className="grid grid-cols-3 gap-3">
                    <div className="col-span-2 space-y-1.5">
                      <label
                        htmlFor="transfer-amount-input"
                        className="text-[13.5px] font-medium text-foreground block"
                      >
                        Amount
                      </label>
                      <Input
                        id="transfer-amount-input"
                        ref={amountInputRef}
                        type="text"
                        inputMode="decimal"
                        value={values.amount}
                        onChange={handleAmountChange}
                        placeholder="0.00"
                        monospace
                        aria-invalid={Boolean(errors.amount)}
                        aria-describedby={errors.amount ? "transfer-amount-error" : undefined}
                      />
                      {errors.amount && (
                        <p
                          id="transfer-amount-error"
                          role="alert"
                          className="text-xs text-destructive mt-1"
                        >
                          {errors.amount}
                        </p>
                      )}
                    </div>

                    <div className="col-span-1 space-y-1.5">
                      <label
                        htmlFor="transfer-currency-display"
                        className="text-[13.5px] font-medium text-foreground block"
                      >
                        Currency
                      </label>
                      <Input
                        id="transfer-currency-display"
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

                  {/* Client balance impact preview (Non-authoritative) */}
                  {selectedSourceAccount && isAmountValid && (
                    <div className="p-2.5 rounded-sm bg-muted/20 border border-border/60 text-xs space-y-1">
                      <div className="flex justify-between items-baseline">
                        <span className="text-muted-foreground">Est. Source Balance After:</span>
                        <span
                          className={`font-mono font-medium ${
                            postTransferSourceBalance !== null && postTransferSourceBalance < 0
                              ? "text-destructive"
                              : "text-foreground"
                          }`}
                        >
                          {postTransferSourceBalance !== null
                            ? `${selectedSourceAccount.currency} ${postTransferSourceBalance.toLocaleString(
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

                  {/* Optional Description */}
                  <div className="space-y-1.5">
                    <div className="flex items-center justify-between">
                      <label
                        htmlFor="transfer-description-input"
                        className="text-[13.5px] font-medium text-foreground block"
                      >
                        Description / Note
                      </label>
                      <span className="text-xs text-muted-foreground">
                        {values.description.length}/255
                      </span>
                    </div>
                    <Input
                      id="transfer-description-input"
                      type="text"
                      maxLength={255}
                      value={values.description}
                      onChange={handleDescriptionChange}
                      placeholder="Optional reference memo or payment invoice..."
                      aria-invalid={Boolean(errors.description)}
                      aria-describedby={
                        errors.description ? "transfer-description-error" : undefined
                      }
                    />
                    {errors.description && (
                      <p
                        id="transfer-description-error"
                        role="alert"
                        className="text-xs text-destructive mt-1"
                      >
                        {errors.description}
                      </p>
                    )}
                  </div>

                  {/* Action */}
                  <div className="pt-2">
                    <Button
                      type="submit"
                      className="w-full h-8.5 gap-2 justify-center font-medium text-[13.5px]"
                    >
                      <span>Review Transfer</span>
                      <ArrowRight className="size-3.5" />
                    </Button>
                  </div>
                </form>
              )}
            </Section>
          )}

          {/* STEP 2: REVIEW & CONFIRM */}
          {step === "REVIEW" && (
            <Section
              title={
                <h2
                  ref={stepHeadingRef}
                  tabIndex={-1}
                  className="text-[17px] font-semibold text-foreground font-sans tracking-tight outline-none"
                >
                  Review & Confirm Transfer
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
                {/* Submit Error Banner */}
                {submitError && (
                  <div
                    role="alert"
                    aria-live="polite"
                    className="p-3 text-[13px] rounded-sm bg-destructive/10 border border-destructive/20 text-destructive space-y-1"
                  >
                    <div className="flex items-center gap-2 font-medium">
                      <AlertCircle className="size-4 shrink-0" />
                      <span>Transfer Execution Failed</span>
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
                      <StatusBadge status={selectedSourceAccount?.status || "ACTIVE"} />
                    </div>
                    <div className="flex items-baseline justify-between pt-1">
                      <span className="font-mono text-[14.5px] font-semibold text-foreground">
                        {selectedSourceAccount?.accountNumber}
                      </span>
                      <AmountDisplay
                        amount={selectedSourceAccount?.balance ?? 0}
                        currency={selectedSourceAccount?.currency ?? values.currency}
                        size="sm"
                      />
                    </div>
                    <span className="font-mono text-[11.5px] text-muted-foreground block truncate">
                      {selectedSourceAccount?.accountId}
                    </span>
                  </div>

                  <div className="p-3.5 space-y-1">
                    <div className="flex items-center justify-between text-xs text-muted-foreground">
                      <span>Destination Account (Credited)</span>
                      <StatusBadge status={selectedDestinationAccount?.status || "ACTIVE"} />
                    </div>
                    <div className="flex items-baseline justify-between pt-1">
                      <span className="font-mono text-[14.5px] font-semibold text-foreground">
                        {selectedDestinationAccount?.accountNumber}
                      </span>
                      <AmountDisplay
                        amount={selectedDestinationAccount?.balance ?? 0}
                        currency={selectedDestinationAccount?.currency ?? values.currency}
                        size="sm"
                      />
                    </div>
                    <span className="font-mono text-[11.5px] text-muted-foreground block truncate">
                      {selectedDestinationAccount?.accountId}
                    </span>
                  </div>

                  <div className="p-3.5 flex items-center justify-between bg-muted/20">
                    <span className="text-[13.5px] font-medium text-foreground">
                      Transfer Amount
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
                    Once confirmed, balanced double-entry DEBIT and CREDIT journal entries will be committed atomically to PostgreSQL.
                  </p>
                </div>

                {/* Navigation actions */}
                <div className="flex items-center gap-3 pt-2">
                  <Button
                    type="button"
                    variant="outline"
                    onClick={handleBackToEdit}
                    disabled={executeTransferMutation.isPending}
                    className="flex-1 h-8.5 gap-1.5 justify-center text-[13.5px]"
                  >
                    <ArrowLeft className="size-3.5" />
                    <span>Back to Edit</span>
                  </Button>

                  <Button
                    type="button"
                    ref={confirmButtonRef}
                    onClick={handleExecuteTransfer}
                    disabled={executeTransferMutation.isPending}
                    className="flex-1 h-8.5 gap-2 justify-center text-[13.5px] font-medium"
                  >
                    {executeTransferMutation.isPending ? (
                      <>
                        <Loader2 className="size-3.5 animate-spin" />
                        <span>Executing Transfer...</span>
                      </>
                    ) : submitError ? (
                      <>
                        <RotateCw className="size-3.5" />
                        <span>Retry Transfer</span>
                      </>
                    ) : (
                      <>
                        <ArrowLeftRight className="size-3.5" />
                        <span>Confirm & Execute</span>
                      </>
                    )}
                  </Button>
                </div>
              </div>
            </Section>
          )}

          {/* STEP 3: SUCCESS RECEIPT */}
          {step === "SUCCESS" && successResult && (
            <Section
              title={
                <h2
                  ref={stepHeadingRef}
                  tabIndex={-1}
                  className="text-[17px] font-semibold text-foreground font-sans tracking-tight flex items-center gap-2 outline-none"
                >
                  <CheckCircle2 className="size-5 text-emerald-600 dark:text-emerald-400" />
                  <span>Transfer Completed</span>
                </h2>
              }
              description="The transfer has been executed atomically and recorded in the double-entry journal."
              badge={<StatusBadge status={successResult.status} />}
            >
              <div className="space-y-4">
                <div className="rounded-sm border border-emerald-500/20 bg-emerald-500/5 p-4 text-center space-y-1">
                  <span className="text-xs text-emerald-700 dark:text-emerald-400 font-medium uppercase tracking-wider">
                    Amount Transferred
                  </span>
                  <div>
                    <AmountDisplay
                      amount={successResult.amount}
                      currency={successResult.currency}
                      size="xl"
                      direction="neutral"
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
                      selectedSourceAccount
                        ? `${selectedSourceAccount.accountNumber} (${successResult.sourceAccountId})`
                        : successResult.sourceAccountId
                    }
                    monospace
                  />
                  <DataRow
                    label="Destination Account"
                    value={
                      selectedDestinationAccount
                        ? `${selectedDestinationAccount.accountNumber} (${successResult.destinationAccountId})`
                        : successResult.destinationAccountId
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
                    onClick={handleStartAnotherTransfer}
                    className="flex-1 h-8.5 gap-1.5 justify-center text-[13.5px] font-medium"
                  >
                    <Plus className="size-3.5" />
                    <span>Start Another Transfer</span>
                  </Button>
                </div>
              </div>
            </Section>
          )}
        </div>

        {/* Sidebar Information Column */}
        <div className="lg:col-span-5 space-y-6">
          {/* Selected Account Overview */}
          {selectedSourceAccount && (
            <Section title="Source Account State" variant="bordered">
              <div className="space-y-2">
                <div className="flex items-center justify-between pb-2 border-b border-border/60">
                  <span className="font-mono text-sm font-semibold text-foreground">
                    {selectedSourceAccount.accountNumber}
                  </span>
                  <StatusBadge status={selectedSourceAccount.status} />
                </div>
                <div className="space-y-1">
                  <DataRow
                    label="Currency"
                    value={selectedSourceAccount.currency}
                    monospace
                  />
                  <DataRow
                    label="Available Balance"
                    value={
                      <AmountDisplay
                        amount={selectedSourceAccount.balance}
                        currency={selectedSourceAccount.currency}
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
                    value={selectedSourceAccount.accountId}
                    monospace
                  />
                </div>
              </div>
            </Section>
          )}

          <Section title="Transaction Invariants">
            <div className="space-y-1">
              <DataRow label="Transaction Atomicity" value="All-or-nothing (ACID)" />
              <DataRow label="Lock Sequencing" value="Deterministic by account UUID" />
              <DataRow label="Idempotency Cache" value="Redis fast-path + database constraint" />
              <DataRow label="Currency Validation" value="Strict ISO currency match" />
              <DataRow label="Balance Check" value="Source balance must cover amount" />
            </div>
          </Section>

          <Section title="Transaction Integrity" variant="subtle">
            <p className="text-[13px] text-muted-foreground leading-relaxed font-sans">
              Transfers acquire pessimistic row-level locks in deterministic account order to prevent concurrency deadlocks. Every transaction records an equal debit and credit journal entry.
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
            destinationAccountId: prev.sourceAccountId ? newAccountId : prev.destinationAccountId,
            sourceAccountId: prev.sourceAccountId ? prev.sourceAccountId : newAccountId,
          }))
        }}
      />
    </>
  )
}
