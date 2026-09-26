"use client"

import { useQuery } from "@tanstack/react-query"
import { reconciliationApi } from "@/lib/api/reconciliation"
import { useAuth } from "@/hooks/auth/use-auth"
import type { OverallReconciliation, ReconciliationResult } from "@/types/reconciliation"
import type { ApiError } from "@/types/api"

export const RECONCILIATION_KEYS = {
  all: ["reconciliation"] as const,
  overall: () => [...RECONCILIATION_KEYS.all, "overall"] as const,
  account: (accountId: string) => [...RECONCILIATION_KEYS.all, "account", accountId] as const,
}

export function useReconciliation(options?: { enabled?: boolean }) {
  const { isAuthenticated } = useAuth()

  return useQuery<OverallReconciliation, ApiError>({
    queryKey: RECONCILIATION_KEYS.overall(),
    queryFn: () => reconciliationApi.reconcileUserAccounts(),
    enabled: isAuthenticated && (options?.enabled ?? true),
  })
}

export function useAccountReconciliation(accountId: string, options?: { enabled?: boolean }) {
  const { isAuthenticated } = useAuth()

  return useQuery<ReconciliationResult, ApiError>({
    queryKey: RECONCILIATION_KEYS.account(accountId),
    queryFn: () => reconciliationApi.reconcileAccount(accountId),
    enabled: isAuthenticated && Boolean(accountId) && (options?.enabled ?? true),
  })
}
