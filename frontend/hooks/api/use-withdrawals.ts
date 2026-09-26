"use client"

import { useMutation, useQueryClient } from "@tanstack/react-query"
import { withdrawalsApi } from "@/lib/api/withdrawals"
import { ACCOUNT_KEYS } from "@/hooks/api/use-accounts"
import type { WithdrawalRequest, WithdrawalResponse } from "@/types/transaction"
import type { ApiError } from "@/types/api"

export interface ExecuteWithdrawalParams {
  request: WithdrawalRequest
  idempotencyKey: string
}

export function useExecuteWithdrawal() {
  const queryClient = useQueryClient()

  return useMutation<WithdrawalResponse, ApiError, ExecuteWithdrawalParams>({
    mutationFn: ({ request, idempotencyKey }) =>
      withdrawalsApi.executeWithdrawal(request, idempotencyKey),
    onSuccess: (data, variables) => {
      queryClient.invalidateQueries({ queryKey: ACCOUNT_KEYS.all })
      if (variables?.request?.accountId) {
        queryClient.invalidateQueries({
          queryKey: ACCOUNT_KEYS.detail(variables.request.accountId),
        })
      }
      queryClient.invalidateQueries({ queryKey: ["transactions"] })
      queryClient.invalidateQueries({ queryKey: ["statements"] })
      queryClient.invalidateQueries({ queryKey: ["reconciliation"] })
    },
  })
}
