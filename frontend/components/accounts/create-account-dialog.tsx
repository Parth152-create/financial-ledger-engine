"use client"

import * as React from "react"
import { Landmark, X, AlertCircle, Loader2 } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { useCreateAccount } from "@/hooks/api/use-accounts"
import { ApiError } from "@/types/api"

interface CreateAccountDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  onSuccess?: (accountId: string) => void
}

export function CreateAccountDialog({
  open,
  onOpenChange,
  onSuccess,
}: CreateAccountDialogProps) {
  const [currency, setCurrency] = React.useState("INR")
  const [errorMsg, setErrorMsg] = React.useState<string | null>(null)
  const createAccountMutation = useCreateAccount()

  const handleClose = () => {
    if (!createAccountMutation.isPending) {
      setCurrency("INR")
      setErrorMsg(null)
      onOpenChange(false)
    }
  }

  if (!open) return null

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setErrorMsg(null)

    const trimmedCurrency = currency.trim().toUpperCase()
    if (!trimmedCurrency) {
      setErrorMsg("Currency is required")
      return
    }

    if (!/^[A-Z]{3}$/.test(trimmedCurrency)) {
      setErrorMsg("Currency must be exactly 3 uppercase letters (e.g., INR)")
      return
    }

    try {
      const created = await createAccountMutation.mutateAsync({
        currency: trimmedCurrency,
      })
      handleClose()
      if (onSuccess) {
        onSuccess(created.accountId)
      }
    } catch (err) {
      if (err instanceof ApiError) {
        setErrorMsg(err.message || "Failed to create account")
      } else if (err instanceof Error) {
        setErrorMsg(err.message)
      } else {
        setErrorMsg("An unexpected error occurred while creating the account")
      }
    }
  }

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="create-account-title"
      className="fixed inset-0 z-50 flex items-center justify-center bg-background/80 backdrop-blur-xs p-4 select-none"
    >
      <div
        className="fixed inset-0"
        onClick={handleClose}
      />

      <div className="relative w-full max-w-md rounded-sm border border-border bg-card p-5 text-card-foreground shadow-lg space-y-4 z-10 font-sans">
        <div className="flex items-start justify-between pb-3 border-b border-border/70">
          <div className="flex items-center gap-2.5">
            <div className="size-8 rounded-sm bg-muted/60 border border-border flex items-center justify-center text-muted-foreground shrink-0">
              <Landmark className="size-4" />
            </div>
            <div>
              <h2
                id="create-account-title"
                className="text-[17px] font-semibold text-foreground tracking-tight"
              >
                Create Checking Account
              </h2>
              <p className="text-[13.5px] text-muted-foreground mt-0.5">
                Provision a new user-owned checking account in the ledger.
              </p>
            </div>
          </div>
          <Button
            type="button"
            variant="ghost"
            size="icon-xs"
            onClick={handleClose}
            disabled={createAccountMutation.isPending}
            className="text-muted-foreground hover:text-foreground"
          >
            <X className="size-3.5" />
            <span className="sr-only">Close</span>
          </Button>
        </div>

        {errorMsg && (
          <div
            role="alert"
            className="flex items-start gap-2 p-2.5 text-[13px] rounded-sm bg-destructive/10 border border-destructive/20 text-destructive"
          >
            <AlertCircle className="size-4 shrink-0 mt-0.5" />
            <span className="leading-snug">{errorMsg}</span>
          </div>
        )}

        <form onSubmit={handleSubmit} className="space-y-4">
          <div className="space-y-1.5">
            <label
              htmlFor="account-currency"
              className="text-[13.5px] font-medium text-foreground block"
            >
              Account Currency (ISO 4217)
            </label>
            <Input
              id="account-currency"
              value={currency}
              onChange={(e) => setCurrency(e.target.value.toUpperCase())}
              placeholder="INR"
              maxLength={3}
              monospace
              disabled={createAccountMutation.isPending}
              className="h-8.5 text-[14px] font-mono uppercase"
              autoFocus
            />
            <p className="text-xs text-muted-foreground">
              Primary project currency is <span className="font-semibold text-foreground">INR</span> (₹).
            </p>
          </div>

          <div className="rounded-sm border border-border/70 bg-muted/30 p-3 text-[12.5px] text-muted-foreground space-y-1.5">
            <div className="flex justify-between">
              <span>Account Type:</span>
              <span className="font-mono text-foreground font-medium">USER_CHECKING</span>
            </div>
            <div className="flex justify-between">
              <span>Starting Balance:</span>
              <span className="font-mono text-foreground font-medium">₹0.00</span>
            </div>
            <div className="flex justify-between">
              <span>Initial Status:</span>
              <span className="font-mono text-foreground font-medium">ACTIVE</span>
            </div>
            <p className="pt-1.5 text-[11px] text-muted-foreground/80 leading-normal border-t border-border/50">
              Account numbers are uniquely generated server-side with strict ledger immutability.
            </p>
          </div>

          <div className="flex items-center justify-end gap-2 pt-2 border-t border-border/70">
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={handleClose}
              disabled={createAccountMutation.isPending}
            >
              Cancel
            </Button>
            <Button
              type="submit"
              size="sm"
              disabled={createAccountMutation.isPending}
              className="gap-1.5"
            >
              {createAccountMutation.isPending ? (
                <>
                  <Loader2 className="size-3.5 animate-spin" />
                  <span>Creating Account...</span>
                </>
              ) : (
                <span>Create Account</span>
              )}
            </Button>
          </div>
        </form>
      </div>
    </div>
  )
}
