export type TransactionStatus = "PENDING" | "COMPLETED" | "FAILED"
export type TransactionType = "TRANSFER" | "DEPOSIT" | "WITHDRAWAL"

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

