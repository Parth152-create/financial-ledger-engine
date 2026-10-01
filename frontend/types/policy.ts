import type { TransactionType } from "./transaction"

export type PolicyType =
  | "MAX_TRANSACTION_AMOUNT"
  | "DAILY_TRANSACTION_AMOUNT"
  | "DAILY_TRANSACTION_COUNT"
  | "ACCOUNT_BALANCE_LIMIT"

export type PolicyScope = "GLOBAL" | "ACCOUNT"

export interface PolicyLimitSummary {
  accountId: string
  transactionType: TransactionType
  maxTransactionAmount: number | null
  dailyAmountLimit: number | null
  dailyAmountUsed: number
  dailyAmountRemaining: number | null
  dailyCountLimit: number | null
  dailyCountUsed: number
  dailyCountRemaining: number | null
  balanceLimit: number | null
  accountBalanceLimit: number | null
  currentBalance: number
  balanceRemaining: number | null
  balanceCapacityRemaining?: number | null
  currency: string
}

export interface FinancialPolicy {
  id: string
  accountId: string | null
  policyScope: PolicyScope
  scope?: PolicyScope
  transactionType: TransactionType | null
  policyType: PolicyType
  amountLimit: number | null
  countLimit: number | null
  currency: string
  enabled: boolean
  createdAt: string
  updatedAt: string
}

export interface CreatePolicyRequest {
  accountId?: string | null
  policyScope: PolicyScope
  scope?: PolicyScope
  transactionType?: TransactionType | null
  policyType: PolicyType
  amountLimit?: number | null
  countLimit?: number | null
  currency?: string
  enabled?: boolean
}

export interface UpdatePolicyRequest {
  amountLimit?: number | null
  countLimit?: number | null
  enabled?: boolean
}
