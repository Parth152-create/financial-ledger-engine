"use client"

import { useQuery } from "@tanstack/react-query"
import { statementsApi } from "@/lib/api/statements"
import type {
  AccountStatementResponse,
  AccountStatementParams,
} from "@/types/statement"
import type { ApiError } from "@/types/api"

export const STATEMENT_KEYS = {
  all: ["statements"] as const,
  byAccount: (accountId: string, params?: AccountStatementParams) =>
    [...STATEMENT_KEYS.all, "account", accountId, params] as const,
}

export function useAccountStatement(
  accountId: string,
  params?: AccountStatementParams,
  options?: { enabled?: boolean }
) {
  return useQuery<AccountStatementResponse, ApiError>({
    queryKey: STATEMENT_KEYS.byAccount(accountId, params),
    queryFn: () => statementsApi.getAccountStatement(accountId, params),
    enabled: Boolean(accountId) && (options?.enabled ?? true),
  })
}
