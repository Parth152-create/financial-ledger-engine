import { apiClient } from "./client"
import type {
  OverallReconciliation,
  ReconciliationResult,
} from "@/types/reconciliation"

export const reconciliationApi = {
  reconcileUserAccounts(): Promise<OverallReconciliation> {
    return apiClient.get<OverallReconciliation>("/api/v1/reconciliation")
  },

  reconcileAccount(accountId: string): Promise<ReconciliationResult> {
    return apiClient.get<ReconciliationResult>(
      `/api/v1/reconciliation/accounts/${encodeURIComponent(accountId)}`
    )
  },
}
