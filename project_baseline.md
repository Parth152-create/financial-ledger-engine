# Financial Ledger Engine — Project Baseline

## 1. Project Overview

**Financial Ledger Engine** is a high-throughput, auditable financial transaction system focused on transactional correctness, double-entry accounting, concurrency control, idempotency, reconciliation, security, and measurable performance.

The project is designed as a serious backend-engineering portfolio project rather than a full banking platform.

### Primary engineering goals

1. Maintain financial correctness under concurrent requests.
2. Ensure every successful transfer produces balanced double-entry ledger records.
3. Make client retries safe through idempotency.
4. Keep PostgreSQL as the authoritative financial source of truth.
5. Use Redis only as an auxiliary fast-path/idempotency mechanism.
6. Detect inconsistencies through reconciliation.
7. Measure performance instead of making unsupported performance claims.
8. Demonstrate production-oriented security, testing, observability, and deployment practices.

---

## 2. Locked Architecture

```text
                    ┌───────────────────────┐
                    │   Next.js Frontend    │
                    │ TypeScript / shadcn   │
                    └───────────┬───────────┘
                                │ REST / JSON
                                ▼
                    ┌───────────────────────┐
                    │    Spring Boot API    │
                    │ Java 25 / Security    │
                    └───────┬─────────┬─────┘
                            │         │
                     Fast Path         │ Source of Truth
                            │         │
                            ▼         ▼
                         ┌───────┐ ┌──────────────┐
                         │ Redis │ │ PostgreSQL   │
                         │       │ │              │
                         │Idemp. │ │ Accounts     │
                         │Assist │ │ Transactions │
                         └───────┘ │ Ledger       │
                                   └──────┬───────┘
                                          │
                                          ▼
                                   Reconciliation
```

JMeter is an external load-testing client and is not part of the production request path.

---

## 3. Core Technology Stack

### Backend

- Java 25
- Spring Boot 4.1.1
- Spring Web
- Spring Data JPA
- Spring Security
- Spring Security OAuth2 Client
- Spring Validation
- Spring Data Redis
- Spring Boot Actuator
- Flyway
- PostgreSQL Driver
- Maven

### Database / Infrastructure

- PostgreSQL
- Redis
- Docker
- Docker Compose
- GitHub Actions

### Frontend

- Next.js
- TypeScript
- Tailwind CSS
- shadcn/ui
- TanStack Query
- Recharts
- React Hook Form
- Zod

### Testing

- JUnit 5
- Mockito
- Testcontainers
- JMeter

---

## 4. Repository Structure

```text
financial-ledger-engine/
│
├── backend/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/
│   │   │   └── resources/
│   │   └── test/
│   ├── pom.xml
│   ├── mvnw
│   └── mvnw.cmd
│
├── frontend/
│
├── load-testing/
│   ├── datasets/
│   ├── results/
│   └── *.jmx
│
├── docs/
│   ├── ARCHITECTURE.md
│   ├── TRANSACTION_FLOW.md
│   ├── CONCURRENCY.md
│   ├── IDEMPOTENCY.md
│   ├── FAILURE_HANDLING.md
│   ├── RECONCILIATION.md
│   ├── LOAD_TESTING.md
│   └── DESIGN_DECISIONS.md
│
├── database/
│
├── .env.example
├── .gitignore
└── README.md
```

Backend organization should eventually follow **package-by-feature**, for example:

```text
com.parth.ledger
├── account/
├── transaction/
├── ledger/
├── idempotency/
├── reconciliation/
├── security/
├── common/
└── config/
```

---

# 5. Financial Domain

## Users

Users authenticate through Google OAuth and can own one or more accounts.

## Accounts

An account contains a current balance snapshot.

Conceptually:

```text
Account
- id
- user_id
- currency
- balance
- version
- created_at
- updated_at
```

Money must use:

```text
Java:       BigDecimal
PostgreSQL: NUMERIC
```

Never use `float` or `double` for financial amounts.

## Transactions

A transaction represents the business operation requested by the client.

Conceptually:

```text
Transaction
- id
- idempotency_key
- status
- amount
- currency
- created_at
- completed_at
```

## Ledger Entries

Every successful transfer produces balanced entries.

For a transfer:

```text
Source account       DEBIT
Destination account  CREDIT
```

The sum of debits must equal the sum of credits.

Ledger entries are immutable.

Historical entries must not be edited to correct mistakes. Corrections should be represented through compensating transactions.

---

# 6. Transaction Processing Flow

The intended transfer flow is:

