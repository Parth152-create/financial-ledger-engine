"use client"

import * as React from "react"
import { AlertCircle, RotateCw, ShieldAlert, Clock } from "lucide-react"
import { Button } from "@/components/ui/button"
import { getWithdrawalErrorMessage, isTransientError } from "@/lib/validators/withdrawal"
import { ApiError } from "@/types/api"

interface WithdrawalErrorProps {
  error: unknown
  onRetry?: () => void
  isPending?: boolean
}

export function WithdrawalError({
  error,
  onRetry,
  isPending = false,
}: WithdrawalErrorProps) {
  if (!error) return null

  const errorMessage = getWithdrawalErrorMessage(error)
  const canSafelyRetry = isTransientError(error) && Boolean(onRetry)

  let retryAfterSeconds: number | null = null
  if (error instanceof ApiError && error.retryAfter) {
    retryAfterSeconds = error.retryAfter
  }

  return (
    <div
      role="alert"
      aria-live="assertive"
      className="p-3.5 rounded-sm border border-destructive/25 bg-destructive/10 text-destructive space-y-2.5 font-sans"
    >
      <div className="flex items-start gap-2.5">
        <AlertCircle className="size-4.5 shrink-0 mt-0.5 text-destructive" />
        <div className="space-y-1 flex-1">
          <p className="text-[13.5px] font-semibold tracking-tight text-destructive">
            Withdrawal Failed
          </p>
          <p className="text-xs text-destructive/90 leading-relaxed font-sans">
            {errorMessage}
          </p>
        </div>
      </div>

      {retryAfterSeconds && (
        <div className="flex items-center gap-1.5 text-xs text-destructive/80 font-mono bg-destructive/5 px-2.5 py-1.5 rounded-xs border border-destructive/15">
          <Clock className="size-3.5" />
          <span>Rate limited. Suggested wait: {retryAfterSeconds} seconds before retry.</span>
        </div>
      )}

      {canSafelyRetry && onRetry && (
        <div className="pt-1 border-t border-destructive/20 flex flex-col sm:flex-row sm:items-center justify-between gap-2">
          <div className="flex items-center gap-1.5 text-[11px] text-destructive/80">
            <ShieldAlert className="size-3 shrink-0" />
            <span>Safe retry preserves identical idempotency key to prevent duplicate debits.</span>
          </div>

          <Button
            type="button"
            variant="outline"
            size="xs"
            onClick={onRetry}
            disabled={isPending}
            className="gap-1.5 shrink-0 border-destructive/30 text-destructive hover:bg-destructive/10"
          >
            <RotateCw className={`size-3 ${isPending ? "animate-spin" : ""}`} />
            <span>{isPending ? "Retrying..." : "Retry Withdrawal"}</span>
          </Button>
        </div>
      )}
    </div>
  )
}
