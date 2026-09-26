"use client"

import { useMutation, useQueryClient } from "@tanstack/react-query"
import { depositsApi } from "@/lib/api/deposits"
import { ACCOUNT_KEYS } from "@/hooks/api/use-accounts"
import type { DepositRequest, DepositResponse } from "@/types/transaction"
import type { ApiError } from "@/types/api"

export interface ExecuteDepositParams {
  request: DepositRequest
  idempotencyKey: string
}

export function useExecuteDeposit() {
  const queryClient = useQueryClient()

  return useMutation<DepositResponse, ApiError, ExecuteDepositParams>({
    mutationFn: ({ request, idempotencyKey }) =>
      depositsApi.executeDeposit(request, idempotencyKey),
    onSuccess: (data, variables) => {
      queryClient.invalidateQueries({ queryKey: ACCOUNT_KEYS.all })
      if (variables?.request?.accountId) {
        queryClient.invalidateQueries({
          queryKey: ACCOUNT_KEYS.detail(variables.request.accountId),
        })
      }
      queryClient.invalidateQueries({ queryKey: ["transactions"] })
      queryClient.invalidateQueries({ queryKey: ["statements"] })
    },
  })
}
