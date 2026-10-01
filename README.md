# Financial Ledger Engine

A production-grade financial ledger engine implementing double-entry accounting, atomic multi-account transactions, deterministic concurrency control, strict platform currency enforcement (`INR`), and comprehensive automated balance-to-ledger reconciliation.

---

## 1. What the Project Is

The **Financial Ledger Engine** is an immutable, double-entry financial core paired with an auxiliary Redis caching/idempotency tier and a Next.js administrative frontend. It is designed to model core banking ledger operations—deposits, withdrawals, peer-to-peer transfers, transaction history, statement generation, and reconciliation audits—with zero tolerance for financial data corruption, phantom balances, or double-spending.

---

## 2. Why It Exists

Modern financial platforms cannot rely on naive balance updates (`UPDATE accounts SET balance = balance - X`). Such patterns lead to:
- **Race conditions & lost updates** under high concurrency.
- **Negative balances** when multiple withdrawals slip past application checks.
- **Deadlocks** when concurrent transfers lock accounts in conflicting orders.
- **Inability to audit** when account balances are mutated without a corresponding immutable historical journal.

This project demonstrates how to build a robust financial ledger where:
1. **The ledger is the truth**: Balances are snapshot projections; the immutable double-entry journal represents the historical financial truth.
2. **PostgreSQL guarantees ACID integrity**: Concurrency is handled at the database level using pessimistic row-level locking in deterministic order.
3. **Double-entry accounting is strictly enforced**: Every monetary movement consists of balanced debit and credit entries.
4. **Idempotency is guaranteed by the database**: Redis provides an auxiliary fast-path, but database uniqueness constraints remain the authoritative guard against duplicate processing.

---

## 3. Architecture

```text
                     ┌─────────────────────────────┐
                     │       Next.js Frontend      │
                     │    (Port 3001, App Router)  │
                     └──────────────┬──────────────┘
                                    │ HTTP / Cookie Session (JSESSIONID)
                                    │ CSRF Token (X-XSRF-TOKEN)
                                    ▼
                     ┌─────────────────────────────┐
                     │   Spring Boot REST API      │
                     │        (Port 8085)          │
                     │  - Security & CSRF Filters  │
                     │  - Rate Limiter (Redis Lua) │
                     │  - Deterministic Locking    │
                     │  - Double-Entry Engine      │
                     └──────┬───────────────┬──────┘
                            │               │
      Authoritative Ledger  │               │ Auxiliary Fast-Path & Rate Limiting
      & ACID Transactions   │               │ (Key-Value, 24h Idempotency TTL)
                            ▼               ▼
                 ┌────────────────────┐   ┌────────────────────┐
                 │     PostgreSQL     │   │       Redis        │
                 │    (Port 5434)     │   │    (Port 6380)     │
                 │ - Accounts         │   │ - Rate limit keys  │
                 │ - Transactions     │   │ - Idempotency cache│
                 │ - Ledger Entries   │   └────────────────────┘
                 │ - Users & Auth     │
                 │ - Immutability Trg │
                 └────────────────────┘
```

The system is deliberately structured as a **modular monolith** following a package-by-feature layout (`account`, `transaction`, `ledger`, `reconciliation`, `system`, `auth`, `ratelimit`, `common`). It intentionally avoids premature microservice decomposition to preserve single-database transactional guarantees.

---

## 4. Core Financial Guarantees

