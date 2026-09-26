import { apiClient } from "./client"
import type {
  AccountStatementResponse,
  AccountStatementParams,
} from "@/types/statement"

export const statementsApi = {
  getAccountStatement(
    accountId: string,
    params?: AccountStatementParams
  ): Promise<AccountStatementResponse> {
    const queryParams: Record<string, string | number | undefined> = {}

    if (params) {
      if (params.from) queryParams.from = params.from
      if (params.to) queryParams.to = params.to
      if (params.transactionType) queryParams.transactionType = params.transactionType
      if (params.status) queryParams.status = params.status
      if (params.page !== undefined && params.page !== null) queryParams.page = params.page
      if (params.size !== undefined && params.size !== null) queryParams.size = params.size
    }

    return apiClient.get<AccountStatementResponse>(
      `/api/v1/accounts/${encodeURIComponent(accountId)}/statement`,
      { params: queryParams }
    )
  },
}
