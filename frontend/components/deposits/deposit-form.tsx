"use client"

import * as React from "react"
import { Loader2, ArrowDownLeft } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { AccountSelector } from "@/components/transfers/account-selector"
import { DepositPreview } from "@/components/deposits/deposit-preview"
import { DepositError } from "@/components/deposits/deposit-error"
import {
  validateDepositForm,
  type DepositFormValues,
} from "@/lib/validators/deposit"
import { useExecuteDeposit } from "@/hooks/api/use-deposits"
import { useAccountLimit } from "@/hooks/api/use-accounts"
import { formatINR } from "@/lib/formatters/currency"
import type { Account } from "@/types/account"
import type { DepositResponse } from "@/types/transaction"

interface DepositFormProps {
  accounts: Account[]
  initialDestinationAccountId?: string
  onSuccess: (result: DepositResponse, destinationAccount?: Account) => void
}

export function DepositForm({
  accounts,
  initialDestinationAccountId,
  onSuccess,
}: DepositFormProps) {
  // Filter user checking accounts
  const checkingAccounts = React.useMemo(() => {
    return accounts.filter((a) => a.accountType === "USER_CHECKING")
  }, [accounts])

  const initialAccount = React.useMemo(() => {
    if (initialDestinationAccountId) {
      return checkingAccounts.find((a) => a.accountId === initialDestinationAccountId)
    }
    return checkingAccounts[0]
  }, [checkingAccounts, initialDestinationAccountId])

  // Form values
  const [values, setValues] = React.useState<DepositFormValues>({
    accountId: initialAccount?.accountId ?? "",
    amount: "",
    currency: initialAccount?.currency ?? "INR",
    description: "",
  })

  // Idempotency key for logical deposit
  const [idempotencyKey, setIdempotencyKey] = React.useState<string | null>(null)

  // Validation errors
  const [errors, setErrors] = React.useState<Record<string, string>>({})
  const [hasAttemptedSubmit, setHasAttemptedSubmit] = React.useState(false)

  // Execution mutation
  const executeDepositMutation = useExecuteDeposit()

  // Account Limits query for destination account
  const { data: depositLimits } = useAccountLimit(values.accountId, "DEPOSIT")

  // Resolved destination account
  const selectedDestinationAccount = React.useMemo(() => {
    return checkingAccounts.find((a) => a.accountId === values.accountId)
  }, [checkingAccounts, values.accountId])

  // Refs for accessibility
  const destSelectRef = React.useRef<HTMLSelectElement>(null)
  const amountInputRef = React.useRef<HTMLInputElement>(null)

  // Invalidate idempotency key whenever deposit parameters change
  const invalidateKeyAndErrors = (field?: string) => {
    setIdempotencyKey(null)
    executeDepositMutation.reset()
    if (field && errors[field]) {
      setErrors((prev) => {
        const next = { ...prev }
        delete next[field]
        return next
      })
    }
  }

  // Handlers
  const handleDestinationChange = (newDestId: string) => {
    const matched = checkingAccounts.find((a) => a.accountId === newDestId)
    setValues((prev) => ({
      ...prev,
      accountId: newDestId,
      currency: matched ? matched.currency : "INR",
    }))
    invalidateKeyAndErrors("accountId")
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
        : `deposit-${Date.now()}-${Math.random().toString(36).substring(2, 9)}`
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
    const validation = validateDepositForm(
      values,
      selectedDestinationAccount
    )

    if (!validation.isValid) {
      setErrors(validation.errors as Record<string, string>)
      if (validation.errors.accountId) {
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
      const response = await executeDepositMutation.mutateAsync({
        request: {
          accountId: values.accountId,
          amount: parseFloat(values.amount),
          currency: values.currency || "INR",
          description: values.description.trim() ? values.description.trim() : undefined,
        },
        idempotencyKey: activeKey,
      })

      onSuccess(response, selectedDestinationAccount)
    } catch {
      // Error is stored in executeDepositMutation.error and rendered via DepositError
    }
  }

  // Retry with identical idempotency key
  const handleSafeRetry = () => {
    if (!idempotencyKey) return
    handleSubmit()
  }

  const isPending = executeDepositMutation.isPending
  const isFormValid =
    Boolean(values.accountId) &&
    Boolean(values.amount) &&
    /^\d+(\.\d{1,4})?$/.test(values.amount) &&
    parseFloat(values.amount) > 0

  const formattedDepositAmount = isFormValid
    ? `₹${parseFloat(values.amount).toLocaleString("en-IN", {
        minimumFractionDigits: 2,
        maximumFractionDigits: 2,
      })}`
    : null

  return (
    <form onSubmit={handleSubmit} noValidate className="space-y-5 font-sans">
      {/* Error Banner */}
      {executeDepositMutation.isError && (
        <DepositError
          error={executeDepositMutation.error}
          onRetry={handleSafeRetry}
          isPending={isPending}
        />
      )}

      {/* Account Selector */}
      <div className="space-y-4">
        <AccountSelector
          id="deposit-destination-account"
          label="Destination Account"
          helperBadge="Credited"
          selectRef={destSelectRef}
          accounts={checkingAccounts}
          selectedAccountId={values.accountId}
          onSelectAccount={handleDestinationChange}
          disabled={isPending}
          error={hasAttemptedSubmit ? errors.accountId : undefined}
          placeholder="Select destination checking account..."
        />
      </div>

      {/* Amount Input (Fixed INR prefix) */}
      <div className="space-y-1.5">
        <label
          htmlFor="deposit-amount"
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
            id="deposit-amount"
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
              hasAttemptedSubmit && errors.amount ? "deposit-amount-error" : undefined
            }
            className="pl-7 text-[15px] font-mono h-9 font-semibold"
          />
        </div>

        {hasAttemptedSubmit && errors.amount && (
          <p
            id="deposit-amount-error"
            role="alert"
            className="text-xs text-destructive mt-1 font-sans"
          >
            {errors.amount}
          </p>
        )}

        {depositLimits && (
          <div className="flex items-center justify-between text-[11px] text-muted-foreground font-mono pt-1">
            {depositLimits.maxTransactionAmount != null && (
              <span>Max deposit: {formatINR(depositLimits.maxTransactionAmount)}</span>
            )}
            {depositLimits.dailyAmountRemaining != null && (
              <span>Daily left: {formatINR(depositLimits.dailyAmountRemaining)}</span>
            )}
          </div>
        )}
      </div>

      {/* Optional Description Field */}
      <div className="space-y-1.5">
        <div className="flex items-center justify-between">
          <label
            htmlFor="deposit-description"
            className="text-[13.5px] font-medium text-foreground"
          >
            Description <span className="text-xs text-muted-foreground font-normal">(Optional)</span>
          </label>
          <span className="text-xs font-mono text-muted-foreground">
            {values.description.length}/255
          </span>
        </div>

        <Input
          id="deposit-description"
          type="text"
          maxLength={255}
          value={values.description}
          onChange={handleDescriptionChange}
          disabled={isPending}
          placeholder="Deposit memo, payroll funding, or external reference..."
          aria-invalid={Boolean(hasAttemptedSubmit && errors.description)}
          aria-describedby={
            hasAttemptedSubmit && errors.description ? "deposit-description-error" : undefined
          }
          className="h-9 text-[13px]"
        />

        {hasAttemptedSubmit && errors.description && (
          <p
            id="deposit-description-error"
            role="alert"
            className="text-xs text-destructive mt-1 font-sans"
          >
            {errors.description}
          </p>
        )}
      </div>

      {/* Live Deposit Preview Area */}
      <DepositPreview
        destinationAccount={selectedDestinationAccount}
        amount={values.amount}
        currency={values.currency}
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
              <span>Executing Deposit...</span>
            </>
          ) : (
            <>
              <ArrowDownLeft className="size-3.5" />
              <span>
                {formattedDepositAmount ? `Deposit ${formattedDepositAmount}` : "Deposit Funds"}
              </span>
            </>
          )}
        </Button>
      </div>
    </form>
  )
}
