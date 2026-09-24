# Transaction History API Documentation

The Transaction History API provides a read-only, paginated, and filtered view of transactions associated with user-owned retail checking accounts in the Financial Ledger Engine.

> **CORE PRINCIPLE: STRICT READ-ONLY ASSURANCE**
> - This endpoint is strictly read-only.
> - No account balances are modified.
> - No transactions are created or updated.
> - No ledger entries are created or modified.
> - No idempotency state or Redis keys are touched.
> - No withdrawal logic is implemented in this milestone.
> - Running balances and periodic statements are deferred to V8.

---

## Endpoint Specification

### `GET /api/v1/accounts/{accountId}/transactions`

Retrieves a paginated and filtered list of transactions where the specified `USER_CHECKING` account participated as either the source or destination.

### Authentication & Authorization

- **Session / OAuth2**: Requires user authentication.
- **Ownership Verification**: The requested `accountId` must belong to the authenticated user.
- **Account Type Restriction**: The requested account must be a `USER_CHECKING` account.
- **Non-Leakage Guarantee**: Requesting a non-existent account, another user's account, or the platform `SYSTEM_CLEARING` account uniformly returns `404 Not Found` to prevent account enumeration.
- **Unauthenticated Requests**: Return `401 Unauthorized`.

---

## Query Parameters

| Parameter | Type | Required | Default | Allowed Values / Constraints | Description |
|---|---|---|---|---|---|
| `page` | `integer` | No | `0` | `>= 0` | Zero-based page index. Values `< 0` return `400 Bad Request`. |
| `size` | `integer` | No | `20` | `1 <= size <= 100` | Number of items per page. Values `< 1` or `> 100` return `400 Bad Request`. |
| `transactionType` | `string` | No | *All* | `TRANSFER`, `DEPOSIT`, `WITHDRAWAL` | Filter by transaction type (case-insensitive). Invalid values return `400 Bad Request`. |
| `status` | `string` | No | *All* | `PENDING`, `COMPLETED`, `FAILED` | Filter by execution status (case-insensitive). Invalid values return `400 Bad Request`. |
| `from` | `string` | No | *None* | ISO-8601 Instant (e.g. `2026-09-24T00:00:00Z`) | Inclusive start boundary (`createdAt >= from`). Invalid format returns `400 Bad Request`. |
| `to` | `string` | No | *None* | ISO-8601 Instant (e.g. `2026-09-24T23:59:59Z`) | Exclusive end boundary (`createdAt < to`). Invalid format returns `400 Bad Request`. |

> **Timestamp Validation**: If both `from` and `to` are supplied, `from <= to` must hold. If `from > to`, the API returns `400 Bad Request`.

---

## Direction Semantics

To provide a clear accounting perspective relative to the requested account, each returned item includes a calculated `direction` field:

| Direction | Condition | Description |
|---|---|---|
| `DEBIT` | `requestedAccountId == sourceAccountId` | Funds moved out of the requested account (e.g., outgoing transfer or future withdrawal). |
| `CREDIT` | `requestedAccountId == destinationAccountId` | Funds moved into the requested account (e.g., incoming transfer or deposit from `SYSTEM_CLEARING`). |

### Concrete Rules by Transaction Type

1. **TRANSFER**:
   - Caller account is source: `DEBIT`
   - Caller account is destination: `CREDIT`
2. **DEPOSIT**:
   - Source is `SYSTEM_CLEARING`, destination is caller account: `CREDIT`
3. **WITHDRAWAL** (Domain reserved, deferred to V9):
   - Source is caller account: `DEBIT`

---

## Sorting & Determinism

All queries are deterministically sorted at the database level:

```sql
ORDER BY created_at DESC, id DESC
```

- **Newest First**: Transactions appear in reverse chronological order.
- **Deterministic Tie-Breaking**: Transactions sharing identical timestamps are stably ordered by transaction UUID in descending order (`id DESC`).
- **No Dynamic Sorting**: Arbitrary client-provided sort parameters are disallowed to protect database query plans and index usage.

---

## Performance & Indexing

The endpoint executes database-backed pagination using Spring Data JPA specifications. No in-memory slicing or full-table scans occur. Queries leverage existing PostgreSQL composite indexes created in V4:

- `idx_transactions_source_account_id_created_at (source_account_id, created_at)`
- `idx_transactions_destination_account_id_created_at (destination_account_id, created_at)`

