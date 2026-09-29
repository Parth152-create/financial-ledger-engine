"use client"

import { useQuery } from "@tanstack/react-query"
import { auditApi } from "@/lib/api/audit"
import { useAuth } from "@/hooks/auth/use-auth"
import type { AuditEventPageResponse, AuditEventQueryParams } from "@/types/audit"
import type { ApiError } from "@/types/api"

export const AUDIT_EVENT_KEYS = {
  all: ["audit-events"] as const,
  list: (params?: AuditEventQueryParams) => [...AUDIT_EVENT_KEYS.all, "list", params] as const,
}

export function useAuditEvents(
  params?: AuditEventQueryParams,
  options?: { enabled?: boolean }
) {
  const { isAuthenticated } = useAuth()

  return useQuery<AuditEventPageResponse, ApiError>({
    queryKey: AUDIT_EVENT_KEYS.list(params),
    queryFn: () => auditApi.getAuditEvents(params),
    enabled: isAuthenticated && (options?.enabled ?? true),
  })
}
