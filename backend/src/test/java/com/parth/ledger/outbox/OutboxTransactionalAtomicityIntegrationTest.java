package com.parth.ledger.outbox;

import com.parth.ledger.BaseIntegrationTest;
import com.parth.ledger.account.Account;
import com.parth.ledger.account.AccountRepository;
import com.parth.ledger.account.AccountService;
import com.parth.ledger.account.AccountStatus;
import com.parth.ledger.account.AccountStatusException;
import com.parth.ledger.account.AccountType;
import com.parth.ledger.account.dto.AccountResponseDto;
import com.parth.ledger.account.dto.CreateAccountRequestDto;
import com.parth.ledger.deposit.dto.DepositRequestDto;
import com.parth.ledger.deposit.service.DepositService;
import com.parth.ledger.policy.PolicyViolationException;
import com.parth.ledger.policy.dto.CreatePolicyRequestDto;
import com.parth.ledger.policy.PolicyScope;
import com.parth.ledger.policy.PolicyService;
import com.parth.ledger.policy.PolicyType;
import com.parth.ledger.system.SystemFundingService;
import com.parth.ledger.transaction.Transaction;
import com.parth.ledger.transaction.TransactionRepository;
import com.parth.ledger.transaction.dto.ReversalRequestDto;
import com.parth.ledger.transaction.dto.TransferRequestDto;
import com.parth.ledger.transaction.dto.TransferResponseDto;
import com.parth.ledger.transaction.exception.InsufficientBalanceException;
import com.parth.ledger.transaction.service.TransactionReversalService;
import com.parth.ledger.transaction.service.TransferService;
import com.parth.ledger.user.User;
import com.parth.ledger.user.UserRepository;
import com.parth.ledger.withdrawal.dto.WithdrawalRequestDto;
import com.parth.ledger.withdrawal.service.WithdrawalService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxTransactionalAtomicityIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TransferService transferService;

    @Autowired
    private DepositService depositService;

    @Autowired
    private WithdrawalService withdrawalService;

    @Autowired
    private TransactionReversalService reversalService;

    @Autowired
    private AccountService accountService;

    @Autowired
    private PolicyService policyService;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private SystemFundingService systemFundingService;

    private User alice;
    private User bob;
    private User admin;
    private Account aliceAccount;
    private Account bobAccount;

    @BeforeEach
    void setUp() {
        systemFundingService.ensureBootstrapFunding();

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        alice = userRepository.save(new User("alice." + suffix + "@ledger.com", "Alice"));
        bob = userRepository.save(new User("bob." + suffix + "@ledger.com", "Bob"));
        admin = userRepository.save(new User("admin." + suffix + "@ledger.com", "Admin"));

        aliceAccount = accountRepository.save(new Account(alice, "INR", new BigDecimal("1000.0000"), AccountType.USER_CHECKING, AccountStatus.ACTIVE, "ACCT-A-" + suffix));
        bobAccount = accountRepository.save(new Account(bob, "INR", new BigDecimal("500.0000"), AccountType.USER_CHECKING, AccountStatus.ACTIVE, "ACCT-B-" + suffix));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        if (jdbcTemplate != null) {
            try {
                jdbcTemplate.execute("TRUNCATE TABLE outbox_events CASCADE");
                jdbcTemplate.execute("TRUNCATE TABLE ledger_entries CASCADE");
                jdbcTemplate.execute("TRUNCATE TABLE transactions CASCADE");
                jdbcTemplate.execute("TRUNCATE TABLE accounts CASCADE");
                jdbcTemplate.execute("TRUNCATE TABLE users CASCADE");
            } catch (Exception ignored) {
            }
        }
    }

    private <T> T executeAsUser(String email, Supplier<T> action) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(email, null, Collections.emptyList())
        );
        try {
            return action.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private <T> T executeAsAdmin(String email, Supplier<T> action) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(email, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))
        );
        try {
            return action.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    // =========================================================================
    // 1. SUCCESSFUL MUTATIONS CREATE OUTBOX EVENTS
    // =========================================================================

    @Test
    @DisplayName("Successful transfer creates TRANSFER_COMPLETED outbox event with expected payload")
    void successfulTransferCreatesOutboxEvent() {
        String key = "TX-KEY-" + UUID.randomUUID();
        TransferRequestDto request = new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("200.0000"),
                "INR",
                "Atomicity test transfer"
        );

        TransferResponseDto response = executeAsUser(alice.getEmail(), () -> transferService.executeTransfer(key, request));

        List<OutboxEvent> events = outboxEventRepository.findByAggregateId(response.transactionId());
        assertThat(events).hasSize(1);

        OutboxEvent event = events.get(0);
        assertThat(event.getAggregateType()).isEqualTo(OutboxAggregateType.TRANSACTION);
        assertThat(event.getAggregateId()).isEqualTo(response.transactionId());
        assertThat(event.getEventType()).isEqualTo(OutboxEventType.TRANSFER_COMPLETED);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getAttemptCount()).isZero();
        assertThat(event.getAvailableAt()).isNotNull();

        Map<String, Object> payload = event.getPayload();
        assertThat(payload.get("transactionId")).isEqualTo(response.transactionId().toString());
        assertThat(payload.get("sourceAccountId")).isEqualTo(aliceAccount.getId().toString());
        assertThat(payload.get("destinationAccountId")).isEqualTo(bobAccount.getId().toString());
        assertThat(new BigDecimal(payload.get("amount").toString())).isEqualByComparingTo(new BigDecimal("200.0000"));
        assertThat(payload.get("currency")).isEqualTo("INR");
        assertThat(payload.get("occurredAt")).isNotNull();
    }

    @Test
    @DisplayName("Successful deposit creates DEPOSIT_COMPLETED outbox event with expected payload")
    void successfulDepositCreatesOutboxEvent() {
        String key = "DEP-KEY-" + UUID.randomUUID();
        DepositRequestDto request = new DepositRequestDto(
                aliceAccount.getId(),
                new BigDecimal("300.0000"),
                "INR",
                "Atomicity test deposit"
        );

        var depositResult = executeAsUser(alice.getEmail(), () -> depositService.executeDeposit(key, request));
        UUID txId = depositResult.transactionId();

        List<OutboxEvent> events = outboxEventRepository.findByAggregateId(txId);
        assertThat(events).hasSize(1);

        OutboxEvent event = events.get(0);
        assertThat(event.getAggregateType()).isEqualTo(OutboxAggregateType.TRANSACTION);
        assertThat(event.getAggregateId()).isEqualTo(txId);
        assertThat(event.getEventType()).isEqualTo(OutboxEventType.DEPOSIT_COMPLETED);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);

        Map<String, Object> payload = event.getPayload();
        assertThat(payload.get("transactionId")).isEqualTo(txId.toString());
        assertThat(payload.get("destinationAccountId")).isEqualTo(aliceAccount.getId().toString());
        assertThat(new BigDecimal(payload.get("amount").toString())).isEqualByComparingTo(new BigDecimal("300.0000"));
        assertThat(payload.get("currency")).isEqualTo("INR");
    }

    @Test
    @DisplayName("Successful withdrawal creates WITHDRAWAL_COMPLETED outbox event with expected payload")
    void successfulWithdrawalCreatesOutboxEvent() {
        String key = "WTH-KEY-" + UUID.randomUUID();
        WithdrawalRequestDto request = new WithdrawalRequestDto(
                aliceAccount.getId(),
                new BigDecimal("150.0000"),
                "INR",
                "Atomicity test withdrawal"
        );

        var withdrawalResult = executeAsUser(alice.getEmail(), () -> withdrawalService.executeWithdrawal(key, request));
        UUID txId = withdrawalResult.transactionId();

        List<OutboxEvent> events = outboxEventRepository.findByAggregateId(txId);
        assertThat(events).hasSize(1);

        OutboxEvent event = events.get(0);
        assertThat(event.getAggregateType()).isEqualTo(OutboxAggregateType.TRANSACTION);
        assertThat(event.getAggregateId()).isEqualTo(txId);
        assertThat(event.getEventType()).isEqualTo(OutboxEventType.WITHDRAWAL_COMPLETED);

        Map<String, Object> payload = event.getPayload();
        assertThat(payload.get("transactionId")).isEqualTo(txId.toString());
        assertThat(payload.get("sourceAccountId")).isEqualTo(aliceAccount.getId().toString());
        assertThat(new BigDecimal(payload.get("amount").toString())).isEqualByComparingTo(new BigDecimal("150.0000"));
        assertThat(payload.get("currency")).isEqualTo("INR");
    }

    @Test
    @DisplayName("Successful reversal creates TRANSACTION_REVERSED outbox event referencing original and reversal transaction")
    void successfulReversalCreatesOutboxEvent() {
        // Step 1: execute a transfer
        String transferKey = "TX-KEY-" + UUID.randomUUID();
        TransferRequestDto transferReq = new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("100.0000"),
                "INR",
                "Transfer to reverse"
        );
        TransferResponseDto transferResp = executeAsUser(alice.getEmail(), () -> transferService.executeTransfer(transferKey, transferReq));
        UUID originalTxId = transferResp.transactionId();

        // Step 2: execute reversal (by admin or destination account owner)
        String reversalKey = "REV-KEY-" + UUID.randomUUID();
        ReversalRequestDto reversalReq = new ReversalRequestDto("Wrong recipient");
        var reversalResp = executeAsAdmin(admin.getEmail(), () -> reversalService.executeReversal(originalTxId, reversalKey, reversalReq));

        UUID reversalTxId = reversalResp.reversalTransactionId();

        // The outbox event must correspond to the reversal transaction
        List<OutboxEvent> reversalEvents = outboxEventRepository.findByAggregateId(reversalTxId);
        assertThat(reversalEvents).hasSize(1);

        OutboxEvent event = reversalEvents.get(0);
        assertThat(event.getAggregateType()).isEqualTo(OutboxAggregateType.TRANSACTION);
        assertThat(event.getAggregateId()).isEqualTo(reversalTxId);
        assertThat(event.getEventType()).isEqualTo(OutboxEventType.TRANSACTION_REVERSED);

        Map<String, Object> payload = event.getPayload();
        assertThat(payload.get("transactionId")).isEqualTo(reversalTxId.toString());
        assertThat(payload.get("reversesTransactionId")).isEqualTo(originalTxId.toString());
        assertThat(new BigDecimal(payload.get("amount").toString())).isEqualByComparingTo(new BigDecimal("100.0000"));
        assertThat(payload.get("currency")).isEqualTo("INR");
    }

    @Test
    @DisplayName("Account lifecycle operations create outbox events")
    void accountLifecycleCreatesOutboxEvents() {
        // 1. Account creation
        AccountResponseDto created = executeAsUser(alice.getEmail(), () ->
                accountService.createAccount(new CreateAccountRequestDto("INR"))
        );
        UUID accountId = created.accountId();

        List<OutboxEvent> createEvents = outboxEventRepository.findByAggregateId(accountId);
        assertThat(createEvents).hasSize(1);
        assertThat(createEvents.get(0).getEventType()).isEqualTo(OutboxEventType.ACCOUNT_CREATED);

        // 2. Account freeze
        executeAsAdmin("admin@ledger.com", () -> accountService.freezeAccount(accountId));

        List<OutboxEvent> freezeEvents = outboxEventRepository.findByAggregateId(accountId);
        assertThat(freezeEvents).hasSize(2);
        assertThat(freezeEvents).extracting(OutboxEvent::getEventType)
                .containsExactlyInAnyOrder(OutboxEventType.ACCOUNT_CREATED, OutboxEventType.ACCOUNT_FROZEN);

        // 3. Account unfreeze
        executeAsAdmin("admin@ledger.com", () -> accountService.unfreezeAccount(accountId));

        List<OutboxEvent> unfreezeEvents = outboxEventRepository.findByAggregateId(accountId);
        assertThat(unfreezeEvents).hasSize(3);
        assertThat(unfreezeEvents).extracting(OutboxEvent::getEventType)
                .contains(OutboxEventType.ACCOUNT_UNFROZEN);

        // 4. Account close (balance is 0)
        executeAsUser(alice.getEmail(), () -> accountService.closeAccount(accountId));

        List<OutboxEvent> closeEvents = outboxEventRepository.findByAggregateId(accountId);
        assertThat(closeEvents).hasSize(4);
        assertThat(closeEvents).extracting(OutboxEvent::getEventType)
                .contains(OutboxEventType.ACCOUNT_CLOSED);
    }

    // =========================================================================
    // 2. FINANCIAL ROLLBACK ENSURES NO OUTBOX EVENT IS PERSISTED
    // =========================================================================

    @Test
    @DisplayName("Transfer rollback due to InsufficientBalance leaves NO outbox event")
    void transferRollbackLeavesNoOutboxEvent() {
        String key = "TX-FAIL-" + UUID.randomUUID();
        TransferRequestDto request = new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("999999.0000"), // Exceeds balance
                "INR",
                "Overdraft transfer"
        );

        assertThatThrownBy(() -> executeAsUser(alice.getEmail(), () -> transferService.executeTransfer(key, request)))
                .isInstanceOf(InsufficientBalanceException.class);

        assertThat(outboxEventRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("Transfer rollback due to PolicyViolation leaves NO outbox event")
    void transferPolicyViolationRollbackLeavesNoOutboxEvent() {
        // Create policy with max amount 50 INR
        executeAsAdmin("admin@ledger.com", () ->
                policyService.createPolicy(new CreatePolicyRequestDto(
                        null,
                        PolicyScope.GLOBAL,
                        com.parth.ledger.transaction.TransactionType.TRANSFER,
                        PolicyType.MAX_TRANSACTION_AMOUNT,
                        new BigDecimal("50.0000"),
                        null,
                        "INR",
                        true
                ))
        );

        String key = "TX-POLICY-FAIL-" + UUID.randomUUID();
        TransferRequestDto request = new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("200.0000"), // Exceeds policy limit
                "INR",
                "Policy breach transfer"
        );

        assertThatThrownBy(() -> executeAsUser(alice.getEmail(), () -> transferService.executeTransfer(key, request)))
                .isInstanceOf(PolicyViolationException.class);

        // Verify outbox has NO events
        assertThat(outboxEventRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("Withdrawal rollback due to InsufficientBalance leaves NO outbox event")
    void withdrawalRollbackLeavesNoOutboxEvent() {
        String key = "WTH-FAIL-" + UUID.randomUUID();
        WithdrawalRequestDto request = new WithdrawalRequestDto(
                aliceAccount.getId(),
                new BigDecimal("99999.0000"),
                "INR",
                "Overdraft withdrawal"
        );

        assertThatThrownBy(() -> executeAsUser(alice.getEmail(), () -> withdrawalService.executeWithdrawal(key, request)))
                .isInstanceOf(InsufficientBalanceException.class);

        assertThat(outboxEventRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("Account close rollback due to non-zero balance leaves NO outbox event")
    void accountCloseRollbackLeavesNoOutboxEvent() {
        // aliceAccount has 1000.0000 balance
        assertThatThrownBy(() -> executeAsUser(alice.getEmail(), () -> accountService.closeAccount(aliceAccount.getId())))
                .isInstanceOf(AccountStatusException.class);

        List<OutboxEvent> events = outboxEventRepository.findByAggregateId(aliceAccount.getId());
        assertThat(events).isEmpty();
    }
}
