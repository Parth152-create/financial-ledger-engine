import type { TransactionStatus, TransactionType, TransactionDirection } from "./transaction"

export interface StatementEntry {
  transactionId: string
  transactionType: TransactionType
  direction: TransactionDirection
  amount: number
  currency: string
  description?: string | null
  status: TransactionStatus
  createdAt: string
  completedAt?: string | null
  balanceAfter: number
}

export interface AccountStatementResponse {
  accountId: string
  accountNumber: string
  currency: string
  openingBalance: number
  entries: StatementEntry[]
  closingBalance: number
  totalCredits: number
  totalDebits: number
  page: number
  size: number
  totalElements: number
  totalPages: number
  first: boolean
  last: boolean
}

export interface AccountStatementParams {
  from?: string
  to?: string
  transactionType?: TransactionType | string
  status?: TransactionStatus | string
  page?: number
  size?: number
}
