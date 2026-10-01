# Observability & Operational Diagnostics (V2.4)

## 1. Overview & Core Observability Principles

The Financial Ledger Engine (V2.4) incorporates enterprise-grade observability and operational diagnostics designed for real-time monitoring, root-cause incident diagnosis, and capacity planning without compromising financial integrity, privacy, or security.

### Core Observability Principles

1. **Observability Must NEVER Become Financial State**:
   - Metrics, diagnostic logs, and distributed traces are strictly observational.
   - They never dictate or influence account balances, transaction validity, double-entry equality, policy enforcement, or idempotency state.
   - The authoritative source of financial truth remains PostgreSQL with its ACID guarantees and immutable ledger journals.

2. **Strict Failure Isolation**:
   - Every metric collection call and operational logging action is completely isolated from transactional mutations.
   - All metric instrumentation is wrapped in defensive `try/catch (Throwable t)` blocks.
   - An internal metric registry failure, timer exception, or logging failure will **never** roll back an active financial transaction or cause data corruption.

3. **Zero-Trust Data Sanitization & Secret Redaction**:
   - Passwords, hashes, Google OAuth tokens, Stripe keys, session cookies (`JSESSIONID`), CSRF tokens (`X-XSRF-TOKEN`), authorization bearer headers, and raw idempotency keys are **never** logged or emitted as metric tags.
   - Financial account numbers, internal account UUIDs, and customer emails are dynamically masked:
     - Account Numbers: `•••• 9012` (only trailing 4 digits retained)
     - Internal UUIDs: `71be...fac8` (leading and trailing 4 hex characters retained)
     - Clearing/Treasury IDs: `[SYSTEM_CLEARING]`, `[SYSTEM_TREASURY]`
     - Email Addresses: `a***@example.com` (user identifier masked)

4. **Strict Bounded Metric Cardinality**:
   - Metric labels (tags) must always adhere to predefined finite enumerations.
   - Unbounded client inputs—such as raw account UUIDs, transaction UUIDs, idempotency keys, customer names, or un-templated URLs—are strictly forbidden in metric dimensions.

---

## 2. Correlation ID Architecture & MDC Propagation

Every request processed by the ledger engine is stamped with a correlation identifier to enable distributed end-to-end tracing across logging streams and client interactions.

### Pipeline Flow

```text
HTTP Request ──► CorrelationIdFilter ──► MDC & Request Attribute ──► Execution ──► Response Header (X-Correlation-ID)
                      │                                                                   │
                      ├── Header: X-Correlation-ID / X-Request-ID (Validated)             └── MDC.clear() (finally block)
                      └── Fallback: Secure Random UUIDv4 Generated
```

### Specifications:
- **Accepted Request Headers**: `X-Correlation-ID`, `X-Request-ID` (checked in order).
- **Validation**: Incoming headers must match the regex `^[a-zA-Z0-9_-]{1,64}$`. Any header violating this pattern (e.g., path traversals, control characters, or oversized payloads) is rejected, and a fresh UUIDv4 is generated.
- **MDC Injection**: Keyed under `correlationId` in SLF4J MDC.
- **Log Pattern**: Configured in `application.yaml` as:
  ```yaml
  logging:
    pattern:
      level: "%5p [%X{correlationId}]"
  ```
  Every log line emitted during request execution includes the correlation ID in brackets, e.g.:
  ```text
  INFO [7ed2f818-7522-45c9-9614-623d97afff24] c.p.l.t.service.TransferService : Successfully executed transfer...
  ```
- **Thread Safety & Cleanup**: A servlet filter `try ... finally { MDC.clear(); }` block guarantees that pooled servlet threads never leak correlation IDs between requests.
- **Response Propagation**: Returned to the client in the `X-Correlation-ID` response header.

---

## 3. Structured Diagnostic HTTP Logging

All incoming HTTP requests passing through the engine are logged by `RequestLoggingFilter` using a consistent, machine-parseable structured format:

```text
HTTP request completed: correlationId=<id> method=<GET|POST> path=<path> status=<statusCode> durationMs=<ms> outcome=<outcome>
```

### Outcome Classification

