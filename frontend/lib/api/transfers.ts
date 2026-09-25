import { apiClient } from "@/lib/api/client"
import { API_ROUTES } from "@/constants/routes"
import type { TransferRequest, TransferResponse } from "@/types/transaction"

export const transfersApi = {
  executeTransfer(data: TransferRequest, idempotencyKey: string): Promise<TransferResponse> {
    return apiClient.post<TransferResponse>(API_ROUTES.TRANSFERS, data, {
      idempotencyKey,
    })
  },
}
