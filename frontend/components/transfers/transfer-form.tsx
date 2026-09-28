"use client"

import * as React from "react"
import { Loader2, ArrowLeftRight, ArrowDownUp } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { AccountSelector } from "@/components/transfers/account-selector"
import { TransferPreview } from "@/components/transfers/transfer-preview"
import { TransferError } from "@/components/transfers/transfer-error"
import {
  validateTransferForm,
  type TransferFormValues,
} from "@/lib/validators/transfer"
import { useExecuteTransfer } from "@/hooks/api/use-transfers"
import type { Account } from "@/types/account"
import type { TransferResponse } from "@/types/transaction"

interface TransferFormProps {
  accounts: Account[]
  initialSourceAccountId?: string
  onSuccess: (result: TransferResponse, sourceAccount?: Account, destinationAccount?: Account) => void
}

export function TransferForm({
  accounts,
  initialSourceAccountId,
  onSuccess,
}: TransferFormProps) {
  // Filter user checking accounts
  const checkingAccounts = React.useMemo(() => {
    return accounts.filter((a) => a.accountType === "USER_CHECKING")
  }, [accounts])

  // Form values
  const [values, setValues] = React.useState<TransferFormValues>({
    sourceAccountId: initialSourceAccountId || (checkingAccounts[0]?.accountId ?? ""),
    destinationAccountId: checkingAccounts.length > 1 ? (checkingAccounts[1]?.accountId ?? "") : "",
    amount: "",
    currency: "INR", // INR-only platform
    description: "",
  })

  // Idempotency key for logical transfer
  const [idempotencyKey, setIdempotencyKey] = React.useState<string | null>(null)

  // Validation errors
  const [errors, setErrors] = React.useState<Record<string, string>>({})
  const [hasAttemptedSubmit, setHasAttemptedSubmit] = React.useState(false)

  // Execution mutation
  const executeTransferMutation = useExecuteTransfer()

  // Resolved accounts
  const selectedSourceAccount = React.useMemo(() => {
    return checkingAccounts.find((a) => a.accountId === values.sourceAccountId)
  }, [checkingAccounts, values.sourceAccountId])

  const selectedDestinationAccount = React.useMemo(() => {
    return checkingAccounts.find((a) => a.accountId === values.destinationAccountId)
  }, [checkingAccounts, values.destinationAccountId])

  // Refs for accessibility
  const sourceSelectRef = React.useRef<HTMLSelectElement>(null)
  const destSelectRef = React.useRef<HTMLSelectElement>(null)
  const amountInputRef = React.useRef<HTMLInputElement>(null)

  // Invalidate idempotency key whenever transfer parameters change
  const invalidateKeyAndErrors = (field?: string) => {
    setIdempotencyKey(null)
    executeTransferMutation.reset()
    if (field && errors[field]) {
      setErrors((prev) => {
        const next = { ...prev }
        delete next[field]
        return next
      })
    }
  }

  // Handlers
  const handleSourceChange = (newSourceId: string) => {
    setValues((prev) => ({
      ...prev,
      sourceAccountId: newSourceId,
    }))
    invalidateKeyAndErrors("sourceAccountId")
  }

  const handleDestinationChange = (newDestId: string) => {
    setValues((prev) => ({
      ...prev,
      destinationAccountId: newDestId,
    }))
    invalidateKeyAndErrors("destinationAccountId")
  }

  const handleSwapAccounts = () => {
    if (!values.sourceAccountId || !values.destinationAccountId) return
    setValues((prev) => ({
      ...prev,
      sourceAccountId: prev.destinationAccountId,
      destinationAccountId: prev.sourceAccountId,
    }))
    invalidateKeyAndErrors()
  }

  const handleAmountChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const raw = e.target.value
    // Restrict input characters to digits and single decimal point
    if (raw && !/^\d*\.?\d*$/.test(raw)) {
      return
    }
    setValues((prev) => ({ ...prev, amount: raw }))
    invalidateKeyAndErrors("amount")
  }

  const handleDescriptionChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    setValues((prev) => ({ ...prev, description: e.target.value }))
    invalidateKeyAndErrors("description")
  }

  // Generate or retrieve current idempotency key
  const getOrCreateIdempotencyKey = () => {
    if (idempotencyKey) {
      return idempotencyKey
    }
    const newKey =
      typeof crypto !== "undefined" && crypto.randomUUID
        ? crypto.randomUUID()
        : `transfer-${Date.now()}-${Math.random().toString(36).substring(2, 9)}`
    setIdempotencyKey(newKey)
    return newKey
  }

  // Form submission execution
  const handleSubmit = async (e?: React.FormEvent) => {
    if (e) {
      e.preventDefault()
    }
    setHasAttemptedSubmit(true)

    // Validate
    const validation = validateTransferForm(
      values,
      selectedSourceAccount,
      selectedDestinationAccount
    )

    if (!validation.isValid) {
      setErrors(validation.errors as Record<string, string>)
      if (validation.errors.sourceAccountId) {
        sourceSelectRef.current?.focus()
      } else if (validation.errors.destinationAccountId) {
        destSelectRef.current?.focus()
      } else if (validation.errors.amount) {
        amountInputRef.current?.focus()
      }
      return
    }

    setErrors({})

    // Assign or maintain logical idempotency key
    const activeKey = getOrCreateIdempotencyKey()

    try {
      const response = await executeTransferMutation.mutateAsync({
        request: {
          sourceAccountId: values.sourceAccountId,
          destinationAccountId: values.destinationAccountId,
          amount: parseFloat(values.amount),
          currency: "INR",
          description: values.description.trim() ? values.description.trim() : undefined,
        },
        idempotencyKey: activeKey,
      })

      onSuccess(response, selectedSourceAccount, selectedDestinationAccount)
    } catch {
      // Error is stored in executeTransferMutation.error and rendered via TransferError
    }
  }

  // Retry with identical idempotency key
  const handleSafeRetry = () => {
    if (!idempotencyKey) return
    handleSubmit()
  }

  const isPending = executeTransferMutation.isPending
  const isFormValid =
    Boolean(values.sourceAccountId) &&
    Boolean(values.destinationAccountId) &&
    values.sourceAccountId !== values.destinationAccountId &&
    Boolean(values.amount) &&
    /^\d+(\.\d{1,4})?$/.test(values.amount) &&
    parseFloat(values.amount) > 0

  const formattedTransferAmount = isFormValid
    ? `₹${parseFloat(values.amount).toLocaleString("en-IN", {
        minimumFractionDigits: 2,
        maximumFractionDigits: 2,
      })}`
    : null

  return (
    <form onSubmit={handleSubmit} noValidate className="space-y-5 font-sans">
      {/* Error Banner */}
      {executeTransferMutation.isError && (
        <TransferError
          error={executeTransferMutation.error}
          onRetry={handleSafeRetry}
          isPending={isPending}
        />
      )}

      {/* Account Selectors */}
      <div className="space-y-4">
        <AccountSelector
          id="source-account"
          label="Source Account"
          helperBadge="Debited"
          selectRef={sourceSelectRef}
          accounts={checkingAccounts}
          selectedAccountId={values.sourceAccountId}
          onSelectAccount={handleSourceChange}
          disabled={isPending}
          error={hasAttemptedSubmit ? errors.sourceAccountId : undefined}
          placeholder="Select source checking account..."
        />

        {/* Swap Accounts Button */}
        <div className="flex justify-center -my-1">
          <Button
            type="button"
            variant="outline"
            size="xs"
            onClick={handleSwapAccounts}
            disabled={isPending || !values.sourceAccountId || !values.destinationAccountId}
            title="Swap Source and Destination"
            aria-label="Swap Source and Destination accounts"
            className="h-7 px-2.5 gap-1.5 text-xs text-muted-foreground hover:text-foreground rounded-full border-dashed"
          >
            <ArrowDownUp className="size-3" />
            <span>Swap accounts</span>
          </Button>
        </div>

        <AccountSelector
          id="destination-account"
          label="Destination Account"
          helperBadge="Credited"
          selectRef={destSelectRef}
          accounts={checkingAccounts}
          selectedAccountId={values.destinationAccountId}
          onSelectAccount={handleDestinationChange}
          disabled={isPending}
          error={hasAttemptedSubmit ? errors.destinationAccountId : undefined}
          placeholder="Select destination checking account..."
          excludeAccountId={values.sourceAccountId}
        />
      </div>

      {/* Amount Input (Fixed INR prefix) */}
      <div className="space-y-1.5">
        <label
          htmlFor="transfer-amount"
          className="text-[13.5px] font-medium text-foreground flex items-center justify-between"
        >
          <span>Amount</span>
          <span className="text-xs font-mono text-muted-foreground">INR (₹)</span>
        </label>

        <div className="relative flex items-center">
          <span
            aria-hidden="true"
            className="absolute left-3 font-mono text-sm font-semibold text-muted-foreground select-none"
          >
            ₹
          </span>
          <Input
            id="transfer-amount"
            ref={amountInputRef}
            type="text"
            inputMode="decimal"
            value={values.amount}
            onChange={handleAmountChange}
            disabled={isPending}
            placeholder="0.00"
            monospace
            aria-invalid={Boolean(hasAttemptedSubmit && errors.amount)}
            aria-describedby={
              hasAttemptedSubmit && errors.amount ? "transfer-amount-error" : undefined
            }
            className="pl-7 text-[15px] font-mono h-9 font-semibold"
          />
        </div>

        {hasAttemptedSubmit && errors.amount && (
          <p
            id="transfer-amount-error"
            role="alert"
            className="text-xs text-destructive mt-1 font-sans"
          >
            {errors.amount}
          </p>
        )}
      </div>

      {/* Optional Description Field */}
      <div className="space-y-1.5">
        <div className="flex items-center justify-between">
          <label
            htmlFor="transfer-description"
            className="text-[13.5px] font-medium text-foreground"
          >
            Description <span className="text-xs text-muted-foreground font-normal">(Optional)</span>
          </label>
          <span className="text-xs font-mono text-muted-foreground">
            {values.description.length}/255
          </span>
        </div>

        <Input
          id="transfer-description"
          type="text"
          maxLength={255}
          value={values.description}
          onChange={handleDescriptionChange}
          disabled={isPending}
          placeholder="Memo, invoice reference, or payment note..."
          aria-invalid={Boolean(hasAttemptedSubmit && errors.description)}
          aria-describedby={
            hasAttemptedSubmit && errors.description ? "transfer-description-error" : undefined
          }
          className="h-9 text-[13px]"
        />

        {hasAttemptedSubmit && errors.description && (
          <p
            id="transfer-description-error"
            role="alert"
            className="text-xs text-destructive mt-1 font-sans"
          >
            {errors.description}
          </p>
        )}
      </div>

      {/* Live Transfer Preview Area */}
      <TransferPreview
        sourceAccount={selectedSourceAccount}
        destinationAccount={selectedDestinationAccount}
        amount={values.amount}
        description={values.description}
      />

      {/* Submit Action */}
      <div className="pt-2">
        <Button
          type="submit"
          disabled={isPending || (hasAttemptedSubmit && !isFormValid)}
          className="w-full h-9 font-medium text-[13.5px] gap-2 justify-center shadow-xs"
        >
          {isPending ? (
            <>
              <Loader2 className="size-4 animate-spin" />
              <span>Executing Transfer...</span>
            </>
          ) : (
            <>
              <ArrowLeftRight className="size-3.5" />
              <span>
                {formattedTransferAmount ? `Transfer ${formattedTransferAmount}` : "Transfer Funds"}
              </span>
            </>
          )}
        </Button>
      </div>
    </form>
  )
}