```text
Request
  ↓
Authentication
  ↓
Authorization
  ↓
Input Validation
  ↓
Idempotency Handling
  ↓
BEGIN PostgreSQL TRANSACTION
  ↓
Lock relevant accounts
  ↓
Use deterministic lock ordering
  ↓
Check balance
  ↓
Debit source
  ↓
Credit destination
  ↓
Create transaction record
  ↓
Create debit ledger entry
  ↓
Create credit ledger entry
  ↓
Verify debit == credit
  ↓
COMMIT
  ↓
Return transaction result
```

The entire financial mutation must occur inside one PostgreSQL transaction.

`@Transactional` defines the transaction boundary, but it does **not by itself solve concurrency problems**.

---

# 7. Concurrency Strategy

The primary concurrency mechanism is PostgreSQL row-level locking.

Relevant accounts should be locked using a deterministic order, such as ascending account ID.

Example:

```text
Transfer A → B

Lock A
Lock B
```

and:

```text
Transfer B → A

Lock A
Lock B
```

Both operations acquire locks in the same order, reducing deadlock risk.

### Why database locking?

Application-level locks such as:

```java
synchronized
ReentrantLock
```

only coordinate threads inside one JVM instance.

PostgreSQL locking coordinates access to shared financial state across multiple application instances.

### Important scenario

If 1,000 concurrent transfers target the same account, the account becomes a hot row.

The system must therefore be tested for:

- lock contention
- transaction wait time
- throughput degradation
- tail latency
- deadlocks
- database saturation

---

# 8. Idempotency

Clients can retry requests because of:

- network failures
- timeout responses
- lost responses
- connection interruptions
- client retry logic

A transfer request therefore includes an idempotency key.

The database must enforce uniqueness on the relevant idempotency key.

Redis can provide a fast-path duplicate check, but Redis is **not** the financial source of truth.

### Important guarantee

Do not describe the system as providing absolute "exactly once execution."

The correct goal is:

> A retried request with the same idempotency key must not create a second financial effect.

A duplicate request arriving concurrently must also be handled safely through authoritative database constraints and transaction logic.

---

# 9. Failure Handling

### Crash before commit

PostgreSQL rolls back uncommitted changes.

Example:

```text
Debit
Credit
Crash
↓
ROLLBACK
```

No partial committed transfer remains.

### Commit succeeds but response is lost

The client may retry the same request.

The idempotency mechanism finds the existing transaction and returns the existing result instead of creating another financial effect.

### Redis goes down

Financial correctness must remain possible because PostgreSQL is authoritative.

Redis should be treated as an optimization/fast-path dependency, not as the database of record.

---

# 10. Reconciliation

Reconciliation checks whether account balance snapshots agree with the ledger history.

Conceptually:

```text
Account Snapshot
       │
       │ compare
       ▼
Ledger-derived Balance
       │
       ▼
Match / Mismatch
```

A reconciliation process should report:

- accounts checked
- ledger entries considered
- expected balance
- stored balance
- mismatches
- reconciliation timestamp
- status

Reconciliation is a correctness mechanism, not merely an analytics feature.

---

# 11. Security

Security is enforced by the backend.

The frontend must never be treated as an authorization boundary.

Important protections include:

- Google OAuth authentication
- authenticated user context
- account ownership checks
- authorization before account operations
- input validation
- rate limiting
- secure configuration
- audit logging where appropriate
- protection against IDOR-style access
- secrets kept outside source control

For example, a client sending:

```text
accountId=123
```

does not automatically mean the authenticated user owns account `123`.

The backend must verify ownership.

---

# 12. Database Design

Initial core tables:

```text
users
accounts
transactions
ledger_entries
```

Potential additional table:

```text
audit_events
```

Important constraints/indexes should include:

- unique idempotency key
- ledger entries indexed by account
- ledger entries indexed by transaction
- transaction timestamps indexed where useful
- foreign keys
- appropriate currency constraints
- non-negative/positive amount validation where applicable

Schema evolution should be managed using Flyway.

Do not rely on Hibernate auto-DDL as the production schema-management mechanism.

---

# 13. Frontend Scope

The frontend is intentionally smaller than the backend.

Primary navigation:

```text
Dashboard
Accounts
Transfers
Ledger
Analytics
Reconciliation
Settings
```

### Dashboard

The dashboard should contain:

- total balance
- transaction count
- transaction volume
- success rate
- recent transactions
- system/transaction health
- analytics

### Analytics chart card

Use **one reusable analytics chart card** with compact icon toggles in the top-right.

The graph changes without changing the card layout.

Possible views:

```text
Transaction Volume
Transaction Value
Balance Trend
Success Rate
```

