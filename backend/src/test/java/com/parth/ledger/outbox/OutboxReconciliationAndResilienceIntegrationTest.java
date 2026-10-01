package com.parth.ledger.outbox;

import com.parth.ledger.BaseIntegrationTest;
import com.parth.ledger.account.Account;
import com.parth.ledger.account.AccountRepository;
import com.parth.ledger.account.AccountStatus;
import com.parth.ledger.account.AccountType;
import com.parth.ledger.deposit.dto.DepositRequestDto;
import com.parth.ledger.deposit.service.DepositService;
import com.parth.ledger.reconciliation.ReconciliationStatus;
import com.parth.ledger.reconciliation.dto.ReconciliationResultDto;
import com.parth.ledger.reconciliation.service.ReconciliationService;
import com.parth.ledger.system.SystemFundingService;
import com.parth.ledger.transaction.dto.TransferRequestDto;
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
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxReconciliationAndResilienceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TransferService transferService;

    @Autowired
    private DepositService depositService;

    @Autowired
    private WithdrawalService withdrawalService;

    @Autowired
    private ReconciliationService reconciliationService;

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
    private Account aliceAccount;
    private Account bobAccount;

    @BeforeEach
    void setUp() {
        systemFundingService.ensureBootstrapFunding();

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        alice = userRepository.save(new User("alice." + suffix + "@ledger.com", "Alice"));
        bob = userRepository.save(new User("bob." + suffix + "@ledger.com", "Bob"));

        aliceAccount = accountRepository.save(new Account(alice, "INR", BigDecimal.ZERO.setScale(4), AccountType.USER_CHECKING, AccountStatus.ACTIVE, "ACCT-A-" + suffix));
        bobAccount = accountRepository.save(new Account(bob, "INR", BigDecimal.ZERO.setScale(4), AccountType.USER_CHECKING, AccountStatus.ACTIVE, "ACCT-B-" + suffix));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(alice.getEmail(), null, Collections.emptyList())
        );
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

    @Test
    @DisplayName("Financial mutations with transactional outbox events maintain 100% reconciliation consistency")
    void outboxMutationsMaintainReconciliationConsistency() {
        // 1. Deposit 5000 into Alice
        depositService.executeDeposit("DEP-RECON-1", new DepositRequestDto(aliceAccount.getId(), new BigDecimal("5000.0000"), "INR", "Initial deposit"));

        // 2. Transfer 2000 from Alice to Bob
        transferService.executeTransfer("TX-RECON-1", new TransferRequestDto(aliceAccount.getId(), bobAccount.getId(), new BigDecimal("2000.0000"), "INR", "Recon transfer"));

        // 3. Alice withdraws 500
        withdrawalService.executeWithdrawal("WTH-RECON-1", new WithdrawalRequestDto(aliceAccount.getId(), new BigDecimal("500.0000"), "INR", "Recon withdrawal"));

        // Verify outbox events are present
        assertThat(outboxEventRepository.count()).isEqualTo(3);

        // Run reconciliation: account snapshot vs ledger entries
        ReconciliationResultDto aliceRecon = reconciliationService.reconcileAccount(aliceAccount.getId());
        assertThat(aliceRecon.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
        assertThat(aliceRecon.snapshotBalance()).isEqualByComparingTo(new BigDecimal("2500.0000"));
        assertThat(aliceRecon.ledgerBalance()).isEqualByComparingTo(new BigDecimal("2500.0000"));
        assertThat(aliceRecon.difference()).isEqualByComparingTo(BigDecimal.ZERO);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(bob.getEmail(), null, Collections.emptyList())
        );
        ReconciliationResultDto bobRecon = reconciliationService.reconcileAccount(bobAccount.getId());
        assertThat(bobRecon.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
        assertThat(bobRecon.snapshotBalance()).isEqualByComparingTo(new BigDecimal("2000.0000"));
        assertThat(bobRecon.ledgerBalance()).isEqualByComparingTo(new BigDecimal("2000.0000"));
        assertThat(bobRecon.difference()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