PostgreSQL executes a `BitmapOr` scan combining both indexes, maintaining logarithmic index traversal performance even across large transaction volumes. Count queries omit entity join fetches to minimize database overhead.

---

## Response Structure

```json
{
  "content": [
    {
      "transactionId": "68d7fd9e-9a9b-401b-96ff-5a4398a7d857",
      "transactionType": "TRANSFER",
      "direction": "DEBIT",
      "sourceAccountId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
      "destinationAccountId": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
      "amount": 25.5000,
      "currency": "USD",
      "description": "Lunch split",
      "status": "COMPLETED",
      "initiatedByUserId": "c3d4e5f6-a7b8-9012-cdef-123456789012",
      "createdAt": "2026-09-24T12:00:00Z",
      "completedAt": "2026-09-24T12:00:01Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1,
  "first": true,
  "last": true
}
```

### Response Field Descriptions

| Field | Type | Description |
|---|---|---|
| `content` | `array` | List of transaction history items for the requested page |
| `content[].transactionId` | `UUID` | Unique identifier of the transaction |
| `content[].transactionType` | `string` | Transaction classification: `TRANSFER`, `DEPOSIT`, or `WITHDRAWAL` |
| `content[].direction` | `string` | Account-relative financial flow: `DEBIT` or `CREDIT` |
| `content[].sourceAccountId` | `UUID` | Debited account ID |
| `content[].destinationAccountId` | `UUID` | Credited account ID |
| `content[].amount` | `BigDecimal` | Monetary transaction amount (up to 4 decimal places) |
| `content[].currency` | `string` | 3-character ISO 4217 currency code |
| `content[].description` | `string` | Optional human-readable memo or reference |
| `content[].status` | `string` | Transaction lifecycle status: `PENDING`, `COMPLETED`, `FAILED` |
| `content[].initiatedByUserId` | `UUID` | User who initiated the transaction (nullable for system deposits) |
| `content[].createdAt` | `string` | ISO-8601 UTC creation timestamp |
| `content[].completedAt` | `string` | ISO-8601 UTC completion timestamp (nullable if not yet completed) |
| `page` | `integer` | Current zero-based page number |
| `size` | `integer` | Page size limit |
| `totalElements` | `long` | Total number of transactions matching the query across all pages |
| `totalPages` | `integer` | Total number of pages available |
| `first` | `boolean` | `true` if current page is the first page |
| `last` | `boolean` | `true` if current page is the last page |

---

## Example Requests & Responses

### 1. Simple History Request (Default Pagination)

**Request**:
```http
GET /api/v1/accounts/a1b2c3d4-e5f6-7890-abcd-ef1234567890/transactions HTTP/1.1
Host: api.ledger.com
Cookie: JSESSIONID=...
```

**Response**: `200 OK`
```json
{
  "content": [
    {
      "transactionId": "d4e5f6a7-b8c9-0123-def4-567890abcdef",
      "transactionType": "TRANSFER",
      "direction": "DEBIT",
      "sourceAccountId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
      "destinationAccountId": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
      "amount": 50.0000,
      "currency": "USD",
      "description": "Coffee supplies",
      "status": "COMPLETED",
      "initiatedByUserId": "c3d4e5f6-a7b8-9012-cdef-123456789012",
      "createdAt": "2026-09-24T14:30:00Z",
      "completedAt": "2026-09-24T14:30:01Z"
    },
    {
      "transactionId": "c3d4e5f6-a7b8-9012-cdef-123456789012",
      "transactionType": "DEPOSIT",
      "direction": "CREDIT",
      "sourceAccountId": "00000000-0000-0000-0000-000000000001",
      "destinationAccountId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
      "amount": 1000.0000,
      "currency": "USD",
      "description": "Initial funding",
      "status": "COMPLETED",
      "initiatedByUserId": null,
      "createdAt": "2026-09-24T09:00:00Z",
      "completedAt": "2026-09-24T09:00:01Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 2,
  "totalPages": 1,
  "first": true,
  "last": true
}
```

---

### 2. Paginated Request

**Request**:
```http
GET /api/v1/accounts/a1b2c3d4-e5f6-7890-abcd-ef1234567890/transactions?page=1&size=2 HTTP/1.1
Host: api.ledger.com
Cookie: JSESSIONID=...
```

