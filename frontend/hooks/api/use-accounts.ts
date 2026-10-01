"use client"

import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query"
import { accountsApi } from "@/lib/api/accounts"
import { useAuth } from "@/hooks/auth/use-auth"
import type { Account, CreateAccountRequest } from "@/types/account"
import type { PolicyLimitSummary } from "@/types/policy"
import type { TransactionType } from "@/types/transaction"
import type { ApiError } from "@/types/api"

export const ACCOUNT_KEYS = {
  all: ["accounts"] as const,
  lists: () => [...ACCOUNT_KEYS.all, "list"] as const,
  detail: (id: string) => [...ACCOUNT_KEYS.all, "detail", id] as const,
  limits: (id: string, transactionType?: string) =>
    [...ACCOUNT_KEYS.detail(id), "limits", transactionType || "all"] as const,
}

export function useAccounts() {
  const { isAuthenticated } = useAuth()

  return useQuery<Account[], ApiError>({
    queryKey: ACCOUNT_KEYS.lists(),
    queryFn: () => accountsApi.listAccounts(),
    enabled: isAuthenticated,
  })
}

export function useAccount(accountId: string) {
  const { isAuthenticated } = useAuth()

  return useQuery<Account, ApiError>({
    queryKey: ACCOUNT_KEYS.detail(accountId),
    queryFn: () => accountsApi.getAccount(accountId),
    enabled: isAuthenticated && Boolean(accountId),
  })
}

export function useAccountLimits(accountId: string) {
  const { isAuthenticated } = useAuth()

  return useQuery<PolicyLimitSummary[], ApiError>({
    queryKey: ACCOUNT_KEYS.limits(accountId),
    queryFn: () => accountsApi.getAccountLimits(accountId),
    enabled: isAuthenticated && Boolean(accountId),
  })
}

export function useAccountLimit(accountId: string, transactionType: TransactionType | string) {
  const { isAuthenticated } = useAuth()

  return useQuery<PolicyLimitSummary, ApiError>({
    queryKey: ACCOUNT_KEYS.limits(accountId, transactionType),
    queryFn: () => accountsApi.getAccountLimit(accountId, transactionType),
    enabled: isAuthenticated && Boolean(accountId) && Boolean(transactionType),
  })
}

export function useCreateAccount() {
  const queryClient = useQueryClient()

  return useMutation<Account, ApiError, CreateAccountRequest>({
    mutationFn: (data: CreateAccountRequest) => accountsApi.createAccount(data),
    onSuccess: (newAccount) => {
      queryClient.invalidateQueries({ queryKey: ACCOUNT_KEYS.all })
      queryClient.setQueryData(ACCOUNT_KEYS.detail(newAccount.accountId), newAccount)
    },
  })
}
