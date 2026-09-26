"use client"

import { useMutation, useQueryClient } from "@tanstack/react-query"
import { transfersApi } from "@/lib/api/transfers"
import { ACCOUNT_KEYS } from "@/hooks/api/use-accounts"
import type { TransferRequest, TransferResponse } from "@/types/transaction"
import type { ApiError } from "@/types/api"

export interface ExecuteTransferParams {
  request: TransferRequest
  idempotencyKey: string
}

export function useExecuteTransfer() {
  const queryClient = useQueryClient()

  return useMutation<TransferResponse, ApiError, ExecuteTransferParams>({
    mutationFn: ({ request, idempotencyKey }) =>
      transfersApi.executeTransfer(request, idempotencyKey),
    onSuccess: (data, variables) => {
      // Invalidate all account lists and cached account data
      queryClient.invalidateQueries({ queryKey: ACCOUNT_KEYS.all })

      // Invalidate source and destination account detail caches
      if (variables?.request?.sourceAccountId) {
        queryClient.invalidateQueries({
          queryKey: ACCOUNT_KEYS.detail(variables.request.sourceAccountId),
        })
      }
      if (variables?.request?.destinationAccountId) {
        queryClient.invalidateQueries({
          queryKey: ACCOUNT_KEYS.detail(variables.request.destinationAccountId),
        })
      }

      // Invalidate transaction history and statement queries
      queryClient.invalidateQueries({ queryKey: ["transactions"] })
      queryClient.invalidateQueries({ queryKey: ["statements"] })
    },
  })
}
