"use client"

import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query"
import { recurringApi } from "@/lib/api/recurring"
import type {
  RecurringTransfer,
  CreateRecurringTransferRequest,
  RecurringTransferPageResponse,
  RecurringExecutionPageResponse,
  RecurringTransferStatus,
} from "@/types/recurring"
import type { ApiError } from "@/types/api"

export const RECURRING_KEYS = {
  all: ["recurring-transfers"] as const,
  lists: () => [...RECURRING_KEYS.all, "list"] as const,
  list: (params?: { status?: RecurringTransferStatus; page?: number; size?: number }) =>
    [...RECURRING_KEYS.lists(), params] as const,
  details: () => [...RECURRING_KEYS.all, "detail"] as const,
  detail: (id: string) => [...RECURRING_KEYS.details(), id] as const,
  executions: (id: string, params?: { page?: number; size?: number }) =>
    [...RECURRING_KEYS.detail(id), "executions", params] as const,
}

export function useRecurringTransfers(params?: {
  status?: RecurringTransferStatus
  page?: number
  size?: number
}) {
  return useQuery<RecurringTransferPageResponse, ApiError>({
    queryKey: RECURRING_KEYS.list(params),
    queryFn: () => recurringApi.listSchedules(params),
    refetchInterval: 5000, // periodic refresh to observe executions
  })
}

export function useRecurringTransfer(id: string | null) {
  return useQuery<RecurringTransfer, ApiError>({
    queryKey: RECURRING_KEYS.detail(id ?? ""),
    queryFn: () => recurringApi.getSchedule(id!),
    enabled: Boolean(id),
  })
}

export function useRecurringTransferExecutions(
  id: string | null,
  params?: { page?: number; size?: number }
) {
  return useQuery<RecurringExecutionPageResponse, ApiError>({
    queryKey: RECURRING_KEYS.executions(id ?? "", params),
    queryFn: () => recurringApi.listExecutions(id!, params),
    enabled: Boolean(id),
    refetchInterval: 5000,
  })
}

export function useCreateRecurringTransfer() {
  const queryClient = useQueryClient()

  return useMutation<RecurringTransfer, ApiError, CreateRecurringTransferRequest>({
    mutationFn: (data) => recurringApi.createSchedule(data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: RECURRING_KEYS.all })
    },
  })
}

export function usePauseRecurringTransfer() {
  const queryClient = useQueryClient()

  return useMutation<RecurringTransfer, ApiError, string>({
    mutationFn: (id) => recurringApi.pauseSchedule(id),
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: RECURRING_KEYS.all })
      queryClient.setQueryData(RECURRING_KEYS.detail(data.id), data)
    },
  })
}

export function useResumeRecurringTransfer() {
  const queryClient = useQueryClient()

  return useMutation<RecurringTransfer, ApiError, string>({
    mutationFn: (id) => recurringApi.resumeSchedule(id),
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: RECURRING_KEYS.all })
      queryClient.setQueryData(RECURRING_KEYS.detail(data.id), data)
    },
  })
}

export function useCancelRecurringTransfer() {
  const queryClient = useQueryClient()

  return useMutation<RecurringTransfer, ApiError, string>({
    mutationFn: (id) => recurringApi.cancelSchedule(id),
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: RECURRING_KEYS.all })
      queryClient.setQueryData(RECURRING_KEYS.detail(data.id), data)
    },
  })
}