The selected icon gets a subtle active state.

### Donut/Pie chart

Keep the donut/pie visualization as a separate fixed card below the switchable analytics chart if the final composition remains visually clean.

The UI should remain minimal and uncluttered.

---

# 14. Frontend Design Principles

The visual direction is:

- minimal fintech interface
- black/white foundation
- light/dark theme
- restrained accent usage
- clean typography
- compact controls
- useful graphs
- minimal animation
- no unnecessary visual effects

Avoid turning the frontend into a second backend.

Spring Boot remains the primary API.

Avoid unnecessary Next.js API routes for ordinary financial operations.

---

# 15. Performance Testing

JMeter will be used to measure the API under load.

Important metrics:

```text
Throughput
p50 latency
p95 latency
p99 latency
Error rate
Concurrent users
Database CPU
Database connections
Connection pool utilization
Lock contention
Transaction duration
```

Every benchmark report should record:

- hardware/environment
- application version/commit
- database configuration
- concurrency
- test duration
- workload
- dataset characteristics
- measured results

Never invent benchmark numbers.

A resume claim such as:

> Achieved X requests/sec at Y ms p95

should only be made after actually measuring it.

---

# 16. Testing Strategy

Testing should cover several layers.

### Unit tests

Test:

- validation
- business rules
- transaction calculations
- authorization rules
- reconciliation logic

### Integration tests

Use Testcontainers where appropriate for:

- PostgreSQL
- Redis
- database migrations
- repository behavior
- transaction behavior

### Concurrency tests

Test:

- simultaneous transfers
- same-account contention
- opposing transfers
- duplicate idempotency keys
- insufficient balance races
- deadlock scenarios

### Failure tests

Test:

- database rollback
- response loss/retry
- Redis unavailable
- duplicate requests
- invalid transactions

### Reconciliation tests

Intentionally introduce inconsistent state in a controlled test environment and verify that reconciliation detects it.

---

# 17. Observability

Spring Boot Actuator will provide health and operational endpoints.

Eventually add:

- structured logs
- request correlation IDs
- transaction IDs in logs
- relevant metrics
- database timing
- lock/contention measurements
- error tracking

Do not expose sensitive financial information in logs.

---

# 18. Infrastructure

Local development should use Docker Compose for infrastructure such as:

```text
PostgreSQL
Redis
```

The Spring Boot application can initially run directly from IntelliJ/Maven.

The frontend can initially run through the Next.js development server.

Containerization of application services can be introduced once the local development flow is stable.

---

# 19. CI/CD

GitHub Actions should eventually validate:

```text
Checkout
↓
Java setup
↓
Maven tests
↓
Build
↓
Frontend install
↓
Frontend lint/typecheck/test
↓
Build frontend
```

Additional quality checks can be introduced later.

CI should not claim a deployment is successful unless deployment actually completes.

---

# 20. Development Milestones

## Milestone 1 — Foundation

- Repository
- Spring Boot project
- Java 25
- PostgreSQL connection
- Redis connection
- Flyway
- Docker Compose
- configuration
- health checks
- package structure

## Milestone 2 — Financial Engine

- users
- accounts
- transactions
- ledger entries
- transfer API
- double-entry validation
- atomic transaction processing

## Milestone 3 — Concurrency

- row locking
- deterministic lock ordering
- concurrent transfer tests
- deadlock analysis
- hot-row testing

## Milestone 4 — Reliability

- idempotency
- Redis fast-path
- database uniqueness
- retry behavior
- failure injection
- reconciliation

## Milestone 5 — Security

- Google OAuth
- authorization
- account ownership
- validation
- rate limiting
- audit events

## Milestone 6 — Frontend

- Next.js foundation
- authentication flow
- dashboard
- accounts
- transfers
- ledger explorer
- analytics
- reconciliation

## Milestone 7 — Performance

- JMeter scenarios
- baseline benchmarks
- concurrency tests
- bottleneck analysis
- database tuning
- connection pool tuning

## Milestone 8 — Production Hardening

- observability
- CI/CD
- Docker images
- documentation
- security review
- failure review
- final load tests
- deployment

---

# 21. Engineering Rules

These rules are locked unless deliberately changed later.

### Financial correctness

- PostgreSQL is authoritative.
- Redis is not the source of truth.
- Every successful transfer must balance.
- Ledger history is immutable.
- Money uses BigDecimal/NUMERIC.
- Financial mutations are transactional.

### Concurrency

- Do not rely on Java locks for distributed correctness.
- Use database locking for shared financial state.
- Acquire multiple account locks deterministically.
- Test contention rather than assuming it is safe.

