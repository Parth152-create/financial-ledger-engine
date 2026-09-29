package com.parth.ledger.audit;

import com.parth.ledger.BaseIntegrationTest;
import com.parth.ledger.account.Account;
import com.parth.ledger.account.AccountRepository;
import com.parth.ledger.account.AccountStatus;
import com.parth.ledger.account.AccountType;
import com.parth.ledger.deposit.dto.DepositRequestDto;
import com.parth.ledger.deposit.service.DepositService;
import com.parth.ledger.security.CustomUserDetailsService;
import com.parth.ledger.security.dto.LinkPasswordRequestDto;
import com.parth.ledger.security.dto.LoginRequestDto;
import com.parth.ledger.security.dto.SignupRequestDto;
import com.parth.ledger.transaction.Transaction;
import com.parth.ledger.transaction.TransactionRepository;
import com.parth.ledger.transaction.dto.TransferRequestDto;
import com.parth.ledger.transaction.service.TransferService;
import com.parth.ledger.user.User;
import com.parth.ledger.user.UserRepository;
import com.parth.ledger.withdrawal.dto.WithdrawalRequestDto;
import com.parth.ledger.withdrawal.service.WithdrawalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class AuditEventIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private TransferService transferService;

    @Autowired
    private DepositService depositService;

    @Autowired
    private WithdrawalService withdrawalService;

    @Autowired
    private CustomUserDetailsService customUserDetailsService;

    private User alice;
    private User bob;
    private User admin;
    private Account aliceAccount;
    private Account bobAccount;

    @BeforeEach
    void setupUsersAndAccounts() {
        if (jdbcTemplate != null) {
            try {
                jdbcTemplate.execute("TRUNCATE TABLE audit_events CASCADE");
                jdbcTemplate.execute("TRUNCATE TABLE ledger_entries CASCADE");
                jdbcTemplate.execute("TRUNCATE TABLE transactions CASCADE");
                jdbcTemplate.execute("TRUNCATE TABLE accounts CASCADE");
                jdbcTemplate.execute("TRUNCATE TABLE user_credentials CASCADE");
                jdbcTemplate.execute("TRUNCATE TABLE users CASCADE");
            } catch (Exception ignored) {
            }
        }
        clearRedis();

        alice = userRepository.save(new User("alice.audit@example.com", "Alice Audit"));
        bob = userRepository.save(new User("bob.audit@example.com", "Bob Audit"));
        admin = userRepository.save(new User("admin.audit@ledger.com", "Admin Audit"));

        jdbcTemplate.update(
                "INSERT INTO accounts (id, user_id, currency, balance, version, created_at, updated_at, account_type, status, account_number) " +
                "VALUES ('00000000-0000-0000-0000-000000000001', NULL, 'INR', 1000000.0000, 0, NOW(), NOW(), 'SYSTEM_CLEARING', 'ACTIVE', 'ACCT-SYSTEM-CLEARING-01') " +
                "ON CONFLICT (id) DO UPDATE SET balance = EXCLUDED.balance, status = 'ACTIVE'"
        );

        aliceAccount = accountRepository.save(new Account(
                alice,
                "INR",
                new BigDecimal("5000.0000"),
                AccountType.USER_CHECKING,
                AccountStatus.ACTIVE,
                "ACCT-ALICE-AUDIT-01"
        ));

        bobAccount = accountRepository.save(new Account(
                bob,
                "INR",
                new BigDecimal("2000.0000"),
                AccountType.USER_CHECKING,
                AccountStatus.ACTIVE,
                "ACCT-BOB-AUDIT-01"
        ));
    }

    private void authenticate(User u, String... roles) {
        List<SimpleGrantedAuthority> authorities = roles.length == 0
                ? List.of(new SimpleGrantedAuthority("ROLE_USER"))
                : java.util.Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList();
        UserDetails details = new org.springframework.security.core.userdetails.User(u.getEmail(), "N/A", authorities);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(details, null, authorities)
        );
    }

    @Test
    @DisplayName("1. Authentication: Signup, Login, Password Change, and Logout should record operational audit events")
    void shouldAuditAuthenticationLifecycle() throws Exception {
        // A. Signup
        SignupRequestDto signupDto = new SignupRequestDto("Charlie Audit", "charlie.audit@example.com", "SecurePass123");
        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(signupDto)))
                .andExpect(status().isCreated());

        List<AuditEvent> signupEvents = auditEventRepository.findAll().stream()
                .filter(e -> e.getEventType() == AuditEventType.AUTH_SIGNUP)
                .toList();
        assertThat(signupEvents).hasSize(1);
        AuditEvent signupEvent = signupEvents.get(0);
        assertThat(signupEvent.getEntityType()).isEqualTo(AuditEntityType.USER);
        assertThat(signupEvent.getMetadata()).containsEntry("email", "charlie.audit@example.com");

        // B. Successful Login
        LoginRequestDto loginDto = new LoginRequestDto("charlie.audit@example.com", "SecurePass123");
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginDto)))
                .andExpect(status().isOk())
                .andReturn();

        List<AuditEvent> loginEvents = auditEventRepository.findAll().stream()
                .filter(e -> e.getEventType() == AuditEventType.AUTH_LOGIN)
                .toList();
        assertThat(loginEvents).hasSize(1);
        assertThat(loginEvents.get(0).getMetadata()).containsEntry("method", "PASSWORD");

        // C. Failed Login should NOT produce AUTH_LOGIN event
        LoginRequestDto badLoginDto = new LoginRequestDto("charlie.audit@example.com", "WrongPassword999");
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(badLoginDto)))
                .andExpect(status().isUnauthorized());

        loginEvents = auditEventRepository.findAll().stream()
                .filter(e -> e.getEventType() == AuditEventType.AUTH_LOGIN)
                .toList();
        assertThat(loginEvents).hasSize(1); // Still exactly 1!

        // D. Password Change
        LinkPasswordRequestDto linkDto = new LinkPasswordRequestDto("NewSecurePass456");
        mockMvc.perform(post("/api/v1/auth/link-password")
                        .with(user("charlie.audit@example.com").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(linkDto)))
                .andExpect(status().isOk());

        List<AuditEvent> passwordEvents = auditEventRepository.findAll().stream()
                .filter(e -> e.getEventType() == AuditEventType.PASSWORD_CHANGED)
                .toList();
        assertThat(passwordEvents).hasSize(1);
        assertThat(passwordEvents.get(0).getMetadata()).doesNotContainKey("password");

        // E. Logout
        var session = loginResult.getRequest().getSession();
        mockMvc.perform(post("/logout")
                        .session((org.springframework.mock.web.MockHttpSession) session)
                        .with(user("charlie.audit@example.com").roles("USER"))
                        .with(csrf()))
                .andExpect(status().isOk());

        List<AuditEvent> logoutEvents = auditEventRepository.findAll().stream()
                .filter(e -> e.getEventType() == AuditEventType.AUTH_LOGOUT)
                .toList();
        assertThat(logoutEvents).hasSize(1);
    }

    @Test
    @DisplayName("2. Account Lifecycle: Create, Freeze, Unfreeze, Close should record operational audit events")
    void shouldAuditAccountLifecycle() throws Exception {
        // A. Account Created
        mockMvc.perform(post("/api/v1/accounts")
                        .with(user("alice.audit@example.com").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\": \"INR\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currency", is("INR")));

        List<AuditEvent> accountCreatedEvents = auditEventRepository.findAll().stream()
                .filter(e -> e.getEventType() == AuditEventType.ACCOUNT_CREATED)
                .toList();
        assertThat(accountCreatedEvents).hasSize(1);
        assertThat(accountCreatedEvents.get(0).getEntityType()).isEqualTo(AuditEntityType.ACCOUNT);
        assertThat(accountCreatedEvents.get(0).getActorUserId()).isEqualTo(alice.getId());

        // B. Account Frozen (Admin operation)
        mockMvc.perform(post("/api/v1/accounts/" + aliceAccount.getId() + "/freeze")
                        .with(user("admin.audit@ledger.com").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("FROZEN")));

        List<AuditEvent> freezeEvents = auditEventRepository.findAll().stream()
                .filter(e -> e.getEventType() == AuditEventType.ACCOUNT_FROZEN)
                .toList();
        assertThat(freezeEvents).hasSize(1);
        assertThat(freezeEvents.get(0).getEntityId()).isEqualTo(aliceAccount.getId());
        assertThat(freezeEvents.get(0).getActorUserId()).isEqualTo(admin.getId());

        // C. Account Unfrozen (Admin operation)
        mockMvc.perform(post("/api/v1/accounts/" + aliceAccount.getId() + "/unfreeze")
                        .with(user("admin.audit@ledger.com").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("ACTIVE")));

        List<AuditEvent> unfreezeEvents = auditEventRepository.findAll().stream()
                .filter(e -> e.getEventType() == AuditEventType.ACCOUNT_UNFROZEN)
                .toList();
        assertThat(unfreezeEvents).hasSize(1);

        // D. Account Closed with non-zero balance fails and does NOT record ACCOUNT_CLOSED
        mockMvc.perform(post("/api/v1/accounts/" + aliceAccount.getId() + "/close")
                        .with(user("alice.audit@example.com").roles("USER"))
                        .with(csrf()))
                .andExpect(status().isUnprocessableEntity());

        List<AuditEvent> closeEvents = auditEventRepository.findAll().stream()
                .filter(e -> e.getEventType() == AuditEventType.ACCOUNT_CLOSED)
                .toList();
        assertThat(closeEvents).isEmpty();

        // E. Account Closed with zero balance succeeds and records ACCOUNT_CLOSED
        Account currentAliceAccount = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        currentAliceAccount.setBalance(BigDecimal.ZERO.setScale(4));
        accountRepository.save(currentAliceAccount);

        mockMvc.perform(post("/api/v1/accounts/" + aliceAccount.getId() + "/close")
                        .with(user("alice.audit@example.com").roles("USER"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("CLOSED")));

        closeEvents = auditEventRepository.findAll().stream()
                .filter(e -> e.getEventType() == AuditEventType.ACCOUNT_CLOSED)
                .toList();
        assertThat(closeEvents).hasSize(1);
    }

    @Test
    @DisplayName("3. Financial Transfer: Atomicity and Idempotency guarantee exactly one audit event")
    void shouldAuditTransferAndPreventDuplicateOnIdempotencyReplay() {
        authenticate(alice);

        String idempotencyKey = "transfer-audit-test-" + UUID.randomUUID();
        TransferRequestDto request = new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("500.0000"),
                "INR",
                "Split bill"
        );

        // First attempt: should execute transfer and record TRANSFER_COMPLETED
        var response1 = transferService.executeTransfer(idempotencyKey, request);
        assertThat(response1.status()).isEqualTo(com.parth.ledger.transaction.TransactionStatus.COMPLETED);

        List<AuditEvent> transferEvents = auditEventRepository.findAll().stream()
                .filter(e -> e.getEventType() == AuditEventType.TRANSFER_COMPLETED)
                .toList();
        assertThat(transferEvents).hasSize(1);
        AuditEvent event = transferEvents.get(0);
        assertThat(event.getEntityType()).isEqualTo(AuditEntityType.TRANSACTION);
        assertThat(event.getEntityId()).isEqualTo(response1.transactionId());
        assertThat(event.getActorUserId()).isEqualTo(alice.getId());
        assertThat(new BigDecimal(event.getMetadata().get("amount").toString())).isEqualByComparingTo("500.0000");
        assertThat(event.getMetadata()).containsEntry("currency", "INR");

        // Second attempt: Idempotent replay with same key should return original and NOT create a second audit event!
        var response2 = transferService.executeTransfer(idempotencyKey, request);
        assertThat(response2.transactionId()).isEqualTo(response1.transactionId());

        transferEvents = auditEventRepository.findAll().stream()
                .filter(e -> e.getEventType() == AuditEventType.TRANSFER_COMPLETED)
                .toList();
        assertThat(transferEvents).hasSize(1); // Invariant maintained: exactly one completion event
    }

    @Test
    @DisplayName("4. Financial Deposit and Withdrawal: Atomicity and Idempotency")
    void shouldAuditDepositAndWithdrawal() {
        authenticate(alice);

        // A. Deposit
        String depositKey = "dep-audit-test-" + UUID.randomUUID();
        DepositRequestDto depositRequest = new DepositRequestDto(
                aliceAccount.getId(),
                new BigDecimal("1000.0000"),
                "INR",
                "Top-up deposit"
        );
        var depResult = depositService.processDeposit(depositKey, depositRequest);
        assertThat(depResult.replayed()).isFalse();

        List<AuditEvent> depEvents = auditEventRepository.findAll().stream()
                .filter(e -> e.getEventType() == AuditEventType.DEPOSIT_COMPLETED)
                .toList();
        assertThat(depEvents).hasSize(1);
        assertThat(depEvents.get(0).getEntityId()).isEqualTo(depResult.response().transactionId());

        // Replay deposit
        var depReplay = depositService.processDeposit(depositKey, depositRequest);
        assertThat(depReplay.replayed()).isTrue();
        assertThat(auditEventRepository.findAll().stream()
                .filter(e -> e.getEventType() == AuditEventType.DEPOSIT_COMPLETED)
                .count()).isEqualTo(1);

        // B. Withdrawal
        String withdrawalKey = "with-audit-test-" + UUID.randomUUID();
        WithdrawalRequestDto withRequest = new WithdrawalRequestDto(
                aliceAccount.getId(),
                new BigDecimal("300.0000"),
                "INR",
                "ATM cash withdrawal"
        );
        var withResult = withdrawalService.processWithdrawal(withdrawalKey, withRequest);
        assertThat(withResult.replayed()).isFalse();

        List<AuditEvent> withEvents = auditEventRepository.findAll().stream()
                .filter(e -> e.getEventType() == AuditEventType.WITHDRAWAL_COMPLETED)
                .toList();
        assertThat(withEvents).hasSize(1);
        assertThat(withEvents.get(0).getEntityId()).isEqualTo(withResult.response().transactionId());

        // Replay withdrawal
        var withReplay = withdrawalService.processWithdrawal(withdrawalKey, withRequest);
        assertThat(withReplay.replayed()).isTrue();
        assertThat(auditEventRepository.findAll().stream()
                .filter(e -> e.getEventType() == AuditEventType.WITHDRAWAL_COMPLETED)
                .count()).isEqualTo(1);
    }

    @Test
    @DisplayName("5. Authorization: User A cannot see User B's audit events; Admin can view all")
    void shouldEnforceAuthorizationBoundariesOnApi() throws Exception {
        // Record event for Alice
        auditEventRepository.save(new AuditEvent(
                alice.getId(),
                AuditEventType.ACCOUNT_CREATED,
                AuditEntityType.ACCOUNT,
                aliceAccount.getId(),
                java.util.Map.of("accountType", "USER_CHECKING"),
                "127.0.0.1",
                "Client-A"
        ));

        // Record event for Bob
        auditEventRepository.save(new AuditEvent(
                bob.getId(),
                AuditEventType.ACCOUNT_CREATED,
                AuditEntityType.ACCOUNT,
                bobAccount.getId(),
                java.util.Map.of("accountType", "USER_CHECKING"),
                "127.0.0.1",
                "Client-B"
        ));

        // 1. Unauthenticated request -> 401
        mockMvc.perform(get("/api/v1/audit-events"))
                .andExpect(status().isUnauthorized());

        // 2. Alice requests audit events -> only sees Alice's event
        mockMvc.perform(get("/api/v1/audit-events")
                        .with(user("alice.audit@example.com").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].entityId", is(aliceAccount.getId().toString())));

        // 3. Bob requests audit events -> only sees Bob's event
        mockMvc.perform(get("/api/v1/audit-events")
                        .with(user("bob.audit@example.com").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].entityId", is(bobAccount.getId().toString())));

        // 4. Admin requests audit events -> sees both events
        mockMvc.perform(get("/api/v1/audit-events")
                        .with(user("admin.audit@ledger.com").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)));
    }

    @Test
    @DisplayName("6. Pagination and Query Parameter Validation")
    void shouldValidatePaginationAndFilters() throws Exception {
        // Invalid negative page
        mockMvc.perform(get("/api/v1/audit-events?page=-1")
                        .with(user("alice.audit@example.com").roles("USER")))
                .andExpect(status().isBadRequest());

        // Invalid page size > 100
        mockMvc.perform(get("/api/v1/audit-events?size=101")
                        .with(user("alice.audit@example.com").roles("USER")))
                .andExpect(status().isBadRequest());

        // Invalid event type
        mockMvc.perform(get("/api/v1/audit-events?eventType=NON_EXISTENT")
                        .with(user("alice.audit@example.com").roles("USER")))
                .andExpect(status().isBadRequest());

        // Invalid timestamp range: from > to
        mockMvc.perform(get("/api/v1/audit-events?from=2026-09-30T00:00:00Z&to=2026-09-20T00:00:00Z")
                        .with(user("alice.audit@example.com").roles("USER")))
                .andExpect(status().isBadRequest());
    }
}
