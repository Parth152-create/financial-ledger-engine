package com.parth.ledger.policy;

import com.parth.ledger.BaseIntegrationTest;
import com.parth.ledger.account.Account;
import com.parth.ledger.account.AccountRepository;
import com.parth.ledger.ledger.LedgerEntryRepository;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PolicyPrecedenceAndScopeIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private PolicyService policyService;

    @Autowired
    private FinancialPolicyRepository financialPolicyRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private TransferService transferService;

    private User aliceUser;
    private User bobUser;
    private Account accountA;
    private Account accountB;

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

        aliceUser = userRepository.save(new User("alice.policy@ledger.com", "Alice"));
        bobUser = userRepository.save(new User("bob.policy@ledger.com", "Bob"));

        accountA = accountRepository.save(new Account(aliceUser, "INR", new BigDecimal("500000.0000")));
        accountB = accountRepository.save(new Account(bobUser, "INR", new BigDecimal("500000.0000")));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice.policy@ledger.com", null, Collections.emptyList())
        );
    }

    @Test
    @DisplayName("4. No policy means unlimited")
    void verifyNoPolicyMeansUnlimited() {
        Optional<FinancialPolicy> policy = policyService.resolveApplicablePolicy(
                accountA.getId(),
                PolicyType.MAX_TRANSACTION_AMOUNT,
                TransactionType.TRANSFER
        );
        assertThat(policy).isEmpty();

        // Transfer should succeed without limit check blocking
        assertThatCode(() -> transferService.executeTransfer("tx-unlimited-01", new TransferRequestDto(
                accountA.getId(),
                accountB.getId(),
                new BigDecimal("150000.0000"),
                "INR"
        ))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Global policy applies to all user accounts")
    void verifyGlobalPolicyAppliesToAllAccounts() {
        // Global transfer max = 100,000
        financialPolicyRepository.save(new FinancialPolicy(
                null,
                PolicyScope.GLOBAL,
                TransactionType.TRANSFER,
                PolicyType.MAX_TRANSACTION_AMOUNT,
                new BigDecimal("100000.0000"),
                null,
                "INR",
                true
        ));

        Optional<FinancialPolicy> policyA = policyService.resolveApplicablePolicy(
                accountA.getId(),
                PolicyType.MAX_TRANSACTION_AMOUNT,
                TransactionType.TRANSFER
        );
        assertThat(policyA).isPresent();
        assertThat(policyA.get().getPolicyScope()).isEqualTo(PolicyScope.GLOBAL);
        assertThat(policyA.get().getAmountLimit()).isEqualByComparingTo("100000.0000");

        // Transfer of 100,000 succeeds
        assertThatCode(() -> transferService.executeTransfer("tx-global-exact", new TransferRequestDto(
                accountA.getId(),
                accountB.getId(),
                new BigDecimal("100000.0000"),
                "INR"
        ))).doesNotThrowAnyException();

        // Transfer of 100,000.0001 fails
        assertThatThrownBy(() -> transferService.executeTransfer("tx-global-over", new TransferRequestDto(
                accountA.getId(),
                accountB.getId(),
                new BigDecimal("100000.0001"),
                "INR"
        ))).isInstanceOf(PolicyViolationException.class)
                .extracting(e -> ((PolicyViolationException) e).getErrorCode())
                .isEqualTo(PolicyErrorCode.POLICY_TRANSACTION_LIMIT_EXCEEDED);
    }

    @Test
    @DisplayName("5. Account policy overrides global policy when enabled")
    void verifyAccountPolicyOverridesGlobalPolicy() {
        // Global policy: max = 100,000
        financialPolicyRepository.save(new FinancialPolicy(
                null,
                PolicyScope.GLOBAL,
                TransactionType.TRANSFER,
                PolicyType.MAX_TRANSACTION_AMOUNT,
                new BigDecimal("100000.0000"),
                null,
                "INR",
                true
        ));

        // Account A policy override: max = 250,000
        financialPolicyRepository.save(new FinancialPolicy(
                accountA.getId(),
                PolicyScope.ACCOUNT,
                TransactionType.TRANSFER,
                PolicyType.MAX_TRANSACTION_AMOUNT,
                new BigDecimal("250000.0000"),
                null,
                "INR",
                true
        ));

        // Resolution for Account A returns account-specific policy
        Optional<FinancialPolicy> resolvedA = policyService.resolveApplicablePolicy(
                accountA.getId(),
                PolicyType.MAX_TRANSACTION_AMOUNT,
                TransactionType.TRANSFER
        );
        assertThat(resolvedA).isPresent();
        assertThat(resolvedA.get().getPolicyScope()).isEqualTo(PolicyScope.ACCOUNT);
        assertThat(resolvedA.get().getAccountId()).isEqualTo(accountA.getId());
        assertThat(resolvedA.get().getAmountLimit()).isEqualByComparingTo("250000.0000");

        // Resolution for Account B returns global policy
        Optional<FinancialPolicy> resolvedB = policyService.resolveApplicablePolicy(
                accountB.getId(),
                PolicyType.MAX_TRANSACTION_AMOUNT,
                TransactionType.TRANSFER
        );
        assertThat(resolvedB).isPresent();
        assertThat(resolvedB.get().getPolicyScope()).isEqualTo(PolicyScope.GLOBAL);
        assertThat(resolvedB.get().getAmountLimit()).isEqualByComparingTo("100000.0000");

        // Account A can transfer 200,000 (> global 100k, <= account 250k)
        assertThatCode(() -> transferService.executeTransfer("tx-acc-override-pass", new TransferRequestDto(
                accountA.getId(),
                accountB.getId(),
                new BigDecimal("200000.0000"),
                "INR"
        ))).doesNotThrowAnyException();

        // Account A cannot transfer 250,000.0001 (> account 250k)
        assertThatThrownBy(() -> transferService.executeTransfer("tx-acc-override-fail", new TransferRequestDto(
                accountA.getId(),
                accountB.getId(),
                new BigDecimal("250000.0001"),
                "INR"
        ))).isInstanceOf(PolicyViolationException.class)
                .extracting(e -> ((PolicyViolationException) e).getErrorCode())
                .isEqualTo(PolicyErrorCode.POLICY_TRANSACTION_LIMIT_EXCEEDED);
    }

    @Test
    @DisplayName("6. Disabled account policy falls back to global policy")
    void verifyDisabledAccountPolicyFallsBackToGlobalPolicy() {
        // Global policy: max = 100,000
        financialPolicyRepository.save(new FinancialPolicy(
                null,
                PolicyScope.GLOBAL,
                TransactionType.TRANSFER,
                PolicyType.MAX_TRANSACTION_AMOUNT,
                new BigDecimal("100000.0000"),
                null,
                "INR",
                true
        ));

        // Account A has a policy but it is DISABLED
        financialPolicyRepository.save(new FinancialPolicy(
                accountA.getId(),
                PolicyScope.ACCOUNT,
                TransactionType.TRANSFER,
                PolicyType.MAX_TRANSACTION_AMOUNT,
                new BigDecimal("250000.0000"),
                null,
                "INR",
                false // disabled
        ));

        // Resolution falls back to global policy
        Optional<FinancialPolicy> resolved = policyService.resolveApplicablePolicy(
                accountA.getId(),
                PolicyType.MAX_TRANSACTION_AMOUNT,
                TransactionType.TRANSFER
        );
        assertThat(resolved).isPresent();
        assertThat(resolved.get().getPolicyScope()).isEqualTo(PolicyScope.GLOBAL);
        assertThat(resolved.get().getAmountLimit()).isEqualByComparingTo("100000.0000");

        // Therefore Account A is subject to 100,000 global limit, NOT 250,000
        assertThatThrownBy(() -> transferService.executeTransfer("tx-disabled-override-fail", new TransferRequestDto(
                accountA.getId(),
                accountB.getId(),
                new BigDecimal("150000.0000"),
                "INR"
        ))).isInstanceOf(PolicyViolationException.class)
                .extracting(e -> ((PolicyViolationException) e).getErrorCode())
                .isEqualTo(PolicyErrorCode.POLICY_TRANSACTION_LIMIT_EXCEEDED);
    }

    @Test
    @DisplayName("15. Debit does not trigger ACCOUNT_BALANCE_LIMIT")
    void verifyDebitDoesNotTriggerAccountBalanceLimit() {
        // Balance limit of 10,000 on Account A
        // But Account A currently has 500,000 (pre-existing balance)
        // Debit of 5,000 from Account A decreases balance, so balance limit does not block it.
        financialPolicyRepository.save(new FinancialPolicy(
                accountA.getId(),
                PolicyScope.ACCOUNT,
                null,
                PolicyType.ACCOUNT_BALANCE_LIMIT,
                new BigDecimal("10000.0000"),
                null,
                "INR",
                true
        ));

        assertThatCode(() -> transferService.executeTransfer("tx-debit-no-balance-check", new TransferRequestDto(
                accountA.getId(),
                accountB.getId(),
                new BigDecimal("5000.0000"),
                "INR"
        ))).doesNotThrowAnyException();
    }
}