| HTTP Status Code | Outcome Category | Description |
|---|---|---|
| `2xx` (200, 201, 204) | `SUCCESS` | Successful execution |
| `3xx` (301, 302, etc.) | `REDIRECTION` | HTTP redirect |
| `400` | `BAD_REQUEST` | Malformed syntax or invalid parameter |
| `401` | `UNAUTHORIZED` | Unauthenticated session |
| `403` | `FORBIDDEN` | Access denied or anti-enumeration violation |
| `404` | `NOT_FOUND` | Account, transaction, or resource not found |
| `409` | `CONFLICT` | Idempotency conflict (different parameters for same key) |
| `422` | `UNPROCESSABLE` | Policy violation, insufficient funds, account frozen/closed |
| `429` | `RATE_LIMITED` | Token bucket limit exceeded |
| `5xx` | `SERVER_ERROR` | Internal server or unhandled exception |

### Bounded Cardinality & Endpoint Path Normalization
To prevent high-cardinality metric explosion across telemetry systems:
- **MVC Route Template Resolution**: Metrics derive endpoint labels from Spring MVC's resolved route pattern (`HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE`), e.g.:
  - `/api/v1/accounts/ca8e1b02-53a9-4672-9694-82a89345cff9` $\rightarrow$ `/api/v1/accounts/{accountId}`
  - `/api/v1/transactions/2166b11d-f5b7-4bfa-b19a-b8dcb8bb89c0` $\rightarrow$ `/api/v1/transactions/{transactionId}`
  - `/api/v1/accounts/97db3c80-f04b-4bfa-a352-8c88934573df/statements` $\rightarrow$ `/api/v1/accounts/{accountId}/statements`
- **Fixed `UNMATCHED` Fallback**: If an incoming request does not match any registered MVC handler or maps to the catch-all pattern (`/**`), the metric endpoint label is assigned the fixed value `UNMATCHED`. Raw URIs with arbitrary client path parameters are **never** used as metric labels.
- **Whitelisted HTTP Methods**: Only standard HTTP methods (`GET`, `POST`, `PUT`, `PATCH`, `DELETE`, `HEAD`, `OPTIONS`) are permitted as metric tag values. Any arbitrary or custom HTTP method is mapped to `OTHER`.
- **Diagnostic Log Path Sanitization**: While metrics use bounded templates or `UNMATCHED`, diagnostic application logs record a sanitized path via `sanitizePathForLogging` where UUID segments are replaced with `{id}` (e.g. `/api/v1/accounts/{id}/balance`), preventing identifier leakage while preserving operational context.

---

## 4. Micrometer Metrics Catalog

All custom business and operational metrics are grouped under the `ledger.*` namespace and registered with Micrometer.

### 4.1 Financial Operations (`ledger.operation.total` & `ledger.operation.duration`)

Tracks operational lifecycle counts and latencies for all financial operations.

| Metric Name | Type | Tags | Values / Examples |
|---|---|---|---|
| `ledger.operation.total` | Counter | `operation`<br>`status` | `operation`: `TRANSFER`, `DEPOSIT`, `WITHDRAWAL`, `REVERSAL`<br>`status`: `ATTEMPTED`, `COMPLETED`, `REJECTED`, `FAILED` |
| `ledger.operation.duration` | Timer | `operation`<br>`status` | `operation`: `TRANSFER`, `DEPOSIT`, `WITHDRAWAL`, `REVERSAL`<br>`status`: `COMPLETED`, `REJECTED`, `FAILED` |

#### Operational & Commit Semantics:
- **`ATTEMPTED`**: Recorded at the very start of service execution after preliminary validation.
- **`COMPLETED`**: Incremented **only after** the database transaction successfully commits. Registration occurs via Spring's `TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() { void afterCommit() { ... } })` (with fallback to immediate execution if synchronization is inactive).
- **`REJECTED`**: Incremented when business constraints fail (policy violations, insufficient funds, account status blocks, idempotency parameter conflict). If the transaction rolls back, `COMPLETED` is **never** emitted.
- **`FAILED`**: Incremented when unhandled system or database errors occur (e.g., deadlock, connection failure) and the transaction rolls back.

### 4.2 Idempotency Telemetry (`ledger.idempotency.total` & `ledger.idempotency.duration`)

Separates brand-new financial operations from retries and client misconfigurations, including dedicated latency tracking.

| Metric Name | Type | Tags | Values |
|---|---|---|---|
| `ledger.idempotency.total` | Counter | `operation`<br>`outcome` | `operation`: `TRANSFER`, `DEPOSIT`, `WITHDRAWAL`, `REVERSAL`<br>`outcome`: `FIRST_EXECUTION`, `REPLAY`, `CONFLICT` |
| `ledger.idempotency.duration` | Timer | `operation`<br>`outcome` | `operation`: `TRANSFER`, `DEPOSIT`, `WITHDRAWAL`, `REVERSAL`<br>`outcome`: `FIRST_EXECUTION`, `REPLAY`, `CONFLICT` |

