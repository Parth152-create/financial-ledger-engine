package com.parth.ledger.recurring;

import com.parth.ledger.BaseIntegrationTest;
import com.parth.ledger.account.Account;
import com.parth.ledger.account.AccountRepository;
import com.parth.ledger.account.AccountStatus;
import com.parth.ledger.audit.AuditEventRepository;
import com.parth.ledger.audit.AuditEventType;
import com.parth.ledger.ledger.LedgerEntry;
import com.parth.ledger.ledger.LedgerEntryRepository;
import com.parth.ledger.ledger.LedgerEntryType;
import com.parth.ledger.outbox.OutboxEvent;
import com.parth.ledger.outbox.OutboxEventRepository;
import com.parth.ledger.outbox.OutboxEventType;
import com.parth.ledger.system.SystemFundingService;
import com.parth.ledger.transaction.Transaction;
import com.parth.ledger.transaction.TransactionRepository;
import com.parth.ledger.transaction.TransactionType;
import com.parth.ledger.user.User;
import com.parth.ledger.user.UserRepository;
import com.parth.ledger.transaction.dto.TransferRequestDto;
import com.parth.ledger.transaction.service.TransferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = "ledger.recurring-transfers.enabled=false")
@DisplayName("V2.6 Recurring Transfer Execution & Idempotency Integration Tests")
class RecurringTransferExecutionIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Autowired
    private RecurringTransferRepository recurringTransferRepository;

    @Autowired
    private RecurringTransferExecutionRepository executionRepository;

    @Autowired
    private RecurringTransferProcessor processor;

    @Autowired
    private SystemFundingService systemFundingService;

    @Autowired
    private TransferService transferService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private User alice;
    private User bob;
    private Account aliceAccount;
    private Account bobAccount;

    @BeforeEach
    void setUp() {
        if (jdbcTemplate != null) {
            jdbcTemplate.execute("TRUNCATE TABLE recurring_transfer_executions CASCADE");
            jdbcTemplate.execute("TRUNCATE TABLE recurring_transfers CASCADE");
            jdbcTemplate.execute("TRUNCATE TABLE outbox_events CASCADE");
            jdbcTemplate.execute("TRUNCATE TABLE ledger_entries CASCADE");
            jdbcTemplate.execute("TRUNCATE TABLE transactions CASCADE");
            jdbcTemplate.execute("TRUNCATE TABLE audit_events CASCADE");
            jdbcTemplate.execute("TRUNCATE TABLE accounts CASCADE");
            jdbcTemplate.execute("TRUNCATE TABLE users CASCADE");
        }
        systemFundingService.bootstrapSystemFunding(SystemFundingService.DEFAULT_BOOTSTRAP_AMOUNT);

        String runId = UUID.randomUUID().toString().substring(0, 8);
        alice = userRepository.save(new User("alice.exec." + runId + "@ledger.com", "Alice"));
        bob = userRepository.save(new User("bob.exec." + runId + "@ledger.com", "Bob"));

        aliceAccount = accountRepository.save(new Account(alice, "INR", new BigDecimal("5000.0000")));
        bobAccount = accountRepository.save(new Account(bob, "INR", new BigDecimal("1000.0000")));
    }

    @Test
    @DisplayName("1. Successful execution reuses TransferService, alters balances, records ledger/audit/outbox and history")
    void testSuccessfulExecutionReusesTransferService() {
        // Schedule due today
        RecurringTransfer schedule = new RecurringTransfer(
                alice,
                aliceAccount,
                bobAccount,
                new BigDecimal("500.0000"),
                "INR",
                RecurringFrequency.MONTHLY,
                LocalDate.now(ZoneOffset.UTC),
                null,
                Instant.now().minusSeconds(10) // already due
        );
        RecurringTransfer savedSchedule = recurringTransferRepository.save(schedule);

        long outboxCountBefore = outboxEventRepository.count();
        long auditCountBefore = auditEventRepository.count();

        // Process single schedule
        boolean processed = processor.processSingleSchedule(savedSchedule.getId());
        assertThat(processed).isTrue();

        // 1. Verify balances updated accurately
        Account reloadedAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        Account reloadedBob = accountRepository.findById(bobAccount.getId()).orElseThrow();
        assertThat(reloadedAlice.getBalance()).isEqualByComparingTo(new BigDecimal("4500.0000"));
        assertThat(reloadedBob.getBalance()).isEqualByComparingTo(new BigDecimal("1500.0000"));

        // 2. Verify financial transaction created with type TRANSFER
        List<Transaction> transfers = transactionRepository.findAll().stream()
                .filter(t -> t.getTransactionType() == TransactionType.TRANSFER)
                .toList();
        assertThat(transfers).hasSize(1);
        Transaction transferTx = transfers.getFirst();
        assertThat(transferTx.getAmount()).isEqualByComparingTo(new BigDecimal("500.0000"));
        assertThat(transferTx.getSourceAccount().getId()).isEqualTo(aliceAccount.getId());
        assertThat(transferTx.getDestinationAccount().getId()).isEqualTo(bobAccount.getId());

        // 3. Verify double-entry ledger entries created
        List<LedgerEntry> entries = ledgerEntryRepository.findByTransactionId(transferTx.getId());
        assertThat(entries).hasSize(2);
        assertThat(entries).anyMatch(e -> e.getAccount().getId().equals(aliceAccount.getId()) && e.getEntryType() == LedgerEntryType.DEBIT);
        assertThat(entries).anyMatch(e -> e.getAccount().getId().equals(bobAccount.getId()) && e.getEntryType() == LedgerEntryType.CREDIT);

        // 4. Verify outbox event TRANSFER_COMPLETED created
        List<OutboxEvent> outboxEvents = outboxEventRepository.findAll();
        assertThat(outboxEvents.size()).isGreaterThan((int) outboxCountBefore);
        assertThat(outboxEvents).anyMatch(e -> e.getEventType() == OutboxEventType.TRANSFER_COMPLETED &&
                e.getAggregateId().equals(transferTx.getId()));

        // 5. Verify audit event TRANSFER_COMPLETED created
        assertThat(auditEventRepository.findAll().size()).isGreaterThan((int) auditCountBefore);
        assertThat(auditEventRepository.findAll()).anyMatch(e -> e.getEventType() == AuditEventType.TRANSFER_COMPLETED &&
                e.getEntityId().equals(transferTx.getId()));

        // 6. Verify execution history recorded
        List<RecurringTransferExecution> executions = executionRepository.findByRecurringTransferId(savedSchedule.getId());
        assertThat(executions).hasSize(1);
        RecurringTransferExecution execution = executions.getFirst();
        assertThat(execution.getStatus()).isEqualTo(RecurringExecutionStatus.SUCCESS);
        assertThat(execution.getTransactionId()).isEqualTo(transferTx.getId());
        assertThat(execution.getFailureReason()).isNull();

        // 7. Verify schedule metadata updated
        RecurringTransfer reloadedSchedule = recurringTransferRepository.findById(savedSchedule.getId()).orElseThrow();
        assertThat(reloadedSchedule.getExecutionCount()).isEqualTo(1);
        assertThat(reloadedSchedule.getLastExecutedAt()).isNotNull();
        assertThat(reloadedSchedule.getStatus()).isEqualTo(RecurringTransferStatus.ACTIVE);
        // Next execution should be strictly in the future
        assertThat(reloadedSchedule.getNextExecutionAt()).isAfter(Instant.now());
    }

    @Test
    @DisplayName("2. Insufficient balance records FAILED execution, leaves schedule ACTIVE, advances to next occurrence")
    void testInsufficientBalanceFailureSemantics() {
        // Alice has balance ₹50, schedule attempts ₹500
        aliceAccount.setBalance(new BigDecimal("50.0000"));
        accountRepository.save(aliceAccount);

        RecurringTransfer schedule = new RecurringTransfer(
                alice,
                aliceAccount,
                bobAccount,
                new BigDecimal("500.0000"),
                "INR",
                RecurringFrequency.WEEKLY,
                LocalDate.now(ZoneOffset.UTC),
                null,
                Instant.now().minusSeconds(10)
        );
        RecurringTransfer savedSchedule = recurringTransferRepository.save(schedule);

        boolean processed = processor.processSingleSchedule(savedSchedule.getId());
        assertThat(processed).isTrue();

        // Balance must remain unchanged
        Account reloadedAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        assertThat(reloadedAlice.getBalance()).isEqualByComparingTo(new BigDecimal("50.0000"));

        // Zero transfer transactions
        assertThat(transactionRepository.findAll().stream().filter(t -> t.getTransactionType() == TransactionType.TRANSFER).count()).isEqualTo(0);

        // Execution recorded as FAILED
        List<RecurringTransferExecution> executions = executionRepository.findByRecurringTransferId(savedSchedule.getId());
        assertThat(executions).hasSize(1);
        RecurringTransferExecution execution = executions.getFirst();
        assertThat(execution.getStatus()).isEqualTo(RecurringExecutionStatus.FAILED);
        assertThat(execution.getTransactionId()).isNull();
        assertThat(execution.getFailureReason()).isEqualTo(RecurringFailureSanitizer.INSUFFICIENT_BALANCE);
        assertThat(execution.getFailureReason()).doesNotContain(aliceAccount.getId().toString());
        assertThat(execution.getFailureReason()).doesNotContain("50");
        assertThat(execution.getFailureReason()).doesNotContain("Exception");

        // Schedule remains ACTIVE and advanced to next week
        RecurringTransfer reloadedSchedule = recurringTransferRepository.findById(savedSchedule.getId()).orElseThrow();
        assertThat(reloadedSchedule.getStatus()).isEqualTo(RecurringTransferStatus.ACTIVE);
        assertThat(reloadedSchedule.getExecutionCount()).isEqualTo(0);
        assertThat(reloadedSchedule.getNextExecutionAt()).isAfter(Instant.now());

        // Subsequent period: fund Alice's account, make schedule due, retry -> SUCCESS
        Account freshAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        freshAlice.setBalance(new BigDecimal("5000.0000"));
        accountRepository.save(freshAlice);

        reloadedSchedule.setNextExecutionAt(Instant.now().minusSeconds(5));
        recurringTransferRepository.save(reloadedSchedule);

        boolean processedNext = processor.processSingleSchedule(savedSchedule.getId());
        assertThat(processedNext).isTrue();

        // Now transfer succeeded!
        Account reloadedAlice2 = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        assertThat(reloadedAlice2.getBalance()).isEqualByComparingTo(new BigDecimal("4500.0000"));

        List<RecurringTransferExecution> allExecutions = executionRepository.findByRecurringTransferId(savedSchedule.getId());
        assertThat(allExecutions).hasSize(2);
        assertThat(allExecutions).anyMatch(e -> e.getStatus() == RecurringExecutionStatus.SUCCESS);
    }

    @Test
    @DisplayName("3. Terminal account condition: closed account causes schedule to be CANCELLED")
    void testClosedAccountCausesCancellation() {
        Account freshAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        freshAlice.setBalance(BigDecimal.ZERO);
        freshAlice.setStatus(AccountStatus.CLOSED);
        accountRepository.save(freshAlice);

        RecurringTransfer schedule = new RecurringTransfer(
                alice,
                aliceAccount,
                bobAccount,
                new BigDecimal("100.0000"),
                "INR",
                RecurringFrequency.MONTHLY,
                LocalDate.now(ZoneOffset.UTC),
                null,
                Instant.now().minusSeconds(10)
        );
        RecurringTransfer savedSchedule = recurringTransferRepository.save(schedule);

        boolean processed = processor.processSingleSchedule(savedSchedule.getId());
        assertThat(processed).isTrue();

        RecurringTransfer reloaded = recurringTransferRepository.findById(savedSchedule.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(RecurringTransferStatus.CANCELLED);

        List<RecurringTransferExecution> executions = executionRepository.findByRecurringTransferId(savedSchedule.getId());
        assertThat(executions).hasSize(1);
        assertThat(executions.getFirst().getStatus()).isEqualTo(RecurringExecutionStatus.FAILED);
    }

    @Test
    @DisplayName("4. Deterministic idempotency: retrying identical schedule slot returns existing transfer without duplication")
    void testDeterministicIdempotencyAndCrashRetry() {
        Instant fixedSlot = Instant.now().minusSeconds(60);

        RecurringTransfer schedule = new RecurringTransfer(
                alice,
                aliceAccount,
                bobAccount,
                new BigDecimal("200.0000"),
                "INR",
                RecurringFrequency.DAILY,
                LocalDate.now(ZoneOffset.UTC),
                null,
                fixedSlot
        );
        RecurringTransfer savedSchedule = recurringTransferRepository.save(schedule);

        // Step 1: Normal processing of the slot
        processor.processSingleSchedule(savedSchedule.getId());

        Account aliceAfter1 = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        assertThat(aliceAfter1.getBalance()).isEqualByComparingTo(new BigDecimal("4800.0000"));
        assertThat(transactionRepository.findAll().stream().filter(t -> t.getTransactionType() == TransactionType.TRANSFER).count()).isEqualTo(1);

        // Step 2: Simulate crash before schedule advancement by resetting nextExecutionAt back to fixedSlot
        // but preserving the committed financial transaction
        RecurringTransfer crashState = recurringTransferRepository.findById(savedSchedule.getId()).orElseThrow();
        crashState.setNextExecutionAt(fixedSlot);
        recurringTransferRepository.save(crashState);

        // Worker B retries the exact same schedule and slot
        processor.processSingleSchedule(savedSchedule.getId());

        // Assert: NO DUPLICATE FINANCIAL MUTATION! Balance remains 4800, exactly 1 transfer transaction!
        Account aliceAfterRetry = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        assertThat(aliceAfterRetry.getBalance()).isEqualByComparingTo(new BigDecimal("4800.0000"));
        assertThat(transactionRepository.findAll().stream().filter(t -> t.getTransactionType() == TransactionType.TRANSFER).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("5. Missed executions: one catch-up executed, schedule converges to future occurrence")
    void testMissedExecutionCatchUpAndConvergence() {
        // Schedule due 60 days ago
        LocalDate pastDate = LocalDate.now(ZoneOffset.UTC).minusDays(60);
        Instant pastSlot = pastDate.atStartOfDay(ZoneOffset.UTC).toInstant();

        RecurringTransfer schedule = new RecurringTransfer(
                alice,
                aliceAccount,
                bobAccount,
                new BigDecimal("100.0000"),
                "INR",
                RecurringFrequency.DAILY,
                pastDate,
                null,
                pastSlot
        );
        RecurringTransfer savedSchedule = recurringTransferRepository.save(schedule);

        processor.processSingleSchedule(savedSchedule.getId());

        // Exactly one catch-up execution occurred (NOT 60 immediate transfers!)
        Account reloadedAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        assertThat(reloadedAlice.getBalance()).isEqualByComparingTo(new BigDecimal("4900.0000"));

        List<RecurringTransferExecution> executions = executionRepository.findByRecurringTransferId(savedSchedule.getId());
        assertThat(executions).hasSize(1);

        // Schedule converged directly to future
        RecurringTransfer reloaded = recurringTransferRepository.findById(savedSchedule.getId()).orElseThrow();
        assertThat(reloaded.getNextExecutionAt()).isAfter(Instant.now());
    }

    @Test
    @DisplayName("6. Inclusive end date: slot on end date executes, then schedule transitions to COMPLETED")
    void testInclusiveEndDateExecutionAndCompletion() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Instant slot = today.atStartOfDay(ZoneOffset.UTC).toInstant();

        RecurringTransfer schedule = new RecurringTransfer(
                alice,
                aliceAccount,
                bobAccount,
                new BigDecimal("100.0000"),
                "INR",
                RecurringFrequency.DAILY,
                today,
                today, // End date equals start date
                slot
        );
        RecurringTransfer savedSchedule = recurringTransferRepository.save(schedule);

        processor.processSingleSchedule(savedSchedule.getId());

        // Successfully executed the slot on the end date
        Account reloadedAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        assertThat(reloadedAlice.getBalance()).isEqualByComparingTo(new BigDecimal("4900.0000"));

        // Schedule completed because next candidate exceeds end date
        RecurringTransfer reloaded = recurringTransferRepository.findById(savedSchedule.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(RecurringTransferStatus.COMPLETED);

        // Further processor ticks will not process completed schedule
        boolean rerun = processor.processSingleSchedule(savedSchedule.getId());
        assertThat(rerun).isFalse();
    }

    @Test
    @DisplayName("7. Failure Sanitization: Domain exceptions map to bounded messages, unknown errors map to generic safe message without leaking UUIDs, balances, or SQL")
    void testGenericFailureSanitization() {
        // Test sanitization unit mappings
        assertThat(RecurringFailureSanitizer.sanitize(new RuntimeException("Syntax error in SQL near UUID 123e4567-e89b-12d3-a456-426614174000 with balance 999.00")))
                .isEqualTo(RecurringFailureSanitizer.GENERIC_FAILURE);

        assertThat(RecurringFailureSanitizer.sanitize(new com.parth.ledger.transaction.exception.InsufficientBalanceException("Insufficient balance: 50.0000")))
                .isEqualTo(RecurringFailureSanitizer.INSUFFICIENT_BALANCE);

        assertThat(RecurringFailureSanitizer.sanitize(new com.parth.ledger.account.AccountFrozenException("Account is frozen: 123e4567-e89b-12d3-a456-426614174000")))
                .isEqualTo(RecurringFailureSanitizer.ACCOUNT_FROZEN);

        assertThat(RecurringFailureSanitizer.sanitize(new com.parth.ledger.policy.PolicyViolationException(
                com.parth.ledger.policy.PolicyErrorCode.POLICY_TRANSACTION_LIMIT_EXCEEDED, "Daily limit exceeded")))
                .isEqualTo(RecurringFailureSanitizer.POLICY_LIMIT_EXCEEDED);

        // Test string scrubbing
        String leakedString = "Error processing account 123e4567-e89b-12d3-a456-426614174000 with balance 5000.0000 at org.hibernate.SQL";
        String sanitized = RecurringFailureSanitizer.sanitizeString(leakedString);
        assertThat(sanitized).doesNotContain("123e4567-e89b-12d3-a456-426614174000");
        assertThat(sanitized).doesNotContain("org.hibernate");
    }

    @Test
    @DisplayName("8. Crash-window real transaction boundary: inner financial tx commits in REQUIRES_NEW, outer scheduler tx rolls back, subsequent retry succeeds via deterministic idempotency")
    void testCrashWindowRealTransactionBoundary() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Instant slot = today.atStartOfDay(ZoneOffset.UTC).toInstant();

        RecurringTransfer schedule = new RecurringTransfer(
                alice,
                aliceAccount,
                bobAccount,
                new BigDecimal("200.0000"),
                "INR",
                RecurringFrequency.DAILY,
                today,
                null,
                slot
        );
        RecurringTransfer savedSchedule = recurringTransferRepository.save(schedule);

        UUID executionIdentity = RecurringTransferProcessor.computeExecutionIdentity(savedSchedule.getId(), slot);
        String idempotencyKey = executionIdentity.toString();

        TransferRequestDto transferRequest = new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("200.0000"),
                "INR"
        );

        // Step 1: Start outer transaction simulating RecurringTransferProcessor outer tx.
        // Inside this outer tx, establish SecurityContext, call TransferService.executeTransfer (which commits in REQUIRES_NEW),
        // update schedule in memory, and then simulate a crash/power failure by throwing a RuntimeException.
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> {
            txTemplate.execute(status -> {
                // Set SecurityContext for Alice
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                org.springframework.security.core.userdetails.User principal =
                        new org.springframework.security.core.userdetails.User(
                                alice.getEmail(),
                                "",
                                java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER"))
                        );
                context.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        principal, null, principal.getAuthorities()));
                SecurityContextHolder.setContext(context);

                try {
                    // Call TransferService - this runs in REQUIRES_NEW and commits to PostgreSQL independently!
                    transferService.executeTransfer(idempotencyKey, transferRequest);

                    // Outer transaction simulates partial work (e.g. updating schedule)
                    RecurringTransfer lockedSchedule = recurringTransferRepository.findById(savedSchedule.getId()).orElseThrow();
                    lockedSchedule.incrementExecutionCount();
                    recurringTransferRepository.save(lockedSchedule);

                    // Simulate crash / power failure / unhandled error BEFORE outer transaction can commit
                    throw new RuntimeException("Simulated crash right after financial commit!");
                } finally {
                    SecurityContextHolder.clearContext();
                }
            });
        }).isInstanceOf(RuntimeException.class).hasMessageContaining("Simulated crash");

        // Step 2: VERIFY POST-CRASH STATE:
        // 1. Inner financial transaction committed:
        Account reloadedAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        Account reloadedBob = accountRepository.findById(bobAccount.getId()).orElseThrow();
        assertThat(reloadedAlice.getBalance()).isEqualByComparingTo(new BigDecimal("4800.0000")); // 5000 - 200
        assertThat(reloadedBob.getBalance()).isEqualByComparingTo(new BigDecimal("1200.0000")); // 1000 + 200

        List<Transaction> transfers = transactionRepository.findAll().stream()
                .filter(t -> t.getTransactionType() == TransactionType.TRANSFER)
                .toList();
        assertThat(transfers).hasSize(1);
        UUID firstTxId = transfers.getFirst().getId();

        List<LedgerEntry> entries = ledgerEntryRepository.findByTransactionId(firstTxId);
        assertThat(entries).hasSize(2);

        // 2. Outer transaction rolled back:
        RecurringTransfer rolledBackSchedule = recurringTransferRepository.findById(savedSchedule.getId()).orElseThrow();
        // Execution count rolled back to 0
        assertThat(rolledBackSchedule.getExecutionCount()).isEqualTo(0);
        // Next execution at is still the original slot (schedule did NOT advance)
        assertThat(rolledBackSchedule.getNextExecutionAt()).isEqualTo(slot);
        // No execution record was committed in the outer tx
        List<RecurringTransferExecution> executionsBeforeRetry = executionRepository.findByRecurringTransferId(savedSchedule.getId());
        assertThat(executionsBeforeRetry).isEmpty();

        // Step 3: Worker recovers / scheduler ticks again for the same slot.
        // It processes the schedule whose nextExecutionAt is still `slot`.
        boolean processed = processor.processSingleSchedule(savedSchedule.getId());
        assertThat(processed).isTrue();

        // Step 4: VERIFY DETERMINISTIC IDEMPOTENCY PREVENTED DOUBLE-DEBIT:
        // 1. Balances MUST NOT be debited again!
        Account aliceAfterRetry = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        Account bobAfterRetry = accountRepository.findById(bobAccount.getId()).orElseThrow();
        assertThat(aliceAfterRetry.getBalance()).isEqualByComparingTo(new BigDecimal("4800.0000")); // Still 4800! NOT 4600!
        assertThat(bobAfterRetry.getBalance()).isEqualByComparingTo(new BigDecimal("1200.0000")); // Still 1200! NOT 1400!

        // 2. Still exactly 1 transfer transaction and 2 ledger entries!
        List<Transaction> transfersAfterRetry = transactionRepository.findAll().stream()
                .filter(t -> t.getTransactionType() == TransactionType.TRANSFER)
                .toList();
        assertThat(transfersAfterRetry).hasSize(1);
        assertThat(transfersAfterRetry.getFirst().getId()).isEqualTo(firstTxId);

        // 3. Execution record successfully saved with SUCCESS and referencing the original transaction
        List<RecurringTransferExecution> executionsAfterRetry = executionRepository.findByRecurringTransferId(savedSchedule.getId());
        assertThat(executionsAfterRetry).hasSize(1);
        RecurringTransferExecution execution = executionsAfterRetry.getFirst();
        assertThat(execution.getStatus()).isEqualTo(RecurringExecutionStatus.SUCCESS);
        assertThat(execution.getTransactionId()).isEqualTo(firstTxId);

        // 4. Outer schedule has now successfully committed its advancement
        RecurringTransfer completedSchedule = recurringTransferRepository.findById(savedSchedule.getId()).orElseThrow();
        assertThat(completedSchedule.getExecutionCount()).isEqualTo(1);
        assertThat(completedSchedule.getNextExecutionAt()).isAfter(slot);
    }
}
