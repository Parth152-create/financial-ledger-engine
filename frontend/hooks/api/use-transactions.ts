"use client"

import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query"
import { transactionsApi } from "@/lib/api/transactions"
import { ACCOUNT_KEYS } from "@/hooks/api/use-accounts"
import type {
  TransactionHistoryPageResponse,
  TransactionHistoryParams,
  TransactionResponse,
  ReversalRequest,
  ReversalResponse,
} from "@/types/transaction"
import type { ApiError } from "@/types/api"

export const TRANSACTION_KEYS = {
  all: ["transactions"] as const,
  lists: () => [...TRANSACTION_KEYS.all, "list"] as const,
  byAccount: (accountId: string, params?: TransactionHistoryParams) =>
    [...TRANSACTION_KEYS.all, "account", accountId, params] as const,
  detail: (transactionId: string) =>
    [...TRANSACTION_KEYS.all, "detail", transactionId] as const,
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

export function useTransaction(
  transactionId: string,
  options?: { enabled?: boolean }
) {
  return useQuery<TransactionResponse, ApiError>({
    queryKey: TRANSACTION_KEYS.detail(transactionId),
    queryFn: () => transactionsApi.getTransaction(transactionId),
    enabled: Boolean(transactionId) && (options?.enabled ?? true),
  })
}

export interface ReverseTransactionParams {
  transactionId: string
  request?: ReversalRequest
  idempotencyKey?: string
}

export function useReverseTransaction() {
  const queryClient = useQueryClient()

  return useMutation<ReversalResponse, ApiError, ReverseTransactionParams>({
    mutationFn: ({ transactionId, request, idempotencyKey }) =>
      transactionsApi.reverseTransaction(transactionId, request, idempotencyKey),
    onSuccess: () => {
      // Invalidate all related financial and operational caches
      queryClient.invalidateQueries({ queryKey: ACCOUNT_KEYS.all })
      queryClient.invalidateQueries({ queryKey: ["transactions"] })
      queryClient.invalidateQueries({ queryKey: ["statements"] })
      queryClient.invalidateQueries({ queryKey: ["reconciliation"] })
      queryClient.invalidateQueries({ queryKey: ["audit-events"] })
      queryClient.invalidateQueries({ queryKey: ["analytics"] })
    },
  })
}
