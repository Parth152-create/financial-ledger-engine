# Financial Ledger Engine — Failure Handling & Reliability

## 1. Architectural Philosophy: Source of Truth vs. Optimization

In the **Financial Ledger Engine**, the architectural boundary between durability and performance is strictly separated:
- **PostgreSQL is the Authoritative Financial Source of Truth**: All financial balances, state transitions, double-entry ledger entries, and idempotency guarantees originate from and are finalized in PostgreSQL.
- **Redis is an Auxiliary Performance Optimization**: Redis serves solely as an idempotency fast-path cache to bypass database row locks for duplicate requests. Redis is **never** an authoritative financial store, does not hold durable state, and is not part of the two-phase transaction lifecycle.

```mermaid
flowchart TD
    Req[Incoming Transfer Request] --> FastPath{Redis Fast-Path}
    FastPath -->|Hit & Match| CachedResp[Return Cached Response]
    FastPath -->|Conflict| ConflictErr[409 Conflict]
    FastPath -->|Miss OR Outage| DBTx[Begin Authoritative PostgreSQL Transaction]
    
    subgraph PostgreSQL Transaction Boundary [@Transactional]
        DBTx --> Lock[Pessimistic Lock Accounts by Ascending UUID]
        Lock --> CheckPreCommitIdemp{Committed Tx Exists?}
        CheckPreCommitIdemp -->|Yes| ReturnTx[Return Existing Tx]
        CheckPreCommitIdemp -->|No| Mutate[Mutate Balances & Persist Ledger Entries]
        Mutate --> InvariantCheck{Double-Entry Balanced?}
        InvariantCheck -->|No| Rollback[Rollback Transaction]
        InvariantCheck -->|Yes| Commit[COMMIT to PostgreSQL]
    end
    
    Commit --> PostCommitSync[TransactionSynchronization.afterCommit]
    PostCommitSync --> TryCache[Cache Response in Redis]
    TryCache -->|Redis Fails| LogWarn[Log Warning - Do NOT Rollback DB]
    TryCache -->|Redis Succeeds| ReturnSuccess[Return Response DTO]
    LogWarn --> ReturnSuccess
```

---

## 2. PostgreSQL Transaction Boundary & Rollback Semantics

All financial mutations inside `TransferService.executeTransfer` execute under Spring's `@Transactional` boundary with PostgreSQL default isolation (`READ_COMMITTED`):

1. **Atomic Mutation Scope**:
   - Source account balance decrement (`accounts.balance`).
   - Destination account balance increment (`accounts.balance`).
   - Transaction status record creation (`transactions`).
   - Double-entry ledger entries (`ledger_entries`: one `DEBIT`, one `CREDIT`).
2. **All-or-Nothing Rollback**:
   - Any runtime exception—such as insufficient funds, currency mismatch, account ownership failure, database connection drop, disk error, or double-entry imbalance—triggers a PostgreSQL rollback (`ROLLBACK`).
   - **No Partial State**: If an error occurs after mutating account balances but before or during ledger entry insertion, the entire database transaction aborts. Account balances revert to their pre-transaction values, and no orphan transaction or ledger records survive.

---

## 3. Redis Failure Modes & Resilience

### A. Redis Unavailable Before Transfer (Fast-Path Read Outage)
When Redis is down, unreachable, or times out during the initial idempotency check:
- `idempotencyCacheService.get(key)` throws `RedisConnectionFailureException` or `QueryTimeoutException`.
- The exception is caught within `TransferService`, logged at `WARN` level, and the system **fails open** to PostgreSQL.
- The transaction proceeds directly to PostgreSQL, acquiring row locks and checking `transactionRepository.findByIdempotencyKey(key)`.
- Financial correctness is maintained without client-observable disruption.

### B. Redis Failure After Database Commit (Post-Commit Write Outage)
- Redis writes are registered via `TransactionSynchronizationManager.registerSynchronization` inside `afterCommit()`.
- The Redis `set()` operation executes **only after** PostgreSQL has successfully committed the transaction to durable storage.
- If Redis fails during this write (e.g., timeout, network partition, out-of-memory):
  - The failure is caught and logged at `WARN` level.
  - **Redis cannot roll back or invalidate committed financial state.** The PostgreSQL commit is final.
  - The client receives the successful `TransferResponseDto`.
