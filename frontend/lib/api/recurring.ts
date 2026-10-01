import { apiClient } from "@/lib/api/client"
import { API_ROUTES } from "@/constants/routes"
import type {
  RecurringTransfer,
  CreateRecurringTransferRequest,
  RecurringTransferPageResponse,
  RecurringExecutionPageResponse,
  RecurringTransferStatus,
} from "@/types/recurring"

export const recurringApi = {
  listSchedules(params?: {
    status?: RecurringTransferStatus
    page?: number
    size?: number
  }): Promise<RecurringTransferPageResponse> {
    const searchParams = new URLSearchParams()
    if (params?.status) {
      searchParams.set("status", params.status)
    }
    if (params?.page != null) {
      searchParams.set("page", params.page.toString())
    }
    if (params?.size != null) {
      searchParams.set("size", params.size.toString())
    }
    const qs = searchParams.toString()
    const url = qs ? `${API_ROUTES.RECURRING_TRANSFERS}?${qs}` : API_ROUTES.RECURRING_TRANSFERS
    return apiClient.get<RecurringTransferPageResponse>(url)
  },

  getSchedule(id: string): Promise<RecurringTransfer> {
    return apiClient.get<RecurringTransfer>(API_ROUTES.RECURRING_TRANSFER_BY_ID(id))
  },

  createSchedule(data: CreateRecurringTransferRequest): Promise<RecurringTransfer> {
    return apiClient.post<RecurringTransfer>(API_ROUTES.RECURRING_TRANSFERS, data)
  },

  pauseSchedule(id: string): Promise<RecurringTransfer> {
    return apiClient.post<RecurringTransfer>(API_ROUTES.RECURRING_TRANSFER_PAUSE(id))
  },

  resumeSchedule(id: string): Promise<RecurringTransfer> {
    return apiClient.post<RecurringTransfer>(API_ROUTES.RECURRING_TRANSFER_RESUME(id))
  },

  cancelSchedule(id: string): Promise<RecurringTransfer> {
    return apiClient.post<RecurringTransfer>(API_ROUTES.RECURRING_TRANSFER_CANCEL(id))
  },

  listExecutions(
    id: string,
    params?: { page?: number; size?: number }
  ): Promise<RecurringExecutionPageResponse> {
    const searchParams = new URLSearchParams()
    if (params?.page != null) {
      searchParams.set("page", params.page.toString())
    }
    if (params?.size != null) {
      searchParams.set("size", params.size.toString())
    }
    const qs = searchParams.toString()
    const url = qs
      ? `${API_ROUTES.RECURRING_TRANSFER_EXECUTIONS(id)}?${qs}`
      : API_ROUTES.RECURRING_TRANSFER_EXECUTIONS(id)
    return apiClient.get<RecurringExecutionPageResponse>(url)
  },
}
