"use client"

import { useQuery } from "@tanstack/react-query"
import { transactionsApi } from "@/lib/api/transactions"
import type {
  TransactionHistoryPageResponse,
  TransactionHistoryParams,
} from "@/types/transaction"
import type { ApiError } from "@/types/api"

export const TRANSACTION_KEYS = {
  all: ["transactions"] as const,
  lists: () => [...TRANSACTION_KEYS.all, "list"] as const,
  byAccount: (accountId: string, params?: TransactionHistoryParams) =>
    [...TRANSACTION_KEYS.all, "account", accountId, params] as const,
}

export function useAccountTransactions(
  accountId: string,
  params?: TransactionHistoryParams,
  options?: { enabled?: boolean }
) {
  return useQuery<TransactionHistoryPageResponse, ApiError>({
    queryKey: TRANSACTION_KEYS.byAccount(accountId, params),
    queryFn: () => transactionsApi.getAccountTransactions(accountId, params),
    enabled: Boolean(accountId) && (options?.enabled ?? true),
  })
}