> [!IMPORTANT]
> **Idempotent Replay Metric Isolation**:
> - An idempotent replay (`outcome=REPLAY`) increments `ledger.idempotency.total{outcome=REPLAY}` and records replay latency to `ledger.idempotency.duration{outcome=REPLAY}`.
> - An idempotent replay **NEVER** increments `ledger.operation.total{status=COMPLETED}` or records to `ledger.operation.duration{status=COMPLETED}`. This strictly isolates fast-path cached replay latency from genuine financial mutation execution latency and prevents artificial transaction volume inflation.
> - First executions record duration to both `ledger.operation.duration{status=COMPLETED}` and `ledger.idempotency.duration{outcome=FIRST_EXECUTION}` upon commit.
> - Idempotency conflicts record duration to `ledger.idempotency.duration{outcome=CONFLICT}` and increment `ledger.idempotency.total{outcome=CONFLICT}`.

### 4.3 Financial Policy Enforcement (`ledger.policy.*`)

Monitors policy engine evaluations and rejections across transaction types.

| Metric Name | Type | Tags | Description |
|---|---|---|---|
| `ledger.policy.evaluations.total` | Counter | `policy_type`<br>`tx_type` | Total policies evaluated. `policy_type`: `MAX_TRANSACTION_AMOUNT`, `DAILY_TRANSACTION_AMOUNT`, `DAILY_TRANSACTION_COUNT`, `ACCOUNT_BALANCE_LIMIT`. `tx_type`: `TRANSFER`, `DEPOSIT`, `WITHDRAWAL`. |
| `ledger.policy.rejections.total` | Counter | `policy_type`<br>`tx_type` | Total policy violations triggered. |

### 4.4 Rate Limiting Telemetry (`ledger.ratelimit.rejections.total`)

Monitors client throttle events across application tiers.

| Metric Name | Type | Tags | Categories |
|---|---|---|---|
| `ledger.ratelimit.rejections.total` | Counter | `category` | `LOGIN`, `SIGNUP`, `FINANCIAL` |

### 4.5 Financial Reconciliation Integrity (`ledger.reconciliation.*`)

Tracks ledger balance vs snapshot balance audits and discrepancy detection.

| Metric Name | Type | Tags | Scopes |
|---|---|---|---|
| `ledger.reconciliation.runs.total` | Counter | `scope` | `SINGLE_ACCOUNT`, `USER_ACCOUNTS`, `SYSTEM` |
| `ledger.reconciliation.accounts.checked.total` | Counter | `scope` | Number of accounts actually inspected during run (incremented only after existence and authorization checks pass) |
| `ledger.reconciliation.accounts.consistent.total` | Counter | `scope` | Number of accounts confirmed consistent |
| `ledger.reconciliation.discrepancies.total` | Counter | `scope` | Discrepancies detected between balance snapshot and ledger journal |
| `ledger.reconciliation.errors.total` | Counter | `scope` | Unexpected errors during reconciliation run |

> [!NOTE]
> **Accurate Work Tracking**: In single-account and direct reconciliation flows, `ledger.reconciliation.accounts.checked.total` is incremented only after the account is confirmed to exist and user authorization succeeds. Nonexistent accounts or unauthorized access attempts reject before inspecting balances and do not inflate the checked count.

### 4.6 HTTP Request Traffic & Latency (`ledger.http.*`)

| Metric Name | Type | Tags | Description |
|---|---|---|---|
| `ledger.http.requests.total` | Counter | `method`, `endpoint`, `status_group`, `outcome` | Total HTTP requests. `method` is bounded (`GET`, `POST`, `PUT`, `PATCH`, `DELETE`, `HEAD`, `OPTIONS`, `OTHER`). `endpoint` is MVC template or `UNMATCHED`. |
| `ledger.http.request.duration` | Timer | `method`, `endpoint`, `status` | HTTP request duration in milliseconds (enables p50, p95, p99 analysis). Tags adhere to bounded method and endpoint rules. |

---

## 5. Health Check Architecture: Authoritative vs Auxiliary Dependencies

Spring Boot Actuator is configured with distinct operational tiers:

### Actuator Endpoint Access Control

| Endpoint | Access Level | Description |
|---|---|---|
| `/actuator/health` | Public | Liveness and component dependency health |
| `/actuator/info` | Public | Application metadata and version |
| `/actuator/metrics` | **ADMIN-ONLY** | Sensitive telemetry; requires `ROLE_ADMIN` authentication |
| Sensitive Actuators (`env`, `heapdump`, `threaddump`) | **DISABLED** | Excluded from web exposure |

