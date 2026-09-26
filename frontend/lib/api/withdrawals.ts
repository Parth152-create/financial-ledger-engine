import { apiClient } from "@/lib/api/client"
import { API_ROUTES } from "@/constants/routes"
import type { WithdrawalRequest, WithdrawalResponse } from "@/types/transaction"

export const withdrawalsApi = {
  executeWithdrawal(data: WithdrawalRequest, idempotencyKey: string): Promise<WithdrawalResponse> {
    return apiClient.post<WithdrawalResponse>(API_ROUTES.WITHDRAWALS, data, {
      idempotencyKey,
    })
  },
}
