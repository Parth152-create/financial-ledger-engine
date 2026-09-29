export type AuditEventType =
  | "AUTH_SIGNUP"
  | "AUTH_LOGIN"
  | "AUTH_LOGOUT"
  | "PASSWORD_CHANGED"
  | "ACCOUNT_CREATED"
  | "ACCOUNT_FROZEN"
  | "ACCOUNT_UNFROZEN"
  | "ACCOUNT_CLOSED"
  | "TRANSFER_COMPLETED"
  | "DEPOSIT_COMPLETED"
  | "WITHDRAWAL_COMPLETED"

export type AuditEntityType = "USER" | "ACCOUNT" | "TRANSACTION" | "SYSTEM"

export interface AuditEvent {
  id: string
  eventType: AuditEventType
  entityType: AuditEntityType
  entityId: string
  createdAt: string
  metadata: Record<string, unknown>
}

export interface AuditEventPageResponse {
  content: AuditEvent[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  first: boolean
  last: boolean
}

export interface AuditEventQueryParams {
  eventType?: string
  entityType?: string
  from?: string
  to?: string
  page?: number
  size?: number
}
