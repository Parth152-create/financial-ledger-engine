"use client"

import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query"
import { policiesApi } from "@/lib/api/policies"
import { useAuth } from "@/hooks/auth/use-auth"
import { ACCOUNT_KEYS } from "@/hooks/api/use-accounts"
import type {
  FinancialPolicy,
  CreatePolicyRequest,
  UpdatePolicyRequest,
  PolicyScope,
} from "@/types/policy"
import type { ApiError } from "@/types/api"

export const POLICY_KEYS = {
  all: ["admin", "policies"] as const,
  lists: (params?: { accountId?: string; policyScope?: PolicyScope }) =>
    [...POLICY_KEYS.all, "list", params || {}] as const,
  detail: (id: string) => [...POLICY_KEYS.all, "detail", id] as const,
}

export function useAdminPolicies(params?: { accountId?: string; policyScope?: PolicyScope }) {
  const { isAuthenticated } = useAuth()

  return useQuery<FinancialPolicy[], ApiError>({
    queryKey: POLICY_KEYS.lists(params),
    queryFn: () => policiesApi.listPolicies(params),
    enabled: isAuthenticated,
  })
}

export function useAdminPolicy(id: string) {
  const { isAuthenticated } = useAuth()

  return useQuery<FinancialPolicy, ApiError>({
    queryKey: POLICY_KEYS.detail(id),
    queryFn: () => policiesApi.getPolicy(id),
    enabled: isAuthenticated && Boolean(id),
  })
}

export function useCreatePolicy() {
  const queryClient = useQueryClient()

  return useMutation<FinancialPolicy, ApiError, CreatePolicyRequest>({
    mutationFn: (data: CreatePolicyRequest) => policiesApi.createPolicy(data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: POLICY_KEYS.all })
      queryClient.invalidateQueries({ queryKey: ACCOUNT_KEYS.all })
    },
  })
}

export function useUpdatePolicy() {
  const queryClient = useQueryClient()

  return useMutation<
    FinancialPolicy,
    ApiError,
    { id: string; data: UpdatePolicyRequest }
  >({
    mutationFn: ({ id, data }) => policiesApi.updatePolicy(id, data),
    onSuccess: (updated) => {
      queryClient.invalidateQueries({ queryKey: POLICY_KEYS.all })
      queryClient.invalidateQueries({ queryKey: ACCOUNT_KEYS.all })
      queryClient.setQueryData(POLICY_KEYS.detail(updated.id), updated)
    },
  })
}

export function useDeletePolicy() {
  const queryClient = useQueryClient()

  return useMutation<void, ApiError, string>({
    mutationFn: (id: string) => policiesApi.deletePolicy(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: POLICY_KEYS.all })
      queryClient.invalidateQueries({ queryKey: ACCOUNT_KEYS.all })
    },
  })
}
