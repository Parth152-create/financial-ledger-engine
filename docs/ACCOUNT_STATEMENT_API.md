# Account Statement & Running Balance API Documentation

The Account Statement API provides an authenticated, read-only, paginated view of an account's financial statement, including opening balances, closing balances, period credit and debit totals, and sequential running balances for each transaction.

> **CORE PRINCIPLE: IMMUTABLE LEDGER DERIVATION**
> 
> "Account statements are derived from immutable ledger entries. The current account balance snapshot is not used to reconstruct historical running balances."
> 
> V7 history is newest-first (`createdAt DESC, id DESC`).
> V8 statements are oldest-first (`createdAt ASC, id ASC`) because running balances require chronological processing.

---

## Endpoint Specification

### `GET /api/v1/accounts/{accountId}/statement`

Retrieves the financial account statement and running balance for a user-owned checking account.

### Authentication & Authorization

- **Authentication**: Requires authenticated security principal via session/OAuth2. Unauthenticated requests receive `401 Unauthorized`.
- **Account Type Restriction**: The requested account must be a `USER_CHECKING` account.
- **Uniform 404 Response**: Non-existent accounts, accounts owned by other users, and the platform `SYSTEM_CLEARING` account return `404 Not Found` to prevent account enumeration.
- **Malformed Input**: Malformed UUIDs in path return `400 Bad Request`.

---

## Query Parameters

| Parameter | Type | Required | Default | Allowed Values / Constraints | Description |
|---|---|---|---|---|---|
| `page` | `integer` | No | `0` | `>= 0` | Zero-based page index. Values `< 0` return `400 Bad Request`. |
| `size` | `integer` | No | `20` | `1 <= size <= 100` | Page size limit. Values `< 1` or `> 100` return `400 Bad Request`. |
| `from` | `string` | No | *None* | ISO-8601 Instant (e.g. `2026-09-01T00:00:00Z`) | Inclusive start boundary (`createdAt >= from`). Invalid format returns `400 Bad Request`. |
| `to` | `string` | No | *None* | ISO-8601 Instant (e.g. `2026-10-01T00:00:00Z`) | Exclusive end boundary (`createdAt < to`). Invalid format returns `400 Bad Request`. |
| `transactionType` | `string` | No | *All* | `TRANSFER`, `DEPOSIT`, `WITHDRAWAL` | Optional filter (case-insensitive). Invalid values return `400 Bad Request`. |
| `status` | `string` | No | *All* | `PENDING`, `COMPLETED`, `FAILED` | Optional filter (case-insensitive). Invalid values return `400 Bad Request`. |

### Date Semantics
- `from` is inclusive (`createdAt >= from`).
- `to` is exclusive (`createdAt < to`).
- If `from > to`: returns `400 Bad Request`.
- If `from == to`: returns an empty statement with zero activity (`entries: []`, `totalCredits: 0.0000`, `totalDebits: 0.0000`, `closingBalance = openingBalance`).

---

## Balance & Accounting Mathematics (True Account Statement Contract)

### 1. Opening Balance (`openingBalance`)
- **Without `from`**: `openingBalance = 0.0000` (represents account inception).
- **With `from`**: `openingBalance` represents the actual ledger-derived account balance immediately before `from`, calculated across **all** ledger history:
  $$\text{openingBalance} = \sum_{t < \text{from}, \text{CREDIT}} \text{amount} - \sum_{t < \text{from}, \text{DEBIT}} \text{amount}$$
- `openingBalance` includes **all** ledger entries before `from`, regardless of any `transactionType` or `status` filters.
- Never derived from `accounts.balance`.

### 2. Closing Balance (`closingBalance`)
- Represents the **actual** ledger-derived account balance at the statement boundary across complete history.
- **With `to`**: All ledger activity strictly prior to `to` (`created_at < to`):
  $$\text{closingBalance} = \sum_{t < \text{to}, \text{CREDIT}} \text{amount} - \sum_{t < \text{to}, \text{DEBIT}} \text{amount}$$
- **Without `to`**: Total cumulative ledger balance across all history:
  $$\text{closingBalance} = \sum_{\text{all}, \text{CREDIT}} \text{amount} - \sum_{\text{all}, \text{DEBIT}} \text{amount}$$
- `closingBalance` is computed independently from full ledger history and is **never** calculated from filtered `totalCredits` or `totalDebits`.

### 3. Filtered Activity Totals (`totalCredits` / `totalDebits`)
- `totalCredits`: Sum of all `CREDIT` ledger entries for the requested account matching query filters in the range $[from, to)$.
- `totalDebits`: Sum of all `DEBIT` ledger entries for the requested account matching query filters in the range $[from, to)$.
- These are activity volume subtotals for the displayed/filtered entries, not balance reconstruction inputs.
- When no `transactionType` or `status` filters are applied, the accounting identity holds:
  $$\text{closingBalance} = \text{openingBalance} + \text{totalCredits} - \text{totalDebits}$$
- When filters are applied, `totalCredits` and `totalDebits` reflect only the matching entries, while `openingBalance` and `closingBalance` remain true account positions.

### 4. Running Balance (`balanceAfter`)
Statement entries are ordered chronologically (`createdAt ASC, id ASC`). For each displayed entry:
- `balanceAfter` represents the **actual** account balance immediately after that transaction occurred in full ledger history.
- It is computed in PostgreSQL via a cumulative sum over all historical ledger entries up to `(created_at, id)`:
  $$\text{balanceAfter} = \sum_{\substack{p \le e \\ \text{CREDIT}}} \text{amount} - \sum_{\substack{p \le e \\ \text{DEBIT}}} \text{amount}$$
- If intermediate transactions are omitted due to `transactionType` or `status` filters, `balanceAfter` still accounts for those hidden transactions, accurately reflecting true account state at that instant.
- Running balances remain mathematically exact across pagination boundaries and never depend on in-memory reconstruction.

---

## Relationship to Reconciliation

- Account statements strictly derive balances from immutable `ledger_entries`.
- For consistent accounts over a full-history range, `closingBalance` matches the stored `accounts.balance` snapshot.
- If an account has a snapshot discrepancy, the statement calculation reflects the authoritative ledger truth without attempting to mutate or "repair" the snapshot.

---

## Response Structure

```json
{
  "accountId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "accountNumber": "ACCT-41B21CF7DEA3",
  "currency": "USD",
  "openingBalance": 1000.0000,
  "entries": [
    {
      "transactionId": "68d7fd9e-9a9b-401b-96ff-5a4398a7d857",
      "transactionType": "TRANSFER",
      "direction": "DEBIT",
      "amount": 200.0000,
      "currency": "USD",
      "description": "Invoice payment",
      "status": "COMPLETED",
      "createdAt": "2026-09-24T10:00:00Z",
      "completedAt": "2026-09-24T10:00:01Z",
      "balanceAfter": 800.0000
    },
    {
      "transactionId": "78d7fd9e-9a9b-401b-96ff-5a4398a7d858",
      "transactionType": "TRANSFER",
      "direction": "CREDIT",
      "amount": 350.0000,
      "currency": "USD",
      "description": "Client payment",
      "status": "COMPLETED",
      "createdAt": "2026-09-24T11:00:00Z",
      "completedAt": "2026-09-24T11:00:01Z",
      "balanceAfter": 1150.0000
    }
  ],
  "closingBalance": 1150.0000,
  "totalCredits": 350.0000,
  "totalDebits": 200.0000,
  "page": 0,
  "size": 20,
  "totalElements": 2,
  "totalPages": 1,
  "first": true,
  "last": true
}
```
