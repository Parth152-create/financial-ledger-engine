package com.parth.ledger.outbox;

import com.parth.ledger.BaseIntegrationTest;
import com.parth.ledger.account.Account;
import com.parth.ledger.account.AccountRepository;
import com.parth.ledger.account.AccountStatus;
import com.parth.ledger.account.AccountType;
import com.parth.ledger.deposit.dto.DepositRequestDto;
import com.parth.ledger.deposit.service.DepositService;
import com.parth.ledger.system.SystemFundingService;
import com.parth.ledger.transaction.dto.ReversalRequestDto;
import com.parth.ledger.transaction.dto.TransactionResponseDto;
import com.parth.ledger.transaction.dto.TransferRequestDto;
import com.parth.ledger.transaction.dto.TransferResponseDto;
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
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxIdempotencyIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TransferService transferService;

    @Autowired
    private DepositService depositService;

    @Autowired
    private WithdrawalService withdrawalService;

    @Autowired
    private TransactionReversalService reversalService;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

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

    @Test
    @DisplayName("Transfer idempotent retry returns existing transaction and does NOT create another outbox event")
    void transferIdempotentReplayDoesNotDuplicateOutboxEvent() {
        String key = "TX-IDEMP-" + UUID.randomUUID();
        TransferRequestDto request = new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("100.0000"),
                "INR",
                "Idempotency transfer"
        );

        // First attempt: creates transaction and outbox event
        TransferResponseDto firstResp = executeAsUser(alice.getEmail(), () -> transferService.executeTransfer(key, request));
        assertThat(outboxEventRepository.findByAggregateId(firstResp.transactionId())).hasSize(1);

        // Second attempt with exact same key and parameters (from Redis cache or DB replay)
        TransferResponseDto secondResp = executeAsUser(alice.getEmail(), () -> transferService.executeTransfer(key, request));
        assertThat(secondResp.transactionId()).isEqualTo(firstResp.transactionId());

        // Verify outbox events count is STILL exactly 1
        assertThat(outboxEventRepository.findByAggregateId(firstResp.transactionId())).hasSize(1);
    }

    @Test
    @DisplayName("Deposit idempotent retry returns existing transaction and does NOT create another outbox event")
    void depositIdempotentReplayDoesNotDuplicateOutboxEvent() {
        String key = "DEP-IDEMP-" + UUID.randomUUID();
        DepositRequestDto request = new DepositRequestDto(
                aliceAccount.getId(),
                new BigDecimal("200.0000"),
                "INR",
                "Idempotency deposit"
        );

        // First execution
        TransactionResponseDto firstResult = executeAsUser(alice.getEmail(), () -> depositService.executeDeposit(key, request));
        UUID txId = firstResult.transactionId();
        assertThat(outboxEventRepository.findByAggregateId(txId)).hasSize(1);

        // Second execution (replay)
        TransactionResponseDto secondResult = executeAsUser(alice.getEmail(), () -> depositService.executeDeposit(key, request));
        assertThat(secondResult.transactionId()).isEqualTo(txId);

        // Verify outbox count remains 1
        assertThat(outboxEventRepository.findByAggregateId(txId)).hasSize(1);
    }

    @Test
    @DisplayName("Withdrawal idempotent retry returns existing transaction and does NOT create another outbox event")
    void withdrawalIdempotentReplayDoesNotDuplicateOutboxEvent() {
        String key = "WTH-IDEMP-" + UUID.randomUUID();
        WithdrawalRequestDto request = new WithdrawalRequestDto(
                aliceAccount.getId(),
                new BigDecimal("50.0000"),
                "INR",
                "Idempotency withdrawal"
        );

        // First execution
        TransactionResponseDto firstResult = executeAsUser(alice.getEmail(), () -> withdrawalService.executeWithdrawal(key, request));
        UUID txId = firstResult.transactionId();
        assertThat(outboxEventRepository.findByAggregateId(txId)).hasSize(1);

        // Replay
        TransactionResponseDto secondResult = executeAsUser(alice.getEmail(), () -> withdrawalService.executeWithdrawal(key, request));
        assertThat(secondResult.transactionId()).isEqualTo(txId);

        // Verify outbox count remains 1
        assertThat(outboxEventRepository.findByAggregateId(txId)).hasSize(1);
    }

    @Test
    @DisplayName("Reversal idempotent retry returns existing reversal and does NOT create another outbox event")
    void reversalIdempotentReplayDoesNotDuplicateOutboxEvent() {
        // Step 1: execute transfer
        String transferKey = "TX-REV-SRC-" + UUID.randomUUID();
        TransferRequestDto transferReq = new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("75.0000"),
                "INR",
                "Transfer for reversal idempotency"
        );
        TransferResponseDto transferResp = executeAsUser(alice.getEmail(), () -> transferService.executeTransfer(transferKey, transferReq));
        UUID originalTxId = transferResp.transactionId();

        // Step 2: execute reversal
        String reversalKey = "REV-IDEMP-" + UUID.randomUUID();
        ReversalRequestDto reversalReq = new ReversalRequestDto("Idempotent reversal test");
        var firstReversal = executeAsAdmin(admin.getEmail(), () -> reversalService.executeReversal(originalTxId, reversalKey, reversalReq));
        UUID reversalTxId = firstReversal.reversalTransactionId();
        assertThat(outboxEventRepository.findByAggregateId(reversalTxId)).hasSize(1);

        // Step 3: replay reversal
        var secondReversal = executeAsAdmin(admin.getEmail(), () -> reversalService.executeReversal(originalTxId, reversalKey, reversalReq));
        assertThat(secondReversal.reversalTransactionId()).isEqualTo(reversalTxId);

        // Verify outbox count remains 1
        assertThat(outboxEventRepository.findByAggregateId(reversalTxId)).hasSize(1);
    }
}