| Guarantee | Implementation Mechanism |
|---|---|
| **Authoritative Source of Truth** | PostgreSQL stores all financial state. Redis is strictly auxiliary for rate limiting and fast-path idempotency caching. |
| **Strict Platform Currency** | Single-currency `INR` platform enforced at domain level and backed by PostgreSQL CHECK constraints (`chk_accounts_currency_inr`, `chk_transactions_currency_inr`, `chk_ledger_entries_currency_inr`). |
| **Double-Entry Equality** | Every transfer generates exactly two ledger entries (one `DEBIT`, one `CREDIT`) of identical amounts referencing the parent transaction. Zero-sum invariant: $\sum \text{debits} - \sum \text{credits} = 0$. |
| **Ledger Immutability** | Database trigger `trg_prevent_ledger_mutation` blocks any `UPDATE` or `DELETE` on `ledger_entries`. Ledger history cannot be rewritten. |
| **Pessimistic Concurrency Control** | `SELECT ... FOR UPDATE` acquires row-level locks before evaluating balances or performing balance adjustments. |
| **Deadlock Prevention** | Multi-account operations lock accounts in deterministic lexicographical order by account UUID (`min(id, dest)` followed by `max(id, dest)`). |
| **Monetary Precision** | Java `BigDecimal` and PostgreSQL `NUMERIC(19,4)` throughout the entire stack. Floating-point arithmetic is strictly forbidden. |
| **Non-Negative Balances** | Database check constraint `chk_accounts_balance_non_negative` (`balance >= 0.0000`) prevents overdrafts at the database storage engine. |
| **Database-Enforced Idempotency** | PostgreSQL unique constraint `uk_transactions_idempotency_key` ensures retried requests never produce duplicate financial effects. |
| **Transparent System Treasury** | Initial platform funding (10,000,000 INR) and user opening balances (50,000 INR) originate from `SYSTEM_CLEARING` via auditable double-entry transactions. |

---

## 5. Technology Stack

- **Backend**: Java 21, Spring Boot 3.3.3, Spring Data JPA, Spring Security, Hibernate 6.5
- **Database**: PostgreSQL 16 with Flyway migration versioning (V1–V11)
- **Caching & Rate Limiting**: Redis 7 (Alpine), Jedis / Spring Data Redis, Lua scripts
- **Frontend**: Next.js 15 (App Router), React 19, TypeScript, Tailwind CSS, Lucide React
- **Testing**: JUnit 5, Testcontainers (PostgreSQL 16 & Redis 7), Mockito, Node.js Test Runner
- **Load Testing**: Apache JMeter 5.6+ with parameterized JMX test plans

---

## 6. API & Domain Capabilities

The engine exposes a clean, versioned REST API (`/api/v1/`):

- **Authentication (`/api/v1/auth`)**:
  - `POST /signup`: Register with email and password.
  - `POST /login`: Authenticate and establish a session.
  - `POST /logout`: Invalidate session and clear cookies.
  - `GET /me`: Return authenticated user profile and account summaries.
  - `GET /oauth2/authorization/google`: Initiate Google OAuth2 authorization code flow.
- **Account Management (`/api/v1/accounts`)**:
  - `POST /`: Open a new retail checking account (receives 50,000 INR bootstrap deposit).
  - `GET /`: List all checking accounts owned by the authenticated caller.
  - `GET /{accountId}`: Fetch real-time account snapshot and status.
  - `POST /{accountId}/freeze`: Administratively freeze account (blocks outgoing transfers and withdrawals).
  - `POST /{accountId}/unfreeze`: Restore account to `ACTIVE` status.
  - `POST /{accountId}/close`: Permanently close account (requires zero balance).
- **Financial Transactions**:
  - `POST /api/v1/transfers`: Atomic peer-to-peer transfer between accounts with deterministic locking and idempotency key.
  - `POST /api/v1/accounts/{accountId}/deposit`: Deposit funds from `SYSTEM_CLEARING` into an active account.
  - `POST /api/v1/accounts/{accountId}/withdraw`: Withdraw funds from an active account back to `SYSTEM_CLEARING`.
  - `POST /api/v1/transactions/{transactionId}/reversal`: Atomic compensating double-entry reversal of a completed transfer, deposit, or withdrawal with idempotency protection.
  - `GET /api/v1/transactions/{transactionId}`: Fetch transaction details including compensating reversal references.
  - `GET /api/v1/accounts/{accountId}/transactions`: Paginated, filtered, reverse-chronological transaction history with relative debit/credit direction flags and reversal indicators.
  - `GET /api/v1/accounts/{accountId}/statement`: Comprehensive financial statement over a date range including opening balance, closing balance, net cash flow, and running balances.
