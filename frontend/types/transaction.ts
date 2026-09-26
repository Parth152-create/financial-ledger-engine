export type TransactionStatus = "PENDING" | "COMPLETED" | "FAILED"
export type TransactionType = "TRANSFER" | "DEPOSIT" | "WITHDRAWAL"
export type TransactionDirection = "DEBIT" | "CREDIT"

export interface TransferRequest {
  sourceAccountId: string
  destinationAccountId: string
  amount: number
  currency: string
  description?: string
}

export interface DepositRequest {
  accountId: string
  amount: number
  currency: string
  description?: string
}

export interface WithdrawalRequest {
  accountId: string
  amount: number
  currency: string
  description?: string
}

export interface TransactionResponse {
  transactionId: string
  status: TransactionStatus
  sourceAccountId: string
  destinationAccountId: string
  amount: number
  currency: string
  createdAt: string
  completedAt?: string | null
  transactionType: TransactionType
  idempotencyKey?: string | null
  initiatedByUserId?: string | null
  description?: string | null
}

export type TransferResponse = TransactionResponse
export type DepositResponse = TransactionResponse
export type WithdrawalResponse = TransactionResponse

export interface TransactionHistoryItem {
  transactionId: string
  transactionType: TransactionType
  direction: TransactionDirection
  sourceAccountId: string
  destinationAccountId: string
  amount: number
  currency: string
  description?: string | null
  status: TransactionStatus
  initiatedByUserId?: string | null
  createdAt: string
  completedAt?: string | null
}

export interface TransactionHistoryPageResponse {
  content: TransactionHistoryItem[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  first: boolean
  last: boolean
}

export interface TransactionHistoryParams {
  transactionType?: TransactionType | string
  status?: TransactionStatus | string
  from?: string
  to?: string
  page?: number
  size?: number
}
