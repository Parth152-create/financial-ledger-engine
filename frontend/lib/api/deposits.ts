import { apiClient } from "@/lib/api/client"
import { API_ROUTES } from "@/constants/routes"
import type { DepositRequest, DepositResponse } from "@/types/transaction"

export const depositsApi = {
  executeDeposit(data: DepositRequest, idempotencyKey: string): Promise<DepositResponse> {
    return apiClient.post<DepositResponse>(API_ROUTES.DEPOSITS, data, {
      idempotencyKey,
    })
  },
}