- **Reconciliation (`/api/v1/reconciliation`)**:
  - `POST /reconcile`: Compare current account balance snapshots against historical ledger sums; reports drifts or discrepancies.
- **Operational Audit Trail (`/api/v1/audit-events`)**:
  - `GET /`: Authorized, paginated, and filtered record of security events (signup, login, logout, password change), account lifecycle events (creation, freeze, unfreeze, closure), atomic financial completion events, and transaction reversals (`TRANSACTION_REVERSED`).

---

## 7. Security Architecture

### Session-Based Dual Authentication
The platform uses server-managed session authentication (`JSESSIONID`) rather than client-stored JWT tokens:
- **Email/Password**: Passwords hashed with BCrypt (strength 10). Credentials stored in dedicated `user_credentials` table isolated from profile data.
- **Google OAuth2 / OIDC**: Authenticated emails are linked or automatically provisioned in PostgreSQL.
- **Cookies**: `HttpOnly`, `SameSite=Lax`, and `Secure` (in HTTPS environments). Session tokens cannot be accessed via JavaScript.
- **Session Fixation Protection**: Spring Security creates a new HTTP session on authentication (`migrateSession()`).

### CSRF Protection
- Spring Security enforces CSRF protection on all state-changing endpoints (`POST`, `PUT`, `DELETE`).
- Uses `CookieCsrfTokenRepository` with `HttpOnly=false` on the `XSRF-TOKEN` cookie, requiring the client to send back the matching `X-XSRF-TOKEN` header on mutations.

### Distributed Rate Limiting
- Atomically enforced via Redis Lua scripts evaluating rolling time windows:
  - **Login**: 5 attempts per 60 seconds.
  - **Signup**: 10 attempts per 60 seconds.
  - **Financial Mutations**: 100 requests per 60 seconds (1,000 requests per 60 seconds in the `load-test` profile).
- Client IP resolution securely extracts remote addresses without blindly trusting spoofed `X-Forwarded-For` headers unless configured behind trusted proxies.

For complete authentication flows and credentials, see [docs/AUTHENTICATION_AND_CREDENTIALS.md](docs/AUTHENTICATION_AND_CREDENTIALS.md).

---

## 8. Concurrency & Deterministic Locking

To guarantee that concurrent transactions between identical or overlapping pairs of accounts never deadlock:

1. **Deterministic Ordering**: Accounts are locked in lexicographical order based on their UUID string representations:
   ```java
   UUID firstId  = sourceId.compareTo(destId) < 0 ? sourceId : destId;
   UUID secondId = sourceId.compareTo(destId) < 0 ? destId : sourceId;
   ```
2. **Pessimistic Row Locks**: `accountRepository.findWithLockById(firstId)` executes `SELECT ... FROM accounts WHERE id = ? FOR UPDATE`, followed by `findWithLockById(secondId)`.
3. **Cycle Elimination**: Because all threads acquire locks in the identical order, lock acquisition cycles are mathematically eliminated, preventing PostgreSQL deadlocks even under intense bi-directional transfer load.
4. **Isolated Transactions**: The entire sequence—lock acquisition, balance verification, balance modification, transaction recording, and ledger journal entry creation—executes inside a single `@Transactional` PostgreSQL boundary.

---

## 9. Financial Reconciliation

The reconciliation engine verifies the integrity of the ledger by executing an audit:
$$\text{Calculated Balance} = \sum_{\text{entry} \in \text{Ledger}} \text{amount} \times (\text{CREDIT} \rightarrow +1, \text{DEBIT} \rightarrow -1)$$

Reconciliation checks:
- **Per-Account Drift**: Compares `accounts.balance` against the calculated ledger sum.
- **System Clearing Conservation**: Verifies that the platform clearing account reflects all net funds distributed across user accounts.
- **Trigger Integrity**: Ensures no unauthorized manual updates have bypassed the ledger trigger.

