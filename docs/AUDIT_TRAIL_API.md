# Audit Trail / Operational Audit Events API Documentation

The Audit Trail API provides an immutable, authorized, and queryable record of operational events across the Financial Ledger Engine.

---

## 1. Core Conceptual Separation

The platform enforces a strict boundary between three distinct layers of operational and financial information:

| Layer | Primary Purpose | Authority & Storage | Mutability | Examples |
|---|---|---|---|---|
| **Application Logs** | Diagnostics, debugging, performance troubleshooting, correlation tracing. | Ephemeral log files, console, stdout/stderr. | Volatile | HTTP requests, SQL latency, cache hits, stack traces. |
| **Audit Events** | Operational accountability: answers *"Who did what, to which entity, and when?"* | PostgreSQL `audit_events` table. | Immutable (enforced via database trigger) | `AUTH_LOGIN`, `ACCOUNT_FROZEN`, `TRANSFER_COMPLETED`. |
| **Financial Ledger** | Pure financial state and historical money movement under double-entry bookkeeping rules. | PostgreSQL `ledger_entries` and `transactions` tables. | Immutable (corrections via compensating transactions) | `DEBIT` ₹5,000, `CREDIT` ₹5,000. |

> [!IMPORTANT]
> The Audit Trail is **NOT** a financial ledger, nor does it replace the double-entry accounting ledger. It serves strictly as an operational history of lifecycle and security events.

---

## 2. Transactional Invariants & Atomicity

For financial operations (`TRANSFER_COMPLETED`, `DEPOSIT_COMPLETED`, `WITHDRAWAL_COMPLETED`), the audit event **participates directly in the same PostgreSQL transaction** as the financial mutation:

```text
BEGIN TRANSACTION
  ├── 1. Lock accounts in deterministic order (pessimistic row-level lock)
  ├── 2. Validate balances and account states (ACTIVE)
  ├── 3. Mutate account snapshot balances
  ├── 4. Insert transaction record
  ├── 5. Insert double-entry ledger entries (DEBIT & CREDIT)
  └── 6. Insert audit event (e.g. TRANSFER_COMPLETED)
COMMIT;
```

If any failure occurs (e.g., insufficient balance, account frozen, concurrent lock acquisition timeout), the entire transaction rolls back:
- Account balance changes are reverted.
- Double-entry ledger entries disappear.
- Transaction record disappears.
- **Audit event disappears.**

Under no circumstances will a financial completion audit event exist without its corresponding financial transaction having successfully committed.

### Idempotency & Replay Safety
When an idempotent financial request is replayed with the same `Idempotency-Key`:
- The service returns the cached transaction response.
- **No duplicate audit event is created.**
- A database-level partial unique constraint (`uk_audit_events_transaction_completion`) guarantees that at most one completion audit event can exist per transaction ID.

---

## 3. Database Schema

Migration `V10__create_audit_events_schema.sql` creates the `audit_events` table and associated safety constraints:

```sql
CREATE TABLE audit_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_user_id UUID,
    event_type VARCHAR(64) NOT NULL,
    entity_type VARCHAR(32) NOT NULL,
    entity_id UUID NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    ip_address VARCHAR(45),
    user_agent VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_audit_events_event_type CHECK (
        event_type IN (
            'AUTH_SIGNUP', 'AUTH_LOGIN', 'AUTH_LOGOUT', 'PASSWORD_CHANGED',
            'ACCOUNT_CREATED', 'ACCOUNT_FROZEN', 'ACCOUNT_UNFROZEN', 'ACCOUNT_CLOSED',
            'TRANSFER_COMPLETED', 'DEPOSIT_COMPLETED', 'WITHDRAWAL_COMPLETED'
        )
    ),
    CONSTRAINT chk_audit_events_entity_type CHECK (
        entity_type IN ('USER', 'ACCOUNT', 'TRANSACTION', 'SYSTEM')
    )
);
```

### Immutability Trigger
Updates and deletions on the `audit_events` table are forbidden at the database level:

```sql
CREATE OR REPLACE FUNCTION prevent_audit_event_modification()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Audit events are immutable and cannot be updated or deleted';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_events_immutable
BEFORE UPDATE OR DELETE ON audit_events
FOR EACH ROW
EXECUTE FUNCTION prevent_audit_event_modification();
```

---

## 4. Security, Privacy, and Authorization