### Reliability

- Use database-enforced idempotency.
- Do not claim absolute exactly-once execution.
- Design retries explicitly.
- Reconcile snapshots against ledger-derived values.

### Security

- Never trust frontend authorization.
- Verify ownership server-side.
- Never commit secrets.
- Validate all external input.

### Performance

- Measure before optimizing.
- Report p95/p99 rather than only averages.
- Record the test environment.
- Do not invent benchmarks.

### Architecture

- Prefer a modular monolith initially.
- Add complexity only when justified by a measurable requirement.
- Do not add Kafka, Kubernetes, microservices, GraphQL, or other infrastructure without a demonstrated need.

---

# 22. What Is Explicitly Out of Scope

The initial project is **not** intended to implement:

- full banking infrastructure
- UPI
- credit cards
- loans
- payment gateway processing
- real-world settlement networks
- microservices
- Kafka event architecture
- Kubernetes
- distributed transactions across multiple databases
- AI features
- unnecessary WebSocket infrastructure
- complex trading functionality

These can be considered only as future extensions if a concrete engineering requirement justifies them.

---

# 23. Development Workflow

Use the following workflow when using CLI coding agents:

```text
YOU define requirement
        ↓
AGENT implements
        ↓
YOU review the code
        ↓
Run tests
        ↓
Try to break it
        ↓
Fix issues
        ↓
Document the decision
        ↓
Commit
```

Agents may handle:

- boilerplate
- DTOs
- repositories
- controllers
- migrations
- tests
- UI components
- documentation

But architectural decisions and critical financial/concurrency behavior must be understood and reviewed manually.

---

# 24. Git Strategy

Use meaningful commits.

Examples:

```text
chore: initialize financial ledger engine
feat: add account domain
feat: implement atomic transfers
feat: add double-entry ledger
feat: add pessimistic account locking
feat: add idempotent transfer processing
test: add concurrent transfer tests
feat: add reconciliation service
feat: add google oauth authentication
feat: add transaction analytics
test: add jmeter load scenarios
docs: document concurrency strategy
```

Suggested milestone tags:

```text
v0.1-foundation
v0.2-ledger
v0.3-concurrency
v0.4-reliability
v0.5-dashboard
v1.0-production
```

---

# 25. Current Baseline

At project initialization:

```text
Repository:
financial-ledger-engine

Backend:
Spring Boot 4.1.1
Java 25
Maven

Current generated package:
com.parth.ledger_engine

Planned package:
com.parth.ledger

Current status:
Initial repository committed

Next major task:
Backend foundation configuration
```

The package refactor and Maven metadata cleanup can be performed in IntelliJ before the next feature commit.

---

# 26. Immediate Next Steps

Do not jump directly into implementing transfers.

The next implementation sequence is:

```text
1. Open backend in IntelliJ
2. Verify Java 25
3. Clean/refactor package structure
4. Configure application.yaml
5. Create Docker Compose
6. Start PostgreSQL
7. Start Redis
8. Configure datasource
9. Configure Flyway
10. Verify Spring Boot startup
11. Add health checks
12. Create initial database migration
13. Verify integration tests
14. Commit foundation
```

Only after this foundation is stable should the account and transaction domain be implemented.

---

# 27. Useful Commands

## Enter repository

```bash
cd ~/IdeaProjects/financial-ledger-engine
```

## Check status

```bash
git status
```

## Pull latest changes

```bash
git pull --rebase origin main
```

## Create a feature branch

```bash
git checkout -b feature/<feature-name>
```

## Stage changes

```bash
git add .
```

## Commit

```bash
git commit -m "<type>: <description>"
```

## Push branch

```bash
git push -u origin feature/<feature-name>
```

## Return to main

```bash
git checkout main
```

## Build backend

```bash
cd backend
./mvnw clean package
```

## Run backend tests

```bash
./mvnw test
```

## Run Spring Boot

```bash
./mvnw spring-boot:run
```

## Return to repository root

```bash
cd ..
```

## Inspect repository tree

```bash
find . -maxdepth 3 -type f | sort
```

## Check Git history

```bash
git log --oneline --decorate --graph --all
```

---

# 28. Baseline Definition

This document defines the initial technical direction and scope of the Financial Ledger Engine.

Any future change should be treated as an explicit architecture decision rather than something added simply because a technology is popular.

The central engineering objective remains:

> Build a financially correct, concurrency-safe, idempotent, auditable ledger engine and demonstrate its behavior through tests, reconciliation, and measurable load testing.