For architectural details, see [docs/RECONCILIATION.md](docs/RECONCILIATION.md).

---

## 10. Load Testing & Performance Benchmarks

Load test plans are located in `load-testing/plans/` and executed using Apache JMeter with the backend running under the `load-test` Spring profile.

### Observed Benchmark Results

The following benchmark runs were conducted on local loopback testing multi-threaded concurrent transfers:

| Test Scenario | Concurrency & Iterations | Total Requests | Error Rate | Mean Latency | Max Latency | Throughput |
|---|---|---|---|---|---|---|
| **10 Threads x 10 Loops** | 10 concurrent threads, 10 transfer iterations per thread | 120 (100 transfers + auth/CSRF setup) | **0.00%** | **37.7 ms** | **824 ms** | **24.7 req/s** |
| **20 Threads x 25 Loops** | 20 concurrent threads, 25 transfer iterations per thread | 540 (500 transfers + auth/CSRF setup) | **0.00%** | **14.8 ms** | **303 ms** | **54.6 req/s** |

*Note: These benchmarks reflect local loopback execution under test conditions. Production throughput will vary based on hardware specifications, network topology, and database storage IOPS.*

For execution commands and detailed statistics, see [docs/RATE_LIMITING_AND_LOAD_TESTING.md](docs/RATE_LIMITING_AND_LOAD_TESTING.md).

---

## 11. Running Locally

### Prerequisites
- Java 21 JDK
- Node.js 20+ and npm
- Docker & Docker Compose

### Port Allocations

| Service | Port | Description |
|---|---|---|
| **PostgreSQL** | `5434` | Authoritative database (mapped from container port 5432) |
| **Redis** | `6380` | Auxiliary cache & rate limiter (mapped from container port 6379) |
| **Spring Boot Backend** | `8085` | Financial REST API |
| **Next.js Frontend** | `3001` | Administrative Web Interface |

### Step-by-Step Setup

1. **Clone and Configure Environment**:
   ```bash
   cp .env.example .env
   ```

2. **Start Infrastructure Services**:
   ```bash
   docker compose up -d
   ```
   Verify containers are healthy:
   ```bash
   docker ps
   ```

3. **Start the Backend**:
   ```bash
   cd backend
   ./mvnw spring-boot:run
   ```
   The backend starts on `http://localhost:8085` and runs Flyway migrations automatically.

4. **Start the Frontend**:
   ```bash
   cd frontend
   npm install
   npm run dev -- -p 3001
   ```
   Open `http://localhost:3001` in your browser.

---

## 12. Environment Variables

Reference template from `.env.example`:

```bash
# PostgreSQL
POSTGRES_DB=ledger
POSTGRES_USER=ledger
POSTGRES_PASSWORD=ledger
POSTGRES_HOST=localhost
POSTGRES_PORT=5434

# Redis
REDIS_HOST=localhost
REDIS_PORT=6380

# Spring Boot Backend
SERVER_PORT=8085
FRONTEND_URL=http://localhost:3001
CORS_ALLOWED_ORIGINS=http://localhost:3001

# Next.js Frontend
NEXT_PUBLIC_API_URL=http://localhost:8085

# Optional Google OAuth2 Credentials
GOOGLE_CLIENT_ID=your-google-client-id
GOOGLE_CLIENT_SECRET=your-google-client-secret

# Distributed Rate Limiting Defaults
RATE_LIMIT_LOGIN_MAX_ATTEMPTS=5
RATE_LIMIT_LOGIN_WINDOW_SECONDS=60
RATE_LIMIT_SIGNUP_MAX_ATTEMPTS=10
RATE_LIMIT_SIGNUP_WINDOW_SECONDS=60
RATE_LIMIT_FINANCIAL_MAX_REQUESTS=100
RATE_LIMIT_FINANCIAL_WINDOW_SECONDS=60
```

---

## 13. Testing Suite

