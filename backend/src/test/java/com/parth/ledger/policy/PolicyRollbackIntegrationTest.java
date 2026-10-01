package com.parth.ledger.policy;

import com.parth.ledger.BaseIntegrationTest;
import com.parth.ledger.account.Account;
import com.parth.ledger.account.AccountRepository;
import com.parth.ledger.ledger.LedgerEntryRepository;
import com.parth.ledger.transaction.Transaction;
import com.parth.ledger.transaction.TransactionRepository;
import com.parth.ledger.transaction.TransactionType;
import com.parth.ledger.transaction.dto.TransferRequestDto;
import com.parth.ledger.transaction.service.TransferService;
import com.parth.ledger.user.User;
import com.parth.ledger.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

class PolicyRollbackIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TransferService transferService;

    @Autowired
    private FinancialPolicyRepository financialPolicyRepository;

    @Autowired
    private DailyPolicyUsageRepository dailyPolicyUsageRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private UserRepository userRepository;

    @MockitoSpyBean
    private TransactionRepository spyTransactionRepository;

    private User aliceUser;
    private User bobUser;
    private Account aliceAccount;
    private Account bobAccount;

    @BeforeEach
    void setUp() {
        clearRedis();
        if (jdbcTemplate != null) {
            jdbcTemplate.execute("TRUNCATE TABLE audit_events CASCADE");
            jdbcTemplate.execute("TRUNCATE TABLE ledger_entries CASCADE");
            jdbcTemplate.execute("TRUNCATE TABLE transactions CASCADE");
            jdbcTemplate.execute("TRUNCATE TABLE policy_usage_daily CASCADE");
            jdbcTemplate.execute("TRUNCATE TABLE financial_policies CASCADE");
            jdbcTemplate.execute("TRUNCATE TABLE accounts CASCADE");
            jdbcTemplate.execute("TRUNCATE TABLE users CASCADE");
        }

        reset(spyTransactionRepository);

        aliceUser = userRepository.save(new User("alice.rollback@ledger.com", "Alice Rollback"));
        bobUser = userRepository.save(new User("bob.rollback@ledger.com", "Bob Rollback"));

        aliceAccount = accountRepository.save(new Account(aliceUser, "INR", new BigDecimal("10000.0000")));
        bobAccount = accountRepository.save(new Account(bobUser, "INR", new BigDecimal("1000.0000")));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice.rollback@ledger.com", null, Collections.emptyList())
        );
    }

    @Test
    @DisplayName("28. Forced failure after policy reservation rolls back policy usage, balance, and transaction")
    void verifyRollbackPreservesPolicyUsageAndFinancialInvariants() {
        // Daily transfer limit = 15,000
        financialPolicyRepository.save(new FinancialPolicy(
                aliceAccount.getId(),
                PolicyScope.ACCOUNT,
                TransactionType.TRANSFER,
                PolicyType.DAILY_TRANSACTION_AMOUNT,
                new BigDecimal("15000.0000"),
                null,
                "INR",
                true
        ));

        // Initial state
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Optional<DailyPolicyUsage> initialUsage = dailyPolicyUsageRepository.findByAccountIdAndTransactionTypeAndUsageDate(
                aliceAccount.getId(),
                TransactionType.TRANSFER,
                today
        );
        assertThat(initialUsage).isEmpty();

        // Configure spy: throw exception during transaction save (which occurs AFTER policy evaluation & usage update)
        doThrow(new DataAccessResourceFailureException("Simulated database failure during transaction persist"))
                .when(spyTransactionRepository).save(any(Transaction.class));

        // Execute transfer: must fail due to simulated database error
        assertThatThrownBy(() -> transferService.executeTransfer("tx-rollback-01", new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("3000.0000"),
                "INR"
        ))).isInstanceOf(DataAccessResourceFailureException.class);

        // Verify Authoritative Post-Rollback State:
        // 1. Account balances remain completely UNCHANGED
        Account refreshedAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        Account refreshedBob = accountRepository.findById(bobAccount.getId()).orElseThrow();
        assertThat(refreshedAlice.getBalance()).isEqualByComparingTo("10000.0000");
        assertThat(refreshedBob.getBalance()).isEqualByComparingTo("1000.0000");

        // 2. Policy usage row was rolled back (either absent or 0 amount used / 0 count)
        Optional<DailyPolicyUsage> rolledBackUsage = dailyPolicyUsageRepository.findByAccountIdAndTransactionTypeAndUsageDate(
                aliceAccount.getId(),
                TransactionType.TRANSFER,
                today
        );
        if (rolledBackUsage.isPresent()) {
            assertThat(rolledBackUsage.get().getAmountUsed()).isEqualByComparingTo("0.0000");
            assertThat(rolledBackUsage.get().getTransactionCount()).isEqualTo(0);
        }

        // 3. Reset spy to verify that subsequent real operations still succeed and policy quota was NOT consumed
        reset(spyTransactionRepository);

        TransferRequestDto validRequest = new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("3000.0000"),
                "INR"
        );
        assertThat(transferService.executeTransfer("tx-after-rollback-success", validRequest).status())
                .isEqualTo(com.parth.ledger.transaction.TransactionStatus.COMPLETED);

        // Final usage is exactly 3000 (1 transaction count), NOT 6000 (2 counts)
        DailyPolicyUsage finalUsage = dailyPolicyUsageRepository.findByAccountIdAndTransactionTypeAndUsageDate(
                aliceAccount.getId(),
                TransactionType.TRANSFER,
                today
        ).orElseThrow();
        assertThat(finalUsage.getAmountUsed()).isEqualByComparingTo("3000.0000");
        assertThat(finalUsage.getTransactionCount()).isEqualTo(1);
    }
}
