package com.parth.ledger.recurring;

import com.parth.ledger.BaseIntegrationTest;
import com.parth.ledger.account.Account;
import com.parth.ledger.account.AccountRepository;
import com.parth.ledger.account.AccountStatus;
import com.parth.ledger.audit.AuditEventRepository;
import com.parth.ledger.audit.AuditEventType;
import com.parth.ledger.ledger.LedgerEntryRepository;
import com.parth.ledger.outbox.OutboxEventRepository;
import com.parth.ledger.recurring.dto.CreateRecurringTransferRequestDto;
import com.parth.ledger.system.SystemFundingService;
import com.parth.ledger.transaction.TransactionRepository;
import com.parth.ledger.user.User;
import com.parth.ledger.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@DisplayName("V2.6 Recurring Transfer Lifecycle & Ownership Integration Tests")
class RecurringTransferLifecycleIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

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
    private SystemFundingService systemFundingService;

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

        alice = userRepository.save(new User("alice.lifecycle@ledger.com", "Alice"));
        bob = userRepository.save(new User("bob.lifecycle@ledger.com", "Bob"));

        aliceAccount = accountRepository.save(new Account(alice, "INR", new BigDecimal("5000.0000")));
        bobAccount = accountRepository.save(new Account(bob, "INR", new BigDecimal("2000.0000")));
    }

    @Test
    @WithMockUser(username = "alice.lifecycle@ledger.com", roles = {"USER"})
    @DisplayName("Creation creates schedule metadata and DOES NOT perform a financial transfer")
    void testCreationDoesNotPerformTransfer() throws Exception {
        CreateRecurringTransferRequestDto request = new CreateRecurringTransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("500.0000"),
                "INR",
                RecurringFrequency.MONTHLY,
                LocalDate.of(2026, 11, 1),
                LocalDate.of(2027, 11, 1)
        );

        long txCountBefore = transactionRepository.count();
        long ledgerCountBefore = ledgerEntryRepository.count();
        long outboxCountBefore = outboxEventRepository.count();

        mockMvc.perform(post("/api/v1/recurring-transfers")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.frequency").value("MONTHLY"))
                .andExpect(jsonPath("$.amount").value(500.0))
                .andExpect(jsonPath("$.executionCount").value(0))
                .andExpect(jsonPath("$.nextExecutionAt").value("2026-11-01T00:00:00Z"));

        // INVARIANT CHECKS: Absolutely NO financial transfer must have happened!
        Account reloadedAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        Account reloadedBob = accountRepository.findById(bobAccount.getId()).orElseThrow();
        assertThat(reloadedAlice.getBalance()).isEqualByComparingTo(new BigDecimal("5000.0000"));
        assertThat(reloadedBob.getBalance()).isEqualByComparingTo(new BigDecimal("2000.0000"));

        // Zero additional financial transactions created
        assertThat(transactionRepository.count()).isEqualTo(txCountBefore);

        // Zero additional ledger entries created
        assertThat(ledgerEntryRepository.count()).isEqualTo(ledgerCountBefore);

        // Zero additional transfer outbox events created
        assertThat(outboxEventRepository.count()).isEqualTo(outboxCountBefore);

        // Audit event for schedule creation must exist
        assertThat(auditEventRepository.findAll()).anyMatch(
                e -> e.getEventType() == AuditEventType.RECURRING_TRANSFER_CREATED &&
                        e.getActorUserId().equals(alice.getId())
        );
    }

    @Test
    @WithMockUser(username = "alice.lifecycle@ledger.com", roles = {"USER"})
    @DisplayName("Creation fails if source account is not owned by authenticated user (403 Forbidden)")
    void testCreationUnauthorizedSourceAccount() throws Exception {
        CreateRecurringTransferRequestDto request = new CreateRecurringTransferRequestDto(
                bobAccount.getId(), // Bob's account!
                aliceAccount.getId(),
                new BigDecimal("100.0000"),
                "INR",
                RecurringFrequency.WEEKLY,
                LocalDate.of(2026, 11, 1),
                null
        );

        mockMvc.perform(post("/api/v1/recurring-transfers")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "alice.lifecycle@ledger.com", roles = {"USER"})
    @DisplayName("Creation fails if source account is FROZEN (422 Unprocessable Content)")
    void testCreationFrozenSourceAccount() throws Exception {
        aliceAccount.setStatus(AccountStatus.FROZEN);
        accountRepository.save(aliceAccount);

        CreateRecurringTransferRequestDto request = new CreateRecurringTransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("100.0000"),
                "INR",
                RecurringFrequency.DAILY,
                LocalDate.of(2026, 11, 1),
                null
        );

        mockMvc.perform(post("/api/v1/recurring-transfers")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @WithMockUser(username = "alice.lifecycle@ledger.com", roles = {"USER"})
    @DisplayName("Creation fails if source == destination (400 Bad Request)")
    void testCreationSameAccountRejected() throws Exception {
        CreateRecurringTransferRequestDto request = new CreateRecurringTransferRequestDto(
                aliceAccount.getId(),
                aliceAccount.getId(),
                new BigDecimal("100.0000"),
                "INR",
                RecurringFrequency.DAILY,
                LocalDate.of(2026, 11, 1),
                null
        );

        mockMvc.perform(post("/api/v1/recurring-transfers")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "alice.lifecycle@ledger.com", roles = {"USER"})
    @DisplayName("Creation fails if end_date < start_date (400 Bad Request)")
    void testCreationEndDateBeforeStartDate() throws Exception {
        CreateRecurringTransferRequestDto request = new CreateRecurringTransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("100.0000"),
                "INR",
                RecurringFrequency.DAILY,
                LocalDate.of(2026, 11, 10),
                LocalDate.of(2026, 11, 5) // Invalid!
        );

        mockMvc.perform(post("/api/v1/recurring-transfers")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "bob.lifecycle@ledger.com", roles = {"USER"})
    @DisplayName("Ownership: Bob cannot view Alice's schedule by ID (404 Anti-enumeration)")
    void testOwnershipNonOwnerCannotView() throws Exception {
        RecurringTransfer schedule = recurringTransferRepository.save(new RecurringTransfer(
                alice,
                aliceAccount,
                bobAccount,
                new BigDecimal("250.0000"),
                "INR",
                RecurringFrequency.MONTHLY,
                LocalDate.of(2026, 11, 1),
                null,
                Instant.parse("2026-11-01T00:00:00Z")
        ));

        // Bob queries Alice's schedule
        mockMvc.perform(get("/api/v1/recurring-transfers/" + schedule.getId()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "bob.lifecycle@ledger.com", roles = {"USER"})
    @DisplayName("Ownership: Bob cannot mutate Alice's schedule (404 Anti-enumeration)")
    void testOwnershipNonOwnerCannotMutate() throws Exception {
        RecurringTransfer schedule = recurringTransferRepository.save(new RecurringTransfer(
                alice,
                aliceAccount,
                bobAccount,
                new BigDecimal("250.0000"),
                "INR",
                RecurringFrequency.MONTHLY,
                LocalDate.of(2026, 11, 1),
                null,
                Instant.parse("2026-11-01T00:00:00Z")
        ));

        mockMvc.perform(post("/api/v1/recurring-transfers/" + schedule.getId() + "/pause")
                        .with(csrf()))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/recurring-transfers/" + schedule.getId() + "/resume")
                        .with(csrf()))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/recurring-transfers/" + schedule.getId() + "/cancel")
                        .with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "admin.lifecycle@ledger.com", roles = {"ADMIN"})
    @DisplayName("Ownership: Admin can mutate another user's schedule")
    void testAdminCanMutateOthersSchedule() throws Exception {
        userRepository.save(new User("admin.lifecycle@ledger.com", "Admin"));

        RecurringTransfer schedule = recurringTransferRepository.save(new RecurringTransfer(
                alice,
                aliceAccount,
                bobAccount,
                new BigDecimal("250.0000"),
                "INR",
                RecurringFrequency.MONTHLY,
                LocalDate.of(2026, 11, 1),
                null,
                Instant.parse("2026-11-01T00:00:00Z")
        ));

        mockMvc.perform(post("/api/v1/recurring-transfers/" + schedule.getId() + "/pause")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAUSED"));
    }

    @Test
    @WithMockUser(username = "alice.lifecycle@ledger.com", roles = {"USER"})
    @DisplayName("Contract: DTO failureCount accurately reflects persisted FAILED executions")
    void testFailureCountContractReflectsFailedExecutions() throws Exception {
        RecurringTransfer schedule = recurringTransferRepository.save(new RecurringTransfer(
                alice,
                aliceAccount,
                bobAccount,
                new BigDecimal("250.0000"),
                "INR",
                RecurringFrequency.MONTHLY,
                LocalDate.of(2026, 11, 1),
                null,
                Instant.parse("2026-11-01T00:00:00Z")
        ));

        com.parth.ledger.transaction.Transaction tx = transactionRepository.save(new com.parth.ledger.transaction.Transaction(
                UUID.randomUUID().toString(),
                new BigDecimal("250.0000"),
                "INR",
                com.parth.ledger.transaction.TransactionStatus.COMPLETED,
                aliceAccount,
                bobAccount
        ));

        // Create 2 FAILED executions and 1 SUCCESS execution
        RecurringTransferExecution exec1 = new RecurringTransferExecution(
                schedule,
                UUID.randomUUID().toString(),
                Instant.parse("2026-11-01T00:00:00Z"),
                null,
                RecurringExecutionStatus.FAILED,
                RecurringFailureSanitizer.INSUFFICIENT_BALANCE,
                Instant.parse("2026-11-01T00:00:01Z")
        );
        RecurringTransferExecution exec2 = new RecurringTransferExecution(
                schedule,
                UUID.randomUUID().toString(),
                Instant.parse("2026-12-01T00:00:00Z"),
                null,
                RecurringExecutionStatus.FAILED,
                RecurringFailureSanitizer.GENERIC_FAILURE,
                Instant.parse("2026-12-01T00:00:01Z")
        );
        RecurringTransferExecution exec3 = new RecurringTransferExecution(
                schedule,
                UUID.randomUUID().toString(),
                Instant.parse("2027-01-01T00:00:00Z"),
                tx.getId(),
                RecurringExecutionStatus.SUCCESS,
                null,
                Instant.parse("2027-01-01T00:00:01Z")
        );
        executionRepository.saveAll(java.util.List.of(exec1, exec2, exec3));

        schedule.incrementExecutionCount();
        recurringTransferRepository.save(schedule);

        // 1. Single schedule GET
        mockMvc.perform(get("/api/v1/recurring-transfers/" + schedule.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionCount").value(1))
                .andExpect(jsonPath("$.failureCount").value(2));

        // 2. Schedule list GET
        mockMvc.perform(get("/api/v1/recurring-transfers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].executionCount").value(1))
                .andExpect(jsonPath("$.content[0].failureCount").value(2));
    }

    @Test
    @WithMockUser(username = "alice.lifecycle@ledger.com", roles = {"USER"})
    @DisplayName("Lifecycle: ACTIVE -> PAUSED -> ACTIVE -> CANCELLED, with terminal invariants")
    void testCompleteLifecycleTransitions() throws Exception {
        RecurringTransfer schedule = recurringTransferRepository.save(new RecurringTransfer(
                alice,
                aliceAccount,
                bobAccount,
                new BigDecimal("300.0000"),
                "INR",
                RecurringFrequency.WEEKLY,
                LocalDate.of(2026, 11, 1),
                null,
                Instant.parse("2026-11-01T00:00:00Z")
        ));

        // 1. ACTIVE -> PAUSED
        mockMvc.perform(post("/api/v1/recurring-transfers/" + schedule.getId() + "/pause")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAUSED"));

        // Verify audit event
        assertThat(auditEventRepository.findAll()).anyMatch(
                e -> e.getEventType() == AuditEventType.RECURRING_TRANSFER_PAUSED &&
                        e.getEntityId().equals(schedule.getId())
        );

        // 2. PAUSED -> ACTIVE (Resume)
        mockMvc.perform(post("/api/v1/recurring-transfers/" + schedule.getId() + "/resume")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        assertThat(auditEventRepository.findAll()).anyMatch(
                e -> e.getEventType() == AuditEventType.RECURRING_TRANSFER_RESUMED &&
                        e.getEntityId().equals(schedule.getId())
        );

        // 3. ACTIVE -> CANCELLED
        mockMvc.perform(post("/api/v1/recurring-transfers/" + schedule.getId() + "/cancel")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(auditEventRepository.findAll()).anyMatch(
                e -> e.getEventType() == AuditEventType.RECURRING_TRANSFER_CANCELLED &&
                        e.getEntityId().equals(schedule.getId())
        );

        // 4. CANCELLED is terminal: cannot resume (409 Conflict)
        mockMvc.perform(post("/api/v1/recurring-transfers/" + schedule.getId() + "/resume")
                        .with(csrf()))
                .andExpect(status().isConflict());

        // 5. CANCELLED is terminal: cannot pause (409 Conflict)
        mockMvc.perform(post("/api/v1/recurring-transfers/" + schedule.getId() + "/pause")
                        .with(csrf()))
                .andExpect(status().isConflict());
    }

    @Test
    @WithMockUser(username = "alice.lifecycle@ledger.com", roles = {"USER"})
    @DisplayName("COMPLETED schedule is terminal: cannot be paused, resumed, or cancelled")
    void testCompletedScheduleIsTerminal() throws Exception {
        RecurringTransfer schedule = new RecurringTransfer(
                alice,
                aliceAccount,
                bobAccount,
                new BigDecimal("100.0000"),
                "INR",
                RecurringFrequency.MONTHLY,
                LocalDate.of(2026, 11, 1),
                LocalDate.of(2026, 11, 1),
                Instant.parse("2026-11-01T00:00:00Z")
        );
        schedule.setStatus(RecurringTransferStatus.COMPLETED);
        RecurringTransfer saved = recurringTransferRepository.save(schedule);

        mockMvc.perform(post("/api/v1/recurring-transfers/" + saved.getId() + "/pause")
                        .with(csrf()))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/v1/recurring-transfers/" + saved.getId() + "/resume")
                        .with(csrf()))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/v1/recurring-transfers/" + saved.getId() + "/cancel")
                        .with(csrf()))
                .andExpect(status().isConflict());
    }
}
