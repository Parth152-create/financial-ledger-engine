export type RecurringFrequency = "DAILY" | "WEEKLY" | "MONTHLY"

export type RecurringTransferStatus = "ACTIVE" | "PAUSED" | "COMPLETED" | "CANCELLED"

export type RecurringExecutionStatus = "SUCCESS" | "FAILED"

export interface RecurringTransfer {
  id: string
  userId: string
  sourceAccountId: string
  destinationAccountId: string
  amount: string
  currency: string
  frequency: RecurringFrequency
  startDate: string
  endDate: string | null
  status: RecurringTransferStatus
  nextExecutionAt: string | null
  lastExecutedAt: string | null
  executionCount: number
  failureCount: number
  createdAt: string
  updatedAt: string
}

export interface RecurringTransferExecution {
  id: string
  recurringTransferId: string
  executionKey: string
  scheduledFor: string
  transactionId: string | null
  status: RecurringExecutionStatus
  failureReason: string | null
  executedAt: string
}

export interface CreateRecurringTransferRequest {
  sourceAccountId: string
  destinationAccountId: string
  amount: number | string
  currency: string
  frequency: RecurringFrequency
  startDate: string
  endDate?: string | null
}

export interface RecurringTransferPageResponse {
  content: RecurringTransfer[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  last: boolean
}

export interface RecurringExecutionPageResponse {
  content: RecurringTransferExecution[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  last: boolean
}
