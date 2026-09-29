import { apiClient } from "./client"
import { API_ROUTES } from "@/constants/routes"
import type { AuditEventPageResponse, AuditEventQueryParams } from "@/types/audit"

export const auditApi = {
  getAuditEvents(params?: AuditEventQueryParams): Promise<AuditEventPageResponse> {
    return apiClient.get<AuditEventPageResponse>(API_ROUTES.AUDIT_EVENTS, {
      params: {
        eventType: params?.eventType || undefined,
        entityType: params?.entityType || undefined,
        from: params?.from || undefined,
        to: params?.to || undefined,
        page: params?.page ?? 0,
        size: params?.size ?? 20,
      },
    })
  },
}
