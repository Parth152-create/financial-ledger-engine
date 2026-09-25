export type TransactionStatus = "PENDING" | "COMPLETED" | "FAILED"
export type TransactionType = "TRANSFER" | "DEPOSIT" | "WITHDRAWAL"

export interface TransferRequest {
  sourceAccountId: string
  destinationAccountId: string
  amount: number
  currency: string
  description?: string
}

export interface TransferResponse {
  transactionId: string
  status: TransactionStatus
  sourceAccountId: string
  destinationAccountId: string
  amount: number
  currency: string
  createdAt: string
  completedAt?: string | null
  transactionType: TransactionType
  initiatedByUserId?: string | null
  description?: string | null
}
