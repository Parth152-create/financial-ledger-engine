"use client"

import * as React from "react"
import { Lock, CheckCircle2, AlertCircle, Loader2 } from "lucide-react"
import { Input } from "@/components/ui/input"
import { Button } from "@/components/ui/button"
import { authApi } from "@/lib/api/auth"
import { validatePasswordForm, getSettingsErrorMessage } from "@/lib/validators/settings"

export function PasswordForm() {
  const [password, setPassword] = React.useState("")
  const [confirmPassword, setConfirmPassword] = React.useState("")
  const [errors, setErrors] = React.useState<{ password?: string; confirmPassword?: string }>({})
  const [isSubmitting, setIsSubmitting] = React.useState(false)
  const [successMessage, setSuccessMessage] = React.useState<string | null>(null)
  const [apiError, setApiError] = React.useState<string | null>(null)

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setSuccessMessage(null)
    setApiError(null)

    const validation = validatePasswordForm({ password, confirmPassword })
    if (!validation.isValid) {
      setErrors(validation.errors)
      return
    }

    setErrors({})
    setIsSubmitting(true)

    try {
      await authApi.linkPassword(password)
      setSuccessMessage("Password credential updated successfully.")
      setPassword("")
      setConfirmPassword("")
    } catch (err) {
      setApiError(getSettingsErrorMessage(err))
    } finally {
      setIsSubmitting(false)
    }
  }

  return (
    <form onSubmit={handleSubmit} className="space-y-4">
      {successMessage && (
        <div
          role="status"
          className="flex items-center gap-2 p-3 rounded-sm border border-emerald-500/30 bg-emerald-500/10 text-xs text-emerald-600 dark:text-emerald-400"
        >
          <CheckCircle2 className="size-4 shrink-0" />
          <span>{successMessage}</span>
        </div>
      )}

      {apiError && (
        <div
          role="alert"
          className="flex items-center gap-2 p-3 rounded-sm border border-destructive/30 bg-destructive/10 text-xs text-destructive"
        >
          <AlertCircle className="size-4 shrink-0" />
          <span>{apiError}</span>
        </div>
      )}

      <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
        <div className="space-y-1.5">
          <label
            htmlFor="settings-password"
            className="text-xs font-medium text-foreground block"
          >
            New Password
          </label>
          <Input
            id="settings-password"
            type="password"
            value={password}
            onChange={(e) => {
              setPassword(e.target.value)
              if (errors.password) setErrors((prev) => ({ ...prev, password: undefined }))
            }}
            placeholder="••••••••"
            autoComplete="new-password"
            disabled={isSubmitting}
            aria-invalid={Boolean(errors.password)}
            aria-describedby={errors.password ? "settings-password-error" : undefined}
          />
          {errors.password && (
            <p id="settings-password-error" className="text-[12px] text-destructive">
              {errors.password}
            </p>
          )}
        </div>

        <div className="space-y-1.5">
          <label
            htmlFor="settings-confirm-password"
            className="text-xs font-medium text-foreground block"
          >
            Confirm New Password
          </label>
          <Input
            id="settings-confirm-password"
            type="password"
            value={confirmPassword}
            onChange={(e) => {
              setConfirmPassword(e.target.value)
              if (errors.confirmPassword) setErrors((prev) => ({ ...prev, confirmPassword: undefined }))
            }}
            placeholder="••••••••"
            autoComplete="new-password"
            disabled={isSubmitting}
            aria-invalid={Boolean(errors.confirmPassword)}
            aria-describedby={errors.confirmPassword ? "settings-confirm-password-error" : undefined}
          />
          {errors.confirmPassword && (
            <p id="settings-confirm-password-error" className="text-[12px] text-destructive">
              {errors.confirmPassword}
            </p>
          )}
        </div>
      </div>

      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 pt-1">
        <p className="text-[12px] text-muted-foreground">
          Minimum 8 characters with at least one letter and one number.
        </p>
        <Button
          type="submit"
          size="sm"
          disabled={isSubmitting}
          className="gap-1.5 self-start sm:self-auto text-[13px]"
        >
          {isSubmitting ? (
            <>
              <Loader2 className="size-3.5 animate-spin" />
              <span>Updating Password...</span>
            </>
          ) : (
            <>
              <Lock className="size-3.5" />
              <span>Update Password</span>
            </>
          )}
        </Button>
      </div>
    </form>
  )
}
