import { apiClient } from "@/lib/api/client"
import { API_ROUTES } from "@/constants/routes"
import type {
  FinancialPolicy,
  CreatePolicyRequest,
  UpdatePolicyRequest,
  PolicyScope,
} from "@/types/policy"

export const policiesApi = {
  listPolicies(params?: {
    accountId?: string
    policyScope?: PolicyScope
  }): Promise<FinancialPolicy[]> {
    const searchParams = new URLSearchParams()
    if (params?.accountId) {
      searchParams.set("accountId", params.accountId)
    }
    if (params?.policyScope) {
      searchParams.set("policyScope", params.policyScope)
    }
    const qs = searchParams.toString()
    const url = qs ? `${API_ROUTES.ADMIN_POLICIES}?${qs}` : API_ROUTES.ADMIN_POLICIES
    return apiClient.get<FinancialPolicy[]>(url)
  },

  getPolicy(id: string): Promise<FinancialPolicy> {
    return apiClient.get<FinancialPolicy>(API_ROUTES.ADMIN_POLICY_BY_ID(id))
  },

  createPolicy(data: CreatePolicyRequest): Promise<FinancialPolicy> {
    const payload = {
      accountId: data.accountId || null,
      policyScope: data.policyScope || data.scope,
      transactionType: data.transactionType || null,
      policyType: data.policyType,
      amountLimit: data.amountLimit != null ? Number(data.amountLimit) : null,
      countLimit: data.countLimit != null ? Number(data.countLimit) : null,
      currency: data.currency || "INR",
      enabled: data.enabled ?? true,
    }
    return apiClient.post<FinancialPolicy>(API_ROUTES.ADMIN_POLICIES, payload)
  },

  updatePolicy(id: string, data: UpdatePolicyRequest): Promise<FinancialPolicy> {
    const payload = {
      amountLimit: data.amountLimit != null ? Number(data.amountLimit) : null,
      countLimit: data.countLimit != null ? Number(data.countLimit) : null,
      enabled: data.enabled,
    }
    return apiClient.put<FinancialPolicy>(API_ROUTES.ADMIN_POLICY_BY_ID(id), payload)
  },

  deletePolicy(id: string): Promise<void> {
    return apiClient.delete<void>(API_ROUTES.ADMIN_POLICY_BY_ID(id))
  },
}
