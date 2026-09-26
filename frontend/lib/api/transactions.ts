import { apiClient } from "./client"
import type {
  TransactionHistoryPageResponse,
  TransactionHistoryParams,
} from "@/types/transaction"

export const transactionsApi = {
  getAccountTransactions(
    accountId: string,
    params?: TransactionHistoryParams
  ): Promise<TransactionHistoryPageResponse> {
    const queryParams: Record<string, string | number | undefined> = {}

    if (params) {
      if (params.transactionType) queryParams.transactionType = params.transactionType
      if (params.status) queryParams.status = params.status
      if (params.from) queryParams.from = params.from
      if (params.to) queryParams.to = params.to
      if (params.page !== undefined && params.page !== null) queryParams.page = params.page
      if (params.size !== undefined && params.size !== null) queryParams.size = params.size
    }

    return apiClient.get<TransactionHistoryPageResponse>(
      `/api/v1/accounts/${encodeURIComponent(accountId)}/transactions`,
      { params: queryParams }
    )
  },
}