The repository contains automated test suites across backend and frontend:

### Backend Tests
Execute unit, integration, Testcontainers, and adversarial concurrency suites:
```bash
cd backend
./mvnw test
```
*Verification status: 467 tests, 0 failures, 0 errors, 0 skipped.*

### Frontend Tests & Validation
```bash
cd frontend

# Run automated unit and component integration tests
npm test

# Run ESLint validation
npm run lint

# Run production build
npm run build
```
*Verification status: 614 backend tests passed, 229 frontend tests passed, 0 lint warnings/errors, 15/15 static and dynamic routes compiled successfully.*

---

## 14. Project Structure & Documentation

```text
financial-ledger-engine/
├── backend/                              # Spring Boot 3.3.3 application
│   ├── src/main/java/com/parth/ledger/   # Package-by-feature domain packages
│   │   ├── account/                      # Accounts, lifecycle, statement generation
│   │   ├── audit/                        # Operational audit events & immutable history
│   │   ├── auth/                         # Dual session-based auth (BCrypt + OAuth2)
│   │   ├── common/                       # Base entities, global exception handler
│   │   ├── ledger/                       # Double-entry ledger journal and repository
│   │   ├── observability/                # Correlation filter, structured logging, Micrometer metrics & health
│   │   ├── ratelimit/                    # Redis Lua token bucket rate limiting
│   │   ├── reconciliation/               # Balance vs ledger drift audit engine
│   │   ├── system/                       # System clearing & initial treasury funding
│   │   └── transaction/                  # Atomic transfers, deposits, withdrawals, reversals
│   └── src/main/resources/db/migration/  # Flyway database migrations (V1 to V11)
├── frontend/                             # Next.js 15 administrative application
│   ├── __tests__/                        # Node test runner component and integration suites
│   ├── app/                              # Next.js App Router pages and layouts
│   ├── components/                       # Shared UI components and modals
│   └── lib/                              # API client, auth context, CSRF token handling
├── database/                             # Database documentation and migration reference
├── load-testing/                         # JMeter test plans, scripts, and benchmark reports
├── docs/                                 # Technical architecture & API specifications
├── .env.example                          # Environment variable configuration template
└── README.md                             # Comprehensive project documentation
```

### Complete Documentation Index

- [Account Model & Database Hardening (V2)](docs/ACCOUNT_MODEL.md)
- [Transaction & Ledger Model Hardening (V3)](docs/TRANSACTION_MODEL.md)
- [Database Integrity, Indexing & Ledger Immutability (V4)](docs/DATABASE_INTEGRITY.md)
- [Account Management API Specification (V5)](docs/ACCOUNT_API.md)
- [Deposit API Specification (V6)](docs/DEPOSIT_API.md)
- [Transaction History API Specification (V7)](docs/TRANSACTION_HISTORY_API.md)
- [Account Statement API Specification (V8)](docs/ACCOUNT_STATEMENT_API.md)
- [Withdrawal API Specification (V9)](docs/WITHDRAWAL_API.md)
- [Account Lifecycle API Specification (V10)](docs/ACCOUNT_LIFECYCLE_API.md)
- [Audit Trail & Operational Audit Events Specification (V2.1)](docs/AUDIT_TRAIL_API.md)
- [Transaction Reversals & Compensating Transactions (V2.2)](docs/TRANSACTION_REVERSAL_API.md)
- [Observability & Operational Diagnostics (V2.4)](docs/OBSERVABILITY_AND_DIAGNOSTICS.md)
- [Transfer API Specification](docs/TRANSFER_API.md)
- [Authentication & Credentials Guide](docs/AUTHENTICATION_AND_CREDENTIALS.md)
- [Rate Limiting & Load Testing Benchmarks](docs/RATE_LIMITING_AND_LOAD_TESTING.md)
- [Financial Reconciliation Architecture](docs/RECONCILIATION.md)
- [Failure Handling & Resiliency Guide](docs/FAILURE_HANDLING.md)
