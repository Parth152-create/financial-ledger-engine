package com.parth.ledger.recurring;

import com.parth.ledger.BaseIntegrationTest;
import com.parth.ledger.account.Account;
import com.parth.ledger.account.AccountRepository;
import com.parth.ledger.deposit.dto.DepositRequestDto;
import com.parth.ledger.deposit.service.DepositService;
import com.parth.ledger.reconciliation.ReconciliationStatus;
import com.parth.ledger.reconciliation.dto.OverallReconciliationDto;
import com.parth.ledger.reconciliation.dto.ReconciliationResultDto;
import com.parth.ledger.reconciliation.service.ReconciliationService;
import com.parth.ledger.recurring.dto.CreateRecurringTransferRequestDto;
import com.parth.ledger.recurring.dto.RecurringTransferResponseDto;
import com.parth.ledger.system.SystemFundingService;
import com.parth.ledger.user.User;
import com.parth.ledger.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test proving that recurring transfers (both successful runs and failed executions)
 * preserve strict double-entry ledger consistency and pass reconciliation with 0 discrepancies.
 */
class RecurringTransferReconciliationIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private RecurringTransferService recurringTransferService;

    @Autowired
    private RecurringTransferProcessor recurringTransferProcessor;

    @Autowired
    private RecurringTransferRepository recurringTransferRepository;

    @Autowired
    private RecurringTransferExecutionRepository recurringExecutionRepository;

    @Autowired
    private ReconciliationService reconciliationService;

    @Autowired
    private DepositService depositService;

    @Autowired
    private SystemFundingService systemFundingService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private UserRepository userRepository;

    private User alice;
    private User bob;
    private User charlie;
    private Account aliceAccount;
    private Account bobAccount;
    private Account charlieAccount;

    @BeforeEach
    void setUp() {
        clearRedis();
        if (jdbcTemplate != null) {
            jdbcTemplate.execute("TRUNCATE TABLE recurring_transfer_executions CASCADE");
            jdbcTemplate.execute("TRUNCATE TABLE recurring_transfers CASCADE");
            jdbcTemplate.execute("TRUNCATE TABLE outbox_events CASCADE");
            jdbcTemplate.execute("TRUNCATE TABLE audit_events CASCADE");
            jdbcTemplate.execute("TRUNCATE TABLE ledger_entries CASCADE");
            jdbcTemplate.execute("TRUNCATE TABLE transactions CASCADE");
            jdbcTemplate.execute("TRUNCATE TABLE policy_usage_daily CASCADE");
            jdbcTemplate.execute("TRUNCATE TABLE accounts CASCADE");
            jdbcTemplate.execute("TRUNCATE TABLE users CASCADE");
        }

        systemFundingService.bootstrapSystemFunding(new BigDecimal("10000000.0000"));

        alice = userRepository.save(new User("alice.recon.rec@ledger.com", "Alice Recon"));
        bob = userRepository.save(new User("bob.recon.rec@ledger.com", "Bob Recon"));
        charlie = userRepository.save(new User("charlie.recon.rec@ledger.com", "Charlie Recon"));

        aliceAccount = accountRepository.save(new Account(alice, "INR", BigDecimal.ZERO.setScale(4)));
        bobAccount = accountRepository.save(new Account(bob, "INR", BigDecimal.ZERO.setScale(4)));
        charlieAccount = accountRepository.save(new Account(charlie, "INR", BigDecimal.ZERO.setScale(4)));

        // Fund Alice with 2000.0000 INR and Bob with 500.0000 INR
        authenticateAs(alice);
        depositService.executeDeposit("init-alice-rec-fund", new DepositRequestDto(
                aliceAccount.getId(),
                new BigDecimal("2000.0000"),
                "INR"
        ));

        authenticateAs(bob);
        depositService.executeDeposit("init-bob-rec-fund", new DepositRequestDto(
                bobAccount.getId(),
                new BigDecimal("500.0000"),
                "INR"
        ));
    }

    private void authenticateAs(User user) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user.getEmail(), null, Collections.emptyList())
        );
    }

    @Test
    @DisplayName("Multiple recurring transfer occurrences preserve 100% reconciliation and zero discrepancy")
    void testRecurringTransferReconciliation_MultipleExecutions() {
        authenticateAs(alice);
        LocalDate today = LocalDate.now();
        LocalDate pastDate = today.minusDays(5);

        // Schedule 1: Alice -> Bob, 300.0000 INR DAILY
        RecurringTransferResponseDto sched1 = recurringTransferService.createRecurringTransfer(
                new CreateRecurringTransferRequestDto(
                        aliceAccount.getId(),
                        bobAccount.getId(),
                        new BigDecimal("300.0000"),
                        "INR",
                        RecurringFrequency.DAILY,
                        pastDate,
                        null
                )
        );

        // Execution 1:
        recurringTransferProcessor.processEligibleSchedules();

        // Check Alice balance: 2000 - 300 = 1700, Bob balance: 500 + 300 = 800
        ReconciliationResultDto aliceRecon1 = reconciliationService.reconcileAccountDirectly(aliceAccount.getId());
        ReconciliationResultDto bobRecon1 = reconciliationService.reconcileAccountDirectly(bobAccount.getId());

        assertThat(aliceRecon1.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
        assertThat(aliceRecon1.difference()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(aliceRecon1.snapshotBalance()).isEqualByComparingTo(new BigDecimal("1700.0000"));

        assertThat(bobRecon1.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
        assertThat(bobRecon1.difference()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(bobRecon1.snapshotBalance()).isEqualByComparingTo(new BigDecimal("800.0000"));

        // Force schedule to be eligible again for a second execution
        RecurringTransfer schedEntity = recurringTransferRepository.findById(sched1.id()).orElseThrow();
        schedEntity.setNextExecutionAt(Instant.now().minusSeconds(10));
        recurringTransferRepository.saveAndFlush(schedEntity);

        // Execution 2:
        recurringTransferProcessor.processEligibleSchedules();

        // Schedule 2: Bob -> Charlie, 400.0000 INR WEEKLY
        authenticateAs(bob);
        RecurringTransferResponseDto sched2 = recurringTransferService.createRecurringTransfer(
                new CreateRecurringTransferRequestDto(
                        bobAccount.getId(),
                        charlieAccount.getId(),
                        new BigDecimal("400.0000"),
                        "INR",
                        RecurringFrequency.WEEKLY,
                        today,
                        null
                )
        );

        // Force Bob schedule to be immediately eligible
        RecurringTransfer schedEntity2 = recurringTransferRepository.findById(sched2.id()).orElseThrow();
        schedEntity2.setNextExecutionAt(Instant.now().minusSeconds(10));
        recurringTransferRepository.saveAndFlush(schedEntity2);

        // Execution 3:
        recurringTransferProcessor.processEligibleSchedules();

        // Verify individual accounts directly
        ReconciliationResultDto aliceFinal = reconciliationService.reconcileAccountDirectly(aliceAccount.getId());
        ReconciliationResultDto bobFinal = reconciliationService.reconcileAccountDirectly(bobAccount.getId());
        ReconciliationResultDto charlieFinal = reconciliationService.reconcileAccountDirectly(charlieAccount.getId());

        // Alice: 2000 - 300 - 300 = 1400.0000
        assertThat(aliceFinal.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
        assertThat(aliceFinal.difference()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(aliceFinal.snapshotBalance()).isEqualByComparingTo(new BigDecimal("1400.0000"));
        assertThat(aliceFinal.snapshotBalance()).isEqualByComparingTo(aliceFinal.ledgerBalance());

        // Bob: 500 + 300 + 300 - 400 = 700.0000
        assertThat(bobFinal.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
        assertThat(bobFinal.difference()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(bobFinal.snapshotBalance()).isEqualByComparingTo(new BigDecimal("700.0000"));
        assertThat(bobFinal.snapshotBalance()).isEqualByComparingTo(bobFinal.ledgerBalance());

        // Charlie: 0 + 400 = 400.0000
        assertThat(charlieFinal.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
        assertThat(charlieFinal.difference()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(charlieFinal.snapshotBalance()).isEqualByComparingTo(new BigDecimal("400.0000"));
        assertThat(charlieFinal.snapshotBalance()).isEqualByComparingTo(charlieFinal.ledgerBalance());

        // Also test user account reconciliation for Alice
        authenticateAs(alice);
        OverallReconciliationDto aliceOverall = reconciliationService.reconcileUserAccounts();
        assertThat(aliceOverall.discrepancyCount()).isEqualTo(0);
        assertThat(aliceOverall.consistentAccounts()).isEqualTo(1);
    }

    @Test
    @DisplayName("Failed recurring transfer execution does not corrupt ledger and reconciliation remains 100% consistent")
    void testRecurringTransferReconciliation_FailedExecutionPreservesReconciliation() {
        authenticateAs(charlie);
        LocalDate today = LocalDate.now();
        // Charlie has 0 balance, create recurring transfer Charlie -> Alice for 100.0000 INR
        RecurringTransferResponseDto sched = recurringTransferService.createRecurringTransfer(
                new CreateRecurringTransferRequestDto(
                        charlieAccount.getId(),
                        aliceAccount.getId(),
                        new BigDecimal("100.0000"),
                        "INR",
                        RecurringFrequency.DAILY,
                        today,
                        null
                )
        );

        // Force schedule to be immediately eligible
        RecurringTransfer schedEntity = recurringTransferRepository.findById(sched.id()).orElseThrow();
        schedEntity.setNextExecutionAt(Instant.now().minusSeconds(10));
        recurringTransferRepository.saveAndFlush(schedEntity);

        // Processor runs and fails due to InsufficientBalanceException
        recurringTransferProcessor.processEligibleSchedules();

        // Verify execution is recorded as FAILED
        List<RecurringTransferExecution> executions = recurringExecutionRepository.findByRecurringTransferId(sched.id());
        assertThat(executions).hasSize(1);
        assertThat(executions.get(0).getStatus()).isEqualTo(RecurringExecutionStatus.FAILED);
        assertThat(executions.get(0).getTransactionId()).isNull();

        // Reconciliation should be completely unaffected: 0 discrepancies across all accounts
        ReconciliationResultDto charlieRecon = reconciliationService.reconcileAccountDirectly(charlieAccount.getId());
        assertThat(charlieRecon.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
        assertThat(charlieRecon.difference()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(charlieRecon.snapshotBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(charlieRecon.ledgerBalance()).isEqualByComparingTo(BigDecimal.ZERO);

        ReconciliationResultDto aliceRecon = reconciliationService.reconcileAccountDirectly(aliceAccount.getId());
        assertThat(aliceRecon.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
        assertThat(aliceRecon.difference()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(aliceRecon.snapshotBalance()).isEqualByComparingTo(new BigDecimal("2000.0000"));
        assertThat(aliceRecon.ledgerBalance()).isEqualByComparingTo(new BigDecimal("2000.0000"));
    }
}
