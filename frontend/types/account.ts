export type AccountType = "USER_CHECKING" | "SYSTEM_CLEARING"

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