- **Subsequent Retry Behavior**: If the client retries with the same idempotency key while Redis is unpopulated:
  - The request encounters a Redis cache miss and falls through to PostgreSQL.
  - PostgreSQL locates the committed transaction record by `idempotency_key`, validates ownership and request parameters, and returns the committed result.
  - Redis is lazily repopulated on the retry path if Redis connectivity has recovered.

---

## 4. Idempotency Under Failure & Retries

### A. Failed / Rolled-Back Transactions
If a transfer attempt encounters an error (e.g., database constraint failure, simulated write error, insufficient balance):
- The PostgreSQL transaction rolls back; no record is saved in `transactions`.
- Because `afterCommit()` is never triggered for a rolled-back transaction, **no record is placed in Redis**.
- **Retry Semantics**: If the client retries using the exact same idempotency key after resolving the transient failure, the request is permitted to execute fresh. It is not blocked by stale failure state.
- Once committed, exactly one financial effect occurs.

### B. Concurrent Duplicate Retries
When multiple concurrent requests arrive with the same idempotency key and identical transfer parameters:
- **With Redis Available**: The first request establishes the transaction; subsequent requests are served from the Redis fast-path without contending for PostgreSQL row locks.
- **With Redis Unavailable**: All concurrent threads fall through to PostgreSQL.
  - The threads contend for the deterministic row lock on the accounts.
  - The winning thread acquires the lock, observes that no transaction exists, executes the transfer, saves the transaction, and commits.
  - The remaining threads, upon acquiring the lock, execute the **post-lock idempotency check** (`transactionRepository.findByIdempotencyKey(key)`), detect that the transaction was committed by the preceding thread, and return the committed transaction without re-applying balance mutations or ledger entries.
  - Result: Single financial effect across all concurrent attempts.

### C. Idempotency Conflict Detection
If a retry arrives with an existing idempotency key but conflicting parameters (e.g., altered amount, different currency, or mismatched destination account):
- The system rejects the request with `IdempotencyConflictException` (`HTTP 409 Conflict`).
- Account balances and ledger entries remain completely untouched.

---

## 5. Concurrency Guarantees & Deadlock Prevention

1. **Deterministic Lock Ordering**:
   - Circular-wait deadlocks between opposing concurrent transfers (e.g., $A \to B$ and $B \to A$) are prevented by sorting account UUIDs and acquiring pessimistic write locks (`SELECT ... FOR UPDATE`) in ascending lexicographical order:
     $$\text{firstLock} = \min(\text{sourceId}, \text{destId}), \quad \text{secondLock} = \max(\text{sourceId}, \text{destId})$$
2. **Double-Entry Balance Verification**:
   - Before committing, the application asserts:
     $$\sum \text{Debits} = \sum \text{Credits}$$
   - Any divergence raises `UnbalancedLedgerException` and triggers rollback.

---

## 6. Financial Integrity Verification: Post-Failure Reconciliation

Following any simulated failure, retry, or concurrency burst, the **Reconciliation Subsystem** verifies account integrity:
- **Snapshot Match**: The stored snapshot (`accounts.balance`) must strictly equal the ledger-derived balance ($\sum \text{Credits} - \sum \text{Debits}$).
- **Status Assertion**: Reconciled accounts must return `CONSISTENT` with `difference == 0.0000`.
- **Evidentiary Invariant**: A failed transfer leaves zero partial ledger entries; a successful transfer leaves exactly balanced debit and credit entries.

---

## 7. Limitations & Non-Guarantees

To maintain engineering precision, the following limitations and non-guarantees are explicitly noted:

1. **Network-Level Ambiguity**: If a network partition severs communication between the client and API after PostgreSQL has committed but before the HTTP response reaches the client, the client experiences a connection timeout. The transaction has committed. The client **must** retry using the same `Idempotency-Key` to safely learn the outcome without creating a duplicate transfer.
2. **Distributed Deadlock Scope**: Deterministic lock ordering eliminates classic circular-wait patterns within this single application instance. In complex distributed topologies involving multiple databases or external services, global deadlock freedom cannot be guaranteed solely by single-instance ordering.
3. **No "Exactly-Once" Network Transmission**: "Exactly-once" delivery over an unreliable network is theoretically impossible. The engine guarantees **idempotent financial processing** (a single financial effect under arbitrary client retries), not exactly-once network transmission.
4. **Redis Cache Freshness**: Redis entries are subject to TTL expiration. A cache miss after expiration does not compromise correctness because PostgreSQL remains the durable, authoritative source of truth.
