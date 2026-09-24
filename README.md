# Financial Ledger Engine

A high-throughput financial ledger engine focused on double-entry accounting, transactional consistency, concurrency control, idempotency, reconciliation, and measurable performance.

## Architecture

- Spring Boot backend
- PostgreSQL as the financial source of truth
- Redis for auxiliary idempotency and fast-path operations
- Next.js frontend
- JMeter for load testing

## Core Principles

- Atomic financial transactions
- Double-entry ledger
- Immutable ledger entries
- Precise decimal monetary calculations
- Database-level concurrency control
- Idempotent transaction processing
- Balance-to-ledger reconciliation
- Measurable performance under load

## Project Structure

```text
financial-ledger-engine/
├── backend/
├── frontend/
├── load-testing/
├── docs/
└── database/
```

## Security & Authentication

- **Google OAuth2**: Authenticates user identity via Google OpenID Connect / OAuth2. Authenticated emails map to PostgreSQL `users`.
- **Account Ownership Authorization**: Transfer operations strictly verify that the authenticated caller owns the source account being debited. Unauthorized debit attempts are rejected with HTTP 403 Forbidden.
- **Local OAuth Configuration**:
  ```bash
  export GOOGLE_CLIENT_ID=your-google-client-id
  export GOOGLE_CLIENT_SECRET=your-google-client-secret
  ```
  Automated tests mock authentication and do not require external Google credentials or network access.

## Documentation

- [Account Model & Database Hardening (V2)](docs/ACCOUNT_MODEL.md)
- [Transaction & Ledger Model Hardening (V3)](docs/TRANSACTION_MODEL.md)
- [Database Integrity, Indexing & Ledger Immutability (V4)](docs/DATABASE_INTEGRITY.md)
- [Account Management API Specification (V5)](docs/ACCOUNT_API.md)
- [Transfer API Specification](docs/TRANSFER_API.md)
- [Deposit API Specification (V6)](docs/DEPOSIT_API.md)
- [Transaction History API Specification (V7)](docs/TRANSACTION_HISTORY_API.md)
- [Account Statement API Specification (V8)](docs/ACCOUNT_STATEMENT_API.md)
- [Withdrawal API Specification (V9)](docs/WITHDRAWAL_API.md)
- [Account Lifecycle & Administrative Operations API Specification (V10)](docs/ACCOUNT_LIFECYCLE_API.md)
- [Financial Reconciliation Architecture](docs/RECONCILIATION.md)
- [Failure Handling & Resiliency](docs/FAILURE_HANDLING.md)
