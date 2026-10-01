export type AccountType = "USER_CHECKING" | "SYSTEM_CLEARING" | "SYSTEM_TREASURY"

export type AccountStatus = "ACTIVE" | "FROZEN" | "CLOSED"

export interface Account {
  accountId: string
  accountNumber: string
  accountType: AccountType
  status: AccountStatus
  currency: string
  balance: number
  createdAt: string
  updatedAt: string
}

export interface CreateAccountRequest {
  currency: string
}

export interface AccountLimitSummary {
  accountId: string
  transactionType: "TRANSFER" | "DEPOSIT" | "WITHDRAWAL"
  maxTransactionAmount: number | null
  dailyAmountLimit: number | null
  dailyAmountUsed: number
  dailyCountLimit: number | null
  dailyCountUsed: number
  accountBalanceLimit: number | null
  currency: string
}
