import { apiClient } from "@/lib/api/client"
import { API_ROUTES } from "@/constants/routes"
import type { Account, CreateAccountRequest } from "@/types/account"
import type { PolicyLimitSummary } from "@/types/policy"
import type { TransactionType } from "@/types/transaction"

export const accountsApi = {
  listAccounts(): Promise<Account[]> {
    return apiClient.get<Account[]>(API_ROUTES.ACCOUNTS)
  },

  getAccount(accountId: string): Promise<Account> {
    return apiClient.get<Account>(API_ROUTES.ACCOUNT_BY_ID(accountId))
  },

  createAccount(data: CreateAccountRequest): Promise<Account> {
    return apiClient.post<Account>(API_ROUTES.ACCOUNTS, data)
  },

  getAccountLimits(accountId: string): Promise<PolicyLimitSummary[]> {
    return apiClient.get<PolicyLimitSummary[]>(API_ROUTES.ACCOUNT_LIMITS(accountId))
  },

  getAccountLimit(accountId: string, transactionType: TransactionType | string): Promise<PolicyLimitSummary> {
    return apiClient.get<PolicyLimitSummary>(
      `${API_ROUTES.ACCOUNT_LIMITS(accountId)}?transactionType=${transactionType}`
    )
  },
}
