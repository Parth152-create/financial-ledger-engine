# Rate Limiting & Load-Test Configuration

## Overview
The Financial Ledger Engine implements Redis-backed distributed rate limiting to protect authentication, account registration, and state-changing financial mutations against brute-force attacks, denial-of-service, and resource exhaustion.

Rate limiting is orchestrated via:
- `FinancialRateLimitingInterceptor`: Spring MVC interceptor intercepting mutating HTTP methods (`POST`, `PUT`, `PATCH`, `DELETE`).
- `RedisRateLimiterService`: Executes atomic Lua `INCR` scripts with rolling TTL expiration windows in Redis.
- `RateLimitProperties`: Strongly-typed configuration properties binding `ledger.rate-limit.*`.

---

## Rate Limit Thresholds

| Operation | Default Production Limit | Load-Test Limit | Config Key |
| :--- | :--- | :--- | :--- |
| **Login** (`/api/v1/auth/login`) | 5 attempts / 60s | 5 attempts / 60s | `ledger.rate-limit.login` |
| **Signup** (`/api/v1/auth/signup`) | 10 attempts / 60s | 10 attempts / 60s | `ledger.rate-limit.signup` |
| **Financial Mutations** (`/api/v1/transfers`, `/deposits`, etc.) | **100 requests / 60s** | **1,000 requests / 60s** (configurable) | `ledger.rate-limit.financial` / `load-test.financial` |

---

## Load-Test Rate Limit Configuration

For load and stress testing (such as JMeter multi-thread concurrent transfer tests), a dedicated load-test configuration allows a controlled, elevated threshold without globally disabling rate limiting or weakening production security.

### Key Invariants:
1. **Disabled by default**: The load-test override only activates when explicitly enabled. Normal production and default configurations strictly enforce the 100 requests / 60 seconds limit.
2. **Full Security Preserved**: Authentication (`JSESSIONID`), CSRF validation (`XSRF-TOKEN` cookie matching `X-XSRF-TOKEN` header), idempotency (`Idempotency-Key`), double-entry ledger validation, and pessimistic account locking remain 100% active.
3. **Threshold Enforcement**: The rate limiter is **not** disabled; once requests exceed the load-test threshold (e.g. >1000 in 60s), the service returns HTTP `429 Too Many Requests` with a `Retry-After` header.

---

## How to Enable Load-Test Configuration Locally

Choose one of the following methods to enable the load-test configuration:

### Method 1: Using the `load-test` Spring Profile (Recommended)

#### With Maven wrapper:
```bash
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=load-test
```

#### With executable JAR:
```bash
java -Dspring.profiles.active=load-test -jar target/ledger-engine-0.0.1-SNAPSHOT.jar
```

#### Via Environment Variable:
```bash
export SPRING_PROFILES_ACTIVE=load-test
./mvnw spring-boot:run
```

---

### Method 2: Via Environment Variables (Without Changing Active Profile)

Enable the load-test rate limit override directly via environment variables:

```bash
# Enable load-test rate limiting override
export RATE_LIMIT_LOAD_TEST_ENABLED=true

# (Optional) Customize the threshold (defaults to 1000 attempts / 60 seconds)
export RATE_LIMIT_LOAD_TEST_FINANCIAL_MAX_ATTEMPTS=1000
export RATE_LIMIT_LOAD_TEST_FINANCIAL_WINDOW_SECONDS=60

# Start the application
./mvnw spring-boot:run
```

---

### Method 3: Via JVM System Properties

```bash
java -Dledger.rate-limit.load-test.enabled=true \
     -Dledger.rate-limit.load-test.financial.max-attempts=1000 \
     -jar target/ledger-engine-0.0.1-SNAPSHOT.jar
```

---

## Verifying Active Configuration

When the application boots with load-test mode active, `RedisRateLimiterService` logs the active status at startup:

```text
INFO --- [ledger-engine] c.p.l.s.r.RedisRateLimiterService : Rate limiting enabled. Load-test override ACTIVE: financial limit = 1000/60s
```

Under normal default configuration, the log confirms standard limits:

```text
INFO --- [ledger-engine] c.p.l.s.r.RedisRateLimiterService : Rate limiting enabled: financial limit = 100/60s, login limit = 5/60s, signup limit = 10/60s
```
