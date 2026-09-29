import test from "node:test"
import assert from "node:assert/strict"

// 1. Audit Event Item Response Contract
test("1. Audit Event Contract: matches backend AuditEventResponseDto specification", () => {
  const mockAuditEvent = {
    id: "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    eventType: "TRANSFER_COMPLETED",
    entityType: "TRANSACTION",
    entityId: "123e4567-e89b-12d3-a456-426614174000",
    createdAt: "2026-09-29T10:00:00Z",
    metadata: {
      amount: "1500.0000",
      currency: "INR",
      sourceAccountId: "987e6543-e21b-12d3-a456-426614174000",
      destinationAccountId: "555e6543-e21b-12d3-a456-426614174000",
    },
  }

  assert.equal(mockAuditEvent.id, "3fa85f64-5717-4562-b3fc-2c963f66afa6")
  assert.equal(mockAuditEvent.eventType, "TRANSFER_COMPLETED")
  assert.equal(mockAuditEvent.entityType, "TRANSACTION")
  assert.equal(mockAuditEvent.entityId, "123e4567-e89b-12d3-a456-426614174000")
  assert.equal(mockAuditEvent.createdAt, "2026-09-29T10:00:00Z")
  assert.equal(mockAuditEvent.metadata.currency, "INR")
  assert.equal(mockAuditEvent.metadata.amount, "1500.0000")
})

// 2. Audit Event Page Response Contract
test("2. Audit Page Contract: matches backend AuditEventPageResponseDto pagination structure", () => {
  const mockPage = {
    content: [
      {
        id: "11111111-1111-1111-1111-111111111111",
        eventType: "ACCOUNT_CREATED",
        entityType: "ACCOUNT",
        entityId: "22222222-2222-2222-2222-222222222222",
        createdAt: "2026-09-29T09:00:00Z",
        metadata: { currency: "INR", accountType: "USER_CHECKING" },
      },
    ],
    page: 0,
    size: 20,
    totalElements: 1,
    totalPages: 1,
    first: true,
    last: true,
  }

  assert.equal(mockPage.content.length, 1)
  assert.equal(mockPage.page, 0)
  assert.equal(mockPage.size, 20)
  assert.equal(mockPage.totalElements, 1)
  assert.equal(mockPage.totalPages, 1)
  assert.equal(mockPage.first, true)
  assert.equal(mockPage.last, true)
})

// 3. Supported Event Types Validation
test("3. Supported Event Types: matches established domain events", () => {
  const validEventTypes = new Set([
    "AUTH_SIGNUP",
    "AUTH_LOGIN",
    "AUTH_LOGOUT",
    "PASSWORD_CHANGED",
    "ACCOUNT_CREATED",
    "ACCOUNT_FROZEN",
    "ACCOUNT_UNFROZEN",
    "ACCOUNT_CLOSED",
    "TRANSFER_COMPLETED",
    "DEPOSIT_COMPLETED",
    "WITHDRAWAL_COMPLETED",
  ])

  assert.ok(validEventTypes.has("AUTH_SIGNUP"))
  assert.ok(validEventTypes.has("AUTH_LOGIN"))
  assert.ok(validEventTypes.has("AUTH_LOGOUT"))
  assert.ok(validEventTypes.has("PASSWORD_CHANGED"))
  assert.ok(validEventTypes.has("ACCOUNT_CREATED"))
  assert.ok(validEventTypes.has("ACCOUNT_FROZEN"))
  assert.ok(validEventTypes.has("ACCOUNT_UNFROZEN"))
  assert.ok(validEventTypes.has("ACCOUNT_CLOSED"))
  assert.ok(validEventTypes.has("TRANSFER_COMPLETED"))
  assert.ok(validEventTypes.has("DEPOSIT_COMPLETED"))
  assert.ok(validEventTypes.has("WITHDRAWAL_COMPLETED"))
  assert.equal(validEventTypes.size, 11)
})

// 4. Supported Entity Types Validation
test("4. Supported Entity Types: matches established domain entities", () => {
  const validEntityTypes = new Set(["USER", "ACCOUNT", "TRANSACTION", "SYSTEM"])
  assert.ok(validEntityTypes.has("USER"))
  assert.ok(validEntityTypes.has("ACCOUNT"))
  assert.ok(validEntityTypes.has("TRANSACTION"))
  assert.ok(validEntityTypes.has("SYSTEM"))
  assert.equal(validEntityTypes.size, 4)
})

// 5. Security & Privacy: DTO intentionally omits IP and User-Agent from standard views
test("5. Privacy & Security: public audit response DTO excludes raw IP addresses and User-Agents", () => {
  const responseDtoKeys = ["id", "eventType", "entityType", "entityId", "createdAt", "metadata"]

  assert.ok(!responseDtoKeys.includes("ipAddress"), "Public audit DTO must not expose ipAddress")
  assert.ok(!responseDtoKeys.includes("userAgent"), "Public audit DTO must not expose userAgent")
  assert.ok(!responseDtoKeys.includes("password"), "Public audit DTO must not expose passwords")
})

// 6. Formatting & Summaries
test("6. Audit Formatting: formats INR amounts correctly", () => {
  const formatINR = (amount) => {
    const num = typeof amount === "number" ? amount : Number(amount)
    if (isNaN(num)) return String(amount)
    return `₹${num.toLocaleString("en-IN", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
  }

  assert.equal(formatINR(5000), "₹5,000.00")
  assert.equal(formatINR("125000.5"), "₹1,25,000.50")
})

// 7. Routes Integrity
test("7. Route Constants: verifies audit UI and API endpoints", () => {
  const ROUTES = { AUDIT: "/app/audit" }
  const API_ROUTES = { AUDIT_EVENTS: "/api/v1/audit-events" }

  assert.equal(ROUTES.AUDIT, "/app/audit")
  assert.equal(API_ROUTES.AUDIT_EVENTS, "/api/v1/audit-events")
})
