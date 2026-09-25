export interface ApiErrorResponse {
  timestamp?: string
  status: number
  error: string
  message: string
  path?: string
  details?: string[] | null
}

export class ApiError extends Error {
  readonly status: number
  readonly error: string
  readonly details?: string[] | null
  readonly path?: string

  constructor(data: ApiErrorResponse) {
    super(data.message || data.error || "An unexpected error occurred")
    this.name = "ApiError"
    this.status = data.status
    this.error = data.error
    this.details = data.details
    this.path = data.path
  }
}
