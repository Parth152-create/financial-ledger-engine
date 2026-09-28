"use client"

import * as React from "react"
import { StatusBadge } from "@/components/ui/status-badge"
import { AmountDisplay } from "@/components/ui/amount-display"
import { maskAccountNumber } from "@/lib/validators/transfer"
import type { Account } from "@/types/account"

interface AccountSelectorProps {
  id: string
  label: string
  helperBadge?: string
  accounts: Account[]
  selectedAccountId: string
  onSelectAccount: (accountId: string) => void
  disabled?: boolean
  error?: string
  placeholder?: string
  excludeAccountId?: string
  autoFocus?: boolean
  selectRef?: React.RefObject<HTMLSelectElement | null>
}

export function AccountSelector({
  id,
  label,
  helperBadge,
  accounts,
  selectedAccountId,
  onSelectAccount,
  disabled = false,
  error,
  placeholder = "Select an account...",
  excludeAccountId,
  selectRef,
}: AccountSelectorProps) {
  // Filter strictly to user checking accounts and exclude specified account if any
  const eligibleAccounts = React.useMemo(() => {
    return accounts.filter((acc) => {
      if (acc.accountType !== "USER_CHECKING") return false
      return true
    })
  }, [accounts])

  const selectedAccount = React.useMemo(() => {
    return eligibleAccounts.find((acc) => acc.accountId === selectedAccountId)
  }, [eligibleAccounts, selectedAccountId])

  return (
    <div className="space-y-1.5 font-sans">
      <div className="flex items-center justify-between">
        <label
          htmlFor={id}
          className="text-[13.5px] font-medium text-foreground flex items-center gap-2"
        >
          <span>{label}</span>
          {helperBadge && (
            <span className="text-[11px] font-normal text-muted-foreground uppercase tracking-wider">
              ({helperBadge})
            </span>
          )}
        </label>
      </div>

      <select
        id={id}
        ref={selectRef}
        value={selectedAccountId}
        onChange={(e) => onSelectAccount(e.target.value)}
        disabled={disabled}
        aria-invalid={Boolean(error)}
        aria-describedby={error ? `${id}-error` : undefined}
        className="flex h-9 w-full rounded-sm border border-input bg-background px-3 py-1.5 text-[13.5px] text-foreground focus-visible:outline-none focus-visible:border-ring focus-visible:ring-1 focus-visible:ring-ring disabled:cursor-not-allowed disabled:opacity-50 transition-colors shadow-2xs font-sans"
      >
        <option value="">{placeholder}</option>
        {eligibleAccounts.map((account) => {
          const isExcluded = excludeAccountId && account.accountId === excludeAccountId
          const isInactive = account.status !== "ACTIVE"
          const masked = maskAccountNumber(account.accountNumber)
          const balanceFormatted = account.balance.toLocaleString("en-IN", {
            minimumFractionDigits: 2,
            maximumFractionDigits: 2,
          })

          return (
            <option
              key={account.accountId}
              value={account.accountId}
              disabled={isInactive}
            >
              Checking {masked} ({account.accountNumber}) · Available: ₹{balanceFormatted}
              {isInactive ? ` [${account.status}]` : ""}
              {isExcluded ? " (Selected as Source)" : ""}
            </option>
          )
        })}
      </select>

      {error && (
        <p id={`${id}-error`} role="alert" className="text-xs text-destructive mt-1 font-sans">
          {error}
        </p>
      )}

      {/* Selected Account Conceptual Card */}
      {selectedAccount && (
        <div className="mt-2 p-3 rounded-sm border border-border/70 bg-card/60 flex items-center justify-between text-xs">
          <div className="space-y-1 min-w-0">
            <div className="flex items-center gap-2">
              <span className="font-semibold text-foreground text-[13px]">Checking</span>
              <span className="font-mono text-muted-foreground text-xs">
                {maskAccountNumber(selectedAccount.accountNumber)}
              </span>
              {selectedAccount.status !== "ACTIVE" && (
                <StatusBadge status={selectedAccount.status} />
              )}
            </div>
            <div className="font-mono text-[11.5px] text-muted-foreground/80 truncate">
              {selectedAccount.accountNumber}
            </div>
          </div>
          <div className="text-right shrink-0 pl-3">
            <span className="text-[10.5px] uppercase tracking-wider text-muted-foreground block font-medium">
              Available
            </span>
            <AmountDisplay
              amount={selectedAccount.balance}
              currency={selectedAccount.currency}
              size="sm"
              align="right"
            />
          </div>
        </div>
      )}
    </div>
  )
}