### Authorization Scopes
- **Standard Users**: Can only view audit events within their domain ownership scope:
  - Events where they are the actor (`actor_user_id`).
  - Events targeting their user entity (`entity_type = 'USER' AND entity_id = user.id`).
  - Events on accounts they own (`entity_type = 'ACCOUNT' AND entity_id IN (user_accounts)`).
  - Events on transactions where they are the sender or recipient (`entity_type = 'TRANSACTION' AND entity_id IN (user_transactions)`).
- **Administrators** (`ROLE_ADMIN`): Can inspect audit events across all accounts, transactions, and users.

### Privacy Redaction
To protect client privacy and adhere to the principle of least privilege:
- Client IP addresses and User-Agents are captured internally for operational forensics.
- The public `AuditEventResponseDto` **intentionally redacts** `ipAddress`, `userAgent`, and any internal system identifiers.
- Metadata is sanitized prior to persistence: passwords, tokens, secret keys, cookies, and authorization headers are strictly prohibited.

---

## 5. REST API Reference

### `GET /api/v1/audit-events`

Retrieves a paginated list of operational audit events for the authenticated caller.

#### Query Parameters

| Parameter | Type | Required | Description |
|---|---|---|---|
| `eventType` | `string` | No | Filter by specific event type (e.g., `TRANSFER_COMPLETED`, `AUTH_LOGIN`). |
| `entityType` | `string` | No | Filter by target entity (`USER`, `ACCOUNT`, `TRANSACTION`, `SYSTEM`). |
| `from` | `string` | No | ISO-8601 start timestamp, inclusive (e.g., `2026-09-01T00:00:00Z`). |
| `to` | `string` | No | ISO-8601 end timestamp, exclusive (e.g., `2026-09-30T23:59:59Z`). |
| `page` | `integer` | No | Zero-based page index (default: `0`). |
| `size` | `integer` | No | Number of records per page (default: `20`, max: `100`). |

#### Supported Event Types

| Event Type | Entity Type | Trigger / Meaning |
|---|---|---|
| `AUTH_SIGNUP` | `USER` | New user registered. |
| `AUTH_LOGIN` | `USER` | User successfully authenticated. |
| `AUTH_LOGOUT` | `USER` | User session terminated. |
| `PASSWORD_CHANGED` | `USER` | User password updated or linked. |
| `ACCOUNT_CREATED` | `ACCOUNT` | New bank/checking account opened. |
| `ACCOUNT_FROZEN` | `ACCOUNT` | Account administratively frozen. |
| `ACCOUNT_UNFROZEN` | `ACCOUNT` | Account returned to active status. |
| `ACCOUNT_CLOSED` | `ACCOUNT` | Account permanently closed. |
| `TRANSFER_COMPLETED` | `TRANSACTION` | Internal double-entry transfer completed. |
| `DEPOSIT_COMPLETED` | `TRANSACTION` | External clearing deposit completed. |
| `WITHDRAWAL_COMPLETED` | `TRANSACTION` | External clearing withdrawal completed. |

---

### Success Response

#### `200 OK`

```json
{
  "content": [
    {
      "id": "c1f7a01d-5b32-47f1-8f4d-176318991201",
      "eventType": "TRANSFER_COMPLETED",
      "entityType": "TRANSACTION",
      "entityId": "a92b3c4d-5e6f-7a8b-9c0d-1e2f3a4b5c6d",
      "createdAt": "2026-09-29T10:15:30.450123Z",
      "metadata": {
        "amount": "2500.0000",
        "currency": "INR",
        "sourceAccountId": "e0a1b2c3-d4e5-f6a7-b8c9-d0e1f2a3b4c5",
        "destinationAccountId": "f1a2b3c4-d5e6-f7a8-b9c0-d1e2f3a4b5c6",
        "description": "Invoice settlement"
      }
    },
    {
      "id": "e2a3b4c5-d6e7-f8a9-b0c1-d2e3f4a5b6c7",
      "eventType": "ACCOUNT_CREATED",
      "entityType": "ACCOUNT",
      "entityId": "e0a1b2c3-d4e5-f6a7-b8c9-d0e1f2a3b4c5",
      "createdAt": "2026-09-29T09:00:12.100500Z",
      "metadata": {
        "accountType": "USER_CHECKING",
        "currency": "INR"
      }
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

### Error Responses

#### `401 Unauthorized`
Session cookie missing, expired, or invalid.
```json
{
  "status": 401,
  "error": "Unauthorized",
  "message": "Authentication required to access audit trail records."
}
```

#### `400 Bad Request`
Invalid query parameter (e.g. malformed ISO-8601 timestamp or unrecognized event type).
```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "Invalid eventType: UNKNOWN_EVENT"
}
```