**Response**: `200 OK`
```json
{
  "content": [
    {
      "transactionId": "b1c2d3e4-f5a6-7890-abcd-ef0123456789",
      "transactionType": "TRANSFER",
      "direction": "CREDIT",
      "sourceAccountId": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
      "destinationAccountId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
      "amount": 15.0000,
      "currency": "USD",
      "description": "Reimbursement",
      "status": "COMPLETED",
      "initiatedByUserId": "e5f6a7b8-c901-2345-def0-123456789abc",
      "createdAt": "2026-09-24T11:00:00Z",
      "completedAt": "2026-09-24T11:00:01Z"
    },
    {
      "transactionId": "a0b1c2d3-e4f5-6789-0abc-def123456789",
      "transactionType": "DEPOSIT",
      "direction": "CREDIT",
      "sourceAccountId": "00000000-0000-0000-0000-000000000001",
      "destinationAccountId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
      "amount": 250.0000,
      "currency": "USD",
      "description": "Supplemental funding",
      "status": "COMPLETED",
      "initiatedByUserId": null,
      "createdAt": "2026-09-24T08:00:00Z",
      "completedAt": "2026-09-24T08:00:01Z"
    }
  ],
  "page": 1,
  "size": 2,
  "totalElements": 5,
  "totalPages": 3,
  "first": false,
  "last": false
}
```

---

### 3. Filtered Request (Type, Status, and Timestamp Range)

**Request**:
```http
GET /api/v1/accounts/a1b2c3d4-e5f6-7890-abcd-ef1234567890/transactions?transactionType=TRANSFER&status=COMPLETED&from=2026-09-24T10:00:00Z&to=2026-09-24T16:00:00Z HTTP/1.1
Host: api.ledger.com
Cookie: JSESSIONID=...
```

**Response**: `200 OK`
```json
{
  "content": [
    {
      "transactionId": "d4e5f6a7-b8c9-0123-def4-567890abcdef",
      "transactionType": "TRANSFER",
      "direction": "DEBIT",
      "sourceAccountId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
      "destinationAccountId": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
      "amount": 50.0000,
      "currency": "USD",
      "description": "Coffee supplies",
      "status": "COMPLETED",
      "initiatedByUserId": "c3d4e5f6-a7b8-9012-cdef-123456789012",
      "createdAt": "2026-09-24T14:30:00Z",
      "completedAt": "2026-09-24T14:30:01Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1,
  "first": true,
  "last": true
}
```

---

### 4. Empty History Response

**Request**:
```http
GET /api/v1/accounts/a1b2c3d4-e5f6-7890-abcd-ef1234567890/transactions?status=FAILED HTTP/1.1
Host: api.ledger.com
Cookie: JSESSIONID=...
```

**Response**: `200 OK`
```json
{
  "content": [],
  "page": 0,
  "size": 20,
  "totalElements": 0,
  "totalPages": 0,
  "first": true,
  "last": true
}
```

---

## Error Responses

| Scenario | HTTP Status | Example Body |
|---|---|---|
| Unauthenticated request | `401 Unauthorized` | `{"status": 401, "error": "Unauthorized", "message": "Authentication required", "path": "..."}` |
| Non-existent account, other user's account, or `SYSTEM_CLEARING` | `404 Not Found` | `{"status": 404, "error": "Not Found", "message": "Account not found: ...", "path": "..."}` |
| Malformed UUID in path | `400 Bad Request` | `{"status": 400, "error": "Bad Request", "message": "Invalid parameter value for 'accountId': not-a-valid-uuid", "path": "..."}` |
| Page size > 100 or < 1 | `400 Bad Request` | `{"status": 400, "error": "Bad Request", "message": "Page size must not exceed 100: 101", "path": "..."}` |
| Negative page number | `400 Bad Request` | `{"status": 400, "error": "Bad Request", "message": "Page index must not be negative: -1", "path": "..."}` |
| Invalid `transactionType` or `status` | `400 Bad Request` | `{"status": 400, "error": "Bad Request", "message": "Invalid transaction type: INVALID", "path": "..."}` |
| Malformed ISO-8601 timestamp | `400 Bad Request` | `{"status": 400, "error": "Bad Request", "message": "Invalid ISO-8601 'from' timestamp: ...", "path": "..."}` |
| `from > to` | `400 Bad Request` | `{"status": 400, "error": "Bad Request", "message": "'from' timestamp (...) must be before or equal to 'to' timestamp (...)", "path": "..."}` |
