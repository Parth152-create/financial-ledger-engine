"use client"

import * as React from "react"
import { Loader2, ArrowUpRight } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { AccountSelector } from "@/components/transfers/account-selector"
import { WithdrawalPreview } from "@/components/withdrawals/withdrawal-preview"
import { WithdrawalError } from "@/components/withdrawals/withdrawal-error"
import {
  validateWithdrawalForm,
  type WithdrawalFormValues,
} from "@/lib/validators/withdrawal"
import { useExecuteWithdrawal } from "@/hooks/api/use-withdrawals"
import type { Account } from "@/types/account"
import type { WithdrawalResponse } from "@/types/transaction"

interface WithdrawalFormProps {
  accounts: Account[]
  initialSourceAccountId?: string
  onSuccess: (result: WithdrawalResponse, sourceAccount?: Account) => void
}

export function WithdrawalForm({
  accounts,
  initialSourceAccountId,
  onSuccess,
}: WithdrawalFormProps) {
  // Filter user checking accounts
  const checkingAccounts = React.useMemo(() => {
    return accounts.filter((a) => a.accountType === "USER_CHECKING")
  }, [accounts])

  const initialAccount = React.useMemo(() => {
    if (initialSourceAccountId) {
      return checkingAccounts.find((a) => a.accountId === initialSourceAccountId)
    }
    return checkingAccounts[0]
  }, [checkingAccounts, initialSourceAccountId])

  // Form values
  const [values, setValues] = React.useState<WithdrawalFormValues>({
    accountId: initialAccount?.accountId ?? "",
    amount: "",
    currency: initialAccount?.currency ?? "INR",
    description: "",
  })

  // Idempotency key for logical withdrawal
  const [idempotencyKey, setIdempotencyKey] = React.useState<string | null>(null)

  // Validation errors
  const [errors, setErrors] = React.useState<Record<string, string>>({})
  const [hasAttemptedSubmit, setHasAttemptedSubmit] = React.useState(false)

  // Execution mutation
  const executeWithdrawalMutation = useExecuteWithdrawal()

  // Resolved source account
  const selectedSourceAccount = React.useMemo(() => {
    return checkingAccounts.find((a) => a.accountId === values.accountId)
  }, [checkingAccounts, values.accountId])

  // Refs for accessibility
  const sourceSelectRef = React.useRef<HTMLSelectElement>(null)
  const amountInputRef = React.useRef<HTMLInputElement>(null)

  // Invalidate idempotency key whenever withdrawal parameters change
  const invalidateKeyAndErrors = (field?: string) => {
    setIdempotencyKey(null)
    executeWithdrawalMutation.reset()
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
    const matched = checkingAccounts.find((a) => a.accountId === newSourceId)
    setValues((prev) => ({
      ...prev,
      accountId: newSourceId,
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
        : `withdrawal-${Date.now()}-${Math.random().toString(36).substring(2, 9)}`
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
    const validation = validateWithdrawalForm(
      values,
      selectedSourceAccount
    )

    if (!validation.isValid) {
      setErrors(validation.errors as Record<string, string>)
      if (validation.errors.accountId) {
        sourceSelectRef.current?.focus()
      } else if (validation.errors.amount) {
        amountInputRef.current?.focus()
      }
      return
    }

    setErrors({})

    // Assign or maintain logical idempotency key
    const activeKey = getOrCreateIdempotencyKey()

    try {
      const response = await executeWithdrawalMutation.mutateAsync({
        request: {
          accountId: values.accountId,
          amount: parseFloat(values.amount),
          currency: values.currency || "INR",
          description: values.description.trim() ? values.description.trim() : undefined,
        },
        idempotencyKey: activeKey,
      })

      onSuccess(response, selectedSourceAccount)
    } catch {
      // Error is stored in executeWithdrawalMutation.error and rendered via WithdrawalError
    }
  }

  // Retry with identical idempotency key
  const handleSafeRetry = () => {
    if (!idempotencyKey) return
    handleSubmit()
  }

  const isPending = executeWithdrawalMutation.isPending
  const isFormValid =
    Boolean(values.accountId) &&
    Boolean(values.amount) &&
    /^\d+(\.\d{1,4})?$/.test(values.amount) &&
    parseFloat(values.amount) > 0 &&
    Boolean(
      selectedSourceAccount &&
      typeof selectedSourceAccount.balance === "number" &&
      parseFloat(values.amount) <= selectedSourceAccount.balance
    )

  const formattedWithdrawalAmount =
    Boolean(values.amount) && /^\d+(\.\d{1,4})?$/.test(values.amount) && parseFloat(values.amount) > 0
      ? `₹${parseFloat(values.amount).toLocaleString("en-IN", {
          minimumFractionDigits: 2,
          maximumFractionDigits: 2,
        })}`
      : null

  return (
    <form onSubmit={handleSubmit} noValidate className="space-y-5 font-sans">
      {/* Error Banner */}
      {executeWithdrawalMutation.isError && (
        <WithdrawalError
          error={executeWithdrawalMutation.error}
          onRetry={handleSafeRetry}
          isPending={isPending}
        />
      )}

      {/* Account Selector */}
      <div className="space-y-4">
        <AccountSelector
          id="withdrawal-source-account"
          label="Source Account"
          helperBadge="Debited"
          selectRef={sourceSelectRef}
          accounts={checkingAccounts}
          selectedAccountId={values.accountId}
          onSelectAccount={handleSourceChange}
          disabled={isPending}
          error={hasAttemptedSubmit ? errors.accountId : undefined}
          placeholder="Select source checking account..."
        />
      </div>

      {/* Amount Input (Fixed INR prefix) */}
      <div className="space-y-1.5">
        <label
          htmlFor="withdrawal-amount"
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
            id="withdrawal-amount"
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
              hasAttemptedSubmit && errors.amount ? "withdrawal-amount-error" : undefined
            }
            className="pl-7 text-[15px] font-mono h-9 font-semibold"
          />
        </div>

        {hasAttemptedSubmit && errors.amount && (
          <p
            id="withdrawal-amount-error"
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
            htmlFor="withdrawal-description"
            className="text-[13.5px] font-medium text-foreground"
          >
            Description <span className="text-xs text-muted-foreground font-normal">(Optional)</span>
          </label>
          <span className="text-xs font-mono text-muted-foreground">
            {values.description.length}/255
          </span>
        </div>

        <Input
          id="withdrawal-description"
          type="text"
          maxLength={255}
          value={values.description}
          onChange={handleDescriptionChange}
          disabled={isPending}
          placeholder="Withdrawal memo, purpose, or settlement reference..."
          aria-invalid={Boolean(hasAttemptedSubmit && errors.description)}
          aria-describedby={
            hasAttemptedSubmit && errors.description ? "withdrawal-description-error" : undefined
          }
          className="h-9 text-[13px]"
        />

        {hasAttemptedSubmit && errors.description && (
          <p
            id="withdrawal-description-error"
            role="alert"
            className="text-xs text-destructive mt-1 font-sans"
          >
            {errors.description}
          </p>
        )}
      </div>

      {/* Live Withdrawal Preview Area */}
      <WithdrawalPreview
        sourceAccount={selectedSourceAccount}
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
              <span>Executing Withdrawal...</span>
            </>
          ) : (
            <>
              <ArrowUpRight className="size-3.5" />
              <span>
                {formattedWithdrawalAmount ? `Withdraw ${formattedWithdrawalAmount}` : "Withdraw Funds"}
              </span>
            </>
          )}
        </Button>
      </div>
    </form>
  )
}
