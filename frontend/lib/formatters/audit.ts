import { formatINR } from "./currency"
import type { AuditEvent, AuditEventType, AuditEntityType } from "@/types/audit"

export interface EventTypeConfig {
  label: string
  category: "financial" | "account" | "security" | "system"
  dot: string
  text: string
  bg: string
  border: string
}

export const EVENT_TYPE_CONFIG: Record<AuditEventType, EventTypeConfig> = {
  TRANSFER_COMPLETED: {
    label: "Transfer Completed",
    category: "financial",
    dot: "bg-blue-500",
    text: "text-blue-700 dark:text-blue-400",
    bg: "bg-blue-500/10",
    border: "border-blue-500/20",
  },
  DEPOSIT_COMPLETED: {
    label: "Deposit Completed",
    category: "financial",
    dot: "bg-emerald-500",
    text: "text-emerald-700 dark:text-emerald-400",
    bg: "bg-emerald-500/10",
    border: "border-emerald-500/20",
  },
  WITHDRAWAL_COMPLETED: {
    label: "Withdrawal Completed",
    category: "financial",
    dot: "bg-amber-500",
    text: "text-amber-700 dark:text-amber-400",
    bg: "bg-amber-500/10",
    border: "border-amber-500/20",
  },
  TRANSACTION_REVERSED: {
    label: "Transaction Reversed",
    category: "financial",
    dot: "bg-rose-500",
    text: "text-rose-700 dark:text-rose-400",
    bg: "bg-rose-500/10",
    border: "border-rose-500/20",
  },
  ACCOUNT_CREATED: {
    label: "Account Created",
    category: "account",
    dot: "bg-purple-500",
    text: "text-purple-700 dark:text-purple-400",
    bg: "bg-purple-500/10",
    border: "border-purple-500/20",
  },
  ACCOUNT_FROZEN: {
    label: "Account Frozen",
    category: "account",
    dot: "bg-amber-500",
    text: "text-amber-700 dark:text-amber-400",
    bg: "bg-amber-500/10",
    border: "border-amber-500/20",
  },
  ACCOUNT_UNFROZEN: {
    label: "Account Unfrozen",
    category: "account",
    dot: "bg-emerald-500",
    text: "text-emerald-700 dark:text-emerald-400",
    bg: "bg-emerald-500/10",
    border: "border-emerald-500/20",
  },
  ACCOUNT_CLOSED: {
    label: "Account Closed",
    category: "account",
    dot: "bg-muted-foreground/60",
    text: "text-muted-foreground",
    bg: "bg-muted/50",
    border: "border-border",
  },
  AUTH_SIGNUP: {
    label: "User Registered",
    category: "security",
    dot: "bg-indigo-500",
    text: "text-indigo-700 dark:text-indigo-400",
    bg: "bg-indigo-500/10",
    border: "border-indigo-500/20",
  },
  AUTH_LOGIN: {
    label: "Login Succeeded",
    category: "security",
    dot: "bg-emerald-500",
    text: "text-emerald-700 dark:text-emerald-400",
    bg: "bg-emerald-500/10",
    border: "border-emerald-500/20",
  },
  AUTH_LOGOUT: {
    label: "Logout",
    category: "security",
    dot: "bg-muted-foreground/60",
    text: "text-muted-foreground",
    bg: "bg-muted/50",
    border: "border-border",
  },
  PASSWORD_CHANGED: {
    label: "Password Changed",
    category: "security",
    dot: "bg-amber-500",
    text: "text-amber-700 dark:text-amber-400",
    bg: "bg-amber-500/10",
    border: "border-amber-500/20",
  },
}

export function getEventTypeConfig(type: string): EventTypeConfig {
  const normalized = type as AuditEventType
  return (
    EVENT_TYPE_CONFIG[normalized] || {
      label: type,
      category: "system",
      dot: "bg-muted-foreground/60",
      text: "text-muted-foreground",
      bg: "bg-muted/40",
      border: "border-border",
    }
  )
}

export function formatEntityLabel(type: AuditEntityType | string): string {
  switch (type) {
    case "USER":
      return "User"
    case "ACCOUNT":
      return "Account"
    case "TRANSACTION":
      return "Transaction"
    case "SYSTEM":
      return "System"
    default:
      return type
  }
}

export function formatEventSummary(event: AuditEvent): string {
  const meta = event.metadata || {}

  switch (event.eventType) {
    case "TRANSFER_COMPLETED": {
      const amount = meta.amount !== undefined ? formatINR(Number(meta.amount)) : null
      return amount ? `Internal transfer of ${amount}` : "Internal transfer completed"
    }
    case "DEPOSIT_COMPLETED": {
      const amount = meta.amount !== undefined ? formatINR(Number(meta.amount)) : null
      return amount ? `Deposit of ${amount}` : "Funds deposit completed"
    }
    case "WITHDRAWAL_COMPLETED": {
      const amount = meta.amount !== undefined ? formatINR(Number(meta.amount)) : null
      return amount ? `Withdrawal of ${amount}` : "Funds withdrawal completed"
    }
    case "TRANSACTION_REVERSED": {
      const amount = meta.amount !== undefined ? formatINR(Number(meta.amount)) : null
      const origType = (meta.originalTransactionType as string) || "transaction"
      return amount
        ? `Reversal of ${origType.toLowerCase()} for ${amount}`
        : "Compensating transaction reversal completed"
    }
    case "ACCOUNT_CREATED": {
      const currency = (meta.currency as string) || "INR"
      const type = (meta.accountType as string) || "Checking"
      return `${type} account opened (${currency})`
    }
    case "ACCOUNT_FROZEN":
      return "Account administratively frozen"
    case "ACCOUNT_UNFROZEN":
      return "Account unfrozen and active"
    case "ACCOUNT_CLOSED":
      return "Account closed"
    case "AUTH_SIGNUP":
      return "New user account created"
    case "AUTH_LOGIN":
      return "User authentication succeeded"
    case "AUTH_LOGOUT":
      return "User logged out"
    case "PASSWORD_CHANGED":
      return "Account credentials updated"
    default:
      return String(event.eventType).replace(/_/g, " ").toLowerCase()
  }
}