### Dependency Resilience Model

```text
               ┌─────────────────────────────────────────────────────┐
               │              /actuator/health Check                 │
               └──────────┬───────────────────────────────┬──────────┘
                          │                               │
                          ▼                               ▼
               ┌──────────────────────┐        ┌──────────────────────┐
               │   PostgreSQL (db)    │        │  Redis (auxiliary)   │
               │    AUTHORITATIVE     │        │      AUXILIARY       │
               └──────────┬───────────┘        └──────────┬───────────┘
                          │                               │
                 DOWN ───►▼◄─── UP               DOWN ───►▼◄─── UP
                    Overall App                      Overall App
                    Status: DOWN                     Status: UP (200)
                    (HTTP 503)                       status: UNAVAILABLE
                                                     (Fail-Open Protected)
```

1. **PostgreSQL is Authoritative**:
   - PostgreSQL holds the authoritative double-entry journal and balances.
   - If PostgreSQL connectivity fails, `/actuator/health` immediately reports `status: DOWN` with HTTP status `503 Service Unavailable`.

2. **Redis is Auxiliary (Fail-Open)**:
   - Redis is used exclusively for rate limiting and fast-path idempotency caching.
   - When healthy, Redis reports:
     ```json
     "redis": {
       "status": "UP",
       "details": {
         "role": "auxiliary",
         "status": "UP",
         "ping": "PONG"
       }
     }
     ```
   - When Redis connectivity fails, `AuxiliaryRedisHealthIndicator` catches the exception and reports:
     ```json
     "redis": {
       "status": "UP",
       "details": {
         "role": "auxiliary",
         "status": "UNAVAILABLE",
         "resilience": "Failing open; PostgreSQL authoritative path active"
       }
     }
     ```
   - Overall application health remains **`UP`** (`200 OK`). Financial transactions proceed directly against PostgreSQL without interruption.

---

## 6. Operator Incident Debugging Playbook

When an operational alert fires, follow these systematic diagnostics workflows:

### Scenario 1: Investigating HTTP 500 Errors
1. **Identify the Incident**: Observe an increase in `ledger.http.requests.total{status_group="5xx"}`.
2. **Find the Request Correlation ID**: Obtain the `X-Correlation-ID` header from customer support tickets, client error reports, or server logs matching `status=500`.
3. **Filter Log Streams**:
   ```bash
   grep "7ed2f818-7522-45c9-9614-623d97afff24" /var/log/ledger-engine.log
   ```
4. **Inspect Root Cause**: Review the exact logged exception, masked account ID, and stack trace associated with that correlation ID.

### Scenario 2: Investigating 429 Rate Limiting Spikes
1. **Identify Category**: Query `ledger.ratelimit.rejections.total`:
   - `category=LOGIN`: Potential brute-force credential stuffing.
   - `category=SIGNUP`: Potential automated bot registration spike.
   - `category=FINANCIAL`: Aggressive automated polling or script executing financial mutations.
2. **Review Logs**: Search for `Rate limit exceeded` in application logs to inspect normalized client identifiers and client IP addresses.

### Scenario 3: Investigating Policy Rejections (HTTP 422)
1. **Inspect Metrics**: Check `ledger.policy.rejections.total`:
   - `policy_type=DAILY_TRANSACTION_AMOUNT`: Account exceeded 24-hour spending limit.
   - `policy_type=ACCOUNT_BALANCE_LIMIT`: Destination account exceeded holding ceiling.
2. **Log Verification**: Search for `Policy evaluation rejected` with the correlation ID to view the evaluated threshold without exposing unmasked financial state.

### Scenario 4: Investigating Financial Reconciliation Discrepancies
1. **Alert Trigger**: `ledger.reconciliation.discrepancies.total` increments (> 0).
2. **Search Logs**:
   ```bash
   grep "Financial discrepancy detected" /var/log/ledger-engine.log
   ```
3. **Inspect Output**: The log reports the masked account ID, snapshot balance, ledger-derived balance, and difference:
   ```text
   WARN [...] Financial discrepancy detected for account 71be...fac8: snapshotBalance=10500.0000, ledgerBalance=10000.0000, difference=500.0000, status=DISCREPANCY
   ```
4. **Remediate**: Review the database audit log and ledger entries for that account to identify any rogue direct updates or synchronization anomalies.
