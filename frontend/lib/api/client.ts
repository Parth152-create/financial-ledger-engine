import { ApiError, type ApiErrorResponse } from "@/types/api"

export interface RequestOptions extends Omit<RequestInit, "body"> {
  params?: Record<string, string | number | boolean | undefined | null>
  idempotencyKey?: string
  requestId?: string
}

const BASE_URL = process.env.NEXT_PUBLIC_API_URL || "http://localhost:8085"

function buildUrl(endpoint: string, params?: RequestOptions["params"]): string {
  const url = new URL(endpoint.startsWith("http") ? endpoint : `${BASE_URL}${endpoint}`)
  if (params) {
    Object.entries(params).forEach(([key, value]) => {
      if (value !== undefined && value !== null) {
        url.searchParams.append(key, String(value))
      }
    })
  }
  return url.toString()
}

async function handleResponse<T>(response: Response, path: string): Promise<T> {
  if (!response.ok) {
    let errorData: ApiErrorResponse
    try {
      errorData = await response.json()
    } catch {
      errorData = {
        status: response.status,
        error: response.statusText || "Request Failed",
        message: `HTTP error ${response.status}: ${response.statusText}`,
        path,
      }
    }
    throw new ApiError(errorData)
  }

  if (response.status === 204) {
    return undefined as unknown as T
  }

  const contentType = response.headers.get("content-type")
  if (contentType && contentType.includes("application/json")) {
    return (await response.json()) as T
  }

  return (await response.text()) as unknown as T
}

async function request<T>(
  endpoint: string,
  options: RequestOptions & { method?: string; body?: unknown } = {}
): Promise<T> {
  const { params, idempotencyKey, requestId, headers = {}, body, ...fetchOptions } = options

  const resolvedHeaders: Record<string, string> = {
    Accept: "application/json",
    ...(headers as Record<string, string>),
  }

  if (body !== undefined && !(body instanceof FormData)) {
    resolvedHeaders["Content-Type"] = "application/json"
  }

  if (idempotencyKey) {
    resolvedHeaders["Idempotency-Key"] = idempotencyKey
  }

  if (requestId) {
    resolvedHeaders["X-Request-Id"] = requestId
  } else if (typeof crypto !== "undefined" && crypto.randomUUID) {
    resolvedHeaders["X-Request-Id"] = crypto.randomUUID()
  }

  const url = buildUrl(endpoint, params)

  try {
    const response = await fetch(url, {
      ...fetchOptions,
      credentials: "include",
      headers: resolvedHeaders,
      body: body !== undefined && !(body instanceof FormData) ? JSON.stringify(body) : (body as BodyInit),
    })

    return await handleResponse<T>(response, endpoint)
  } catch (error) {
    if (error instanceof ApiError) {
      throw error
    }
    throw new ApiError({
      status: 0,
      error: "NetworkError",
      message: error instanceof Error ? error.message : "Network connection failed",
      path: endpoint,
    })
  }
}

export const apiClient = {
  get<T>(endpoint: string, options?: RequestOptions): Promise<T> {
    return request<T>(endpoint, { ...options, method: "GET" })
  },

  post<T>(endpoint: string, body?: unknown, options?: RequestOptions): Promise<T> {
    return request<T>(endpoint, { ...options, method: "POST", body })
  },

  put<T>(endpoint: string, body?: unknown, options?: RequestOptions): Promise<T> {
    return request<T>(endpoint, { ...options, method: "PUT", body })
  },

  delete<T>(endpoint: string, options?: RequestOptions): Promise<T> {
    return request<T>(endpoint, { ...options, method: "DELETE" })
  },

  getBaseUrl(): string {
    return BASE_URL
  },
}
