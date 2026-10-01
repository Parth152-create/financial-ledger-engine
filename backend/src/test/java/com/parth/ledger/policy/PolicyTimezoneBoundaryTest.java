package com.parth.ledger.policy;

import com.parth.ledger.BaseIntegrationTest;
import com.parth.ledger.account.Account;
import com.parth.ledger.account.AccountRepository;
import com.parth.ledger.account.AccountStatus;
import com.parth.ledger.account.AccountType;
import com.parth.ledger.audit.AuditEventService;
import com.parth.ledger.policy.dto.AccountLimitSummaryDto;
import com.parth.ledger.security.AuthenticatedUserService;
import com.parth.ledger.system.SystemFundingService;
import com.parth.ledger.transaction.TransactionType;
import com.parth.ledger.user.User;
import com.parth.ledger.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PolicyTimezoneBoundaryTest extends BaseIntegrationTest {

    @Autowired
    private FinancialPolicyRepository financialPolicyRepository;

    @Autowired
    private DailyPolicyUsageRepository dailyPolicyUsageRepository;

    @Autowired
    private PolicyEvaluator policyEvaluator;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AuditEventService auditEventService;

    @Autowired
    private AuthenticatedUserService authenticatedUserService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SystemFundingService systemFundingService;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private org.springframework.transaction.support.TransactionTemplate txTemplate;

    private User alice;
    private User bob;
    private Account aliceAccount;
    private Account bobAccount;

    private static class MutableClock extends Clock {
        private final AtomicReference<Instant> currentInstant;
        private final ZoneId zone;

        public MutableClock(Instant initialInstant, ZoneId zone) {
            this.currentInstant = new AtomicReference<>(initialInstant);
            this.zone = zone;
        }

        public void setInstant(Instant newInstant) {
            this.currentInstant.set(newInstant);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(currentInstant.get(), zone);
        }

        @Override
        public Instant instant() {
            return currentInstant.get();
        }
    }

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

        systemFundingService.bootstrapSystemFunding(new BigDecimal("10000000.0000"));

        alice = userRepository.save(new User("alice.tz@ledger.com", "Alice"));
        bob = userRepository.save(new User("bob.tz@ledger.com", "Bob"));

        aliceAccount = accountRepository.save(new Account(alice, "INR", new BigDecimal("500000.0000")));
        bobAccount = accountRepository.save(new Account(bob, "INR", new BigDecimal("100000.0000")));

        txTemplate = new org.springframework.transaction.support.TransactionTemplate(transactionManager);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        alice.getEmail(),
                        null,
                        Collections.singletonList(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER"))
                )
        );
    }

    @Test
    @DisplayName("M4: Daily amount quota resets authoritatively across the 00:00 UTC boundary")
    void verifyDailyAmountQuotaResetsAtMidnightUtc() {
        // 1. Configure Alice Daily Amount Limit = ₹10,000
        financialPolicyRepository.save(new FinancialPolicy(
                aliceAccount.getId(),
                PolicyScope.ACCOUNT,
                TransactionType.TRANSFER,
                PolicyType.DAILY_TRANSACTION_AMOUNT,
                new BigDecimal("10000.0000"),
                null,
                "INR",
                true
        ));

        // Start clock at 2026-09-30 23:59:59 UTC (1 second before midnight)
        Instant beforeMidnight = Instant.parse("2026-09-30T23:59:59Z");
        MutableClock mutableClock = new MutableClock(beforeMidnight, ZoneOffset.UTC);

        PolicyService timeAwarePolicyService = new PolicyService(
                financialPolicyRepository,
                dailyPolicyUsageRepository,
                policyEvaluator,
                accountRepository,
                auditEventService,
                authenticatedUserService,
                mutableClock
        );

        // Consume entire daily limit on 2026-09-30
        txTemplate.executeWithoutResult(status -> timeAwarePolicyService.evaluateAndRecordTransferLimits(
                aliceAccount,
                bobAccount,
                new BigDecimal("10000.0000")
        ));

        // Check summary at 23:59:59 UTC
        AccountLimitSummaryDto summaryDay1 = timeAwarePolicyService.getAccountLimitSummary(aliceAccount.getId(), TransactionType.TRANSFER);
        assertThat(summaryDay1.dailyAmountLimit()).isEqualByComparingTo(new BigDecimal("10000.0000"));
        assertThat(summaryDay1.dailyAmountUsed()).isEqualByComparingTo(new BigDecimal("10000.0000"));
        assertThat(summaryDay1.dailyAmountRemaining()).isEqualByComparingTo(BigDecimal.ZERO);

        // Further transfer on same day is rejected
        assertThatThrownBy(() -> txTemplate.executeWithoutResult(status -> timeAwarePolicyService.evaluateAndRecordTransferLimits(
                aliceAccount,
                bobAccount,
                new BigDecimal("1.0000")
        ))).isInstanceOf(PolicyViolationException.class)
                .hasMessageContaining("Daily transaction amount limit");

        // 2. Advance clock by 1 second to 2026-10-01 00:00:00 UTC (Midnight UTC boundary)
        Instant afterMidnight = Instant.parse("2026-10-01T00:00:00Z");
        mutableClock.setInstant(afterMidnight);

        // Authoritative summary on new day must reflect fresh quota
        AccountLimitSummaryDto summaryDay2 = timeAwarePolicyService.getAccountLimitSummary(aliceAccount.getId(), TransactionType.TRANSFER);
        assertThat(summaryDay2.dailyAmountUsed()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(summaryDay2.dailyAmountRemaining()).isEqualByComparingTo(new BigDecimal("10000.0000"));

        // New transfer on day D+1 succeeds without being blocked by day D's usage
        txTemplate.executeWithoutResult(status -> timeAwarePolicyService.evaluateAndRecordTransferLimits(
                aliceAccount,
                bobAccount,
                new BigDecimal("6000.0000")
        ));

        AccountLimitSummaryDto summaryDay2After = timeAwarePolicyService.getAccountLimitSummary(aliceAccount.getId(), TransactionType.TRANSFER);
        assertThat(summaryDay2After.dailyAmountUsed()).isEqualByComparingTo(new BigDecimal("6000.0000"));
        assertThat(summaryDay2After.dailyAmountRemaining()).isEqualByComparingTo(new BigDecimal("4000.0000"));
    }

    @Test
    @DisplayName("M4: Daily count quota resets authoritatively across the 00:00 UTC boundary")
    void verifyDailyCountQuotaResetsAtMidnightUtc() {
        // Configure Alice Daily Count Limit = 2
        financialPolicyRepository.save(new FinancialPolicy(
                aliceAccount.getId(),
                PolicyScope.ACCOUNT,
                TransactionType.TRANSFER,
                PolicyType.DAILY_TRANSACTION_COUNT,
                null,
                2,
                "INR",
                true
        ));

        Instant beforeMidnight = Instant.parse("2026-09-30T23:59:59Z");
        MutableClock mutableClock = new MutableClock(beforeMidnight, ZoneOffset.UTC);

        PolicyService timeAwarePolicyService = new PolicyService(
                financialPolicyRepository,
                dailyPolicyUsageRepository,
                policyEvaluator,
                accountRepository,
                auditEventService,
                authenticatedUserService,
                mutableClock
        );

        // Perform 2 transfers to consume count limit
        txTemplate.executeWithoutResult(status -> timeAwarePolicyService.evaluateAndRecordTransferLimits(aliceAccount, bobAccount, new BigDecimal("10.0000")));
        txTemplate.executeWithoutResult(status -> timeAwarePolicyService.evaluateAndRecordTransferLimits(aliceAccount, bobAccount, new BigDecimal("10.0000")));

        AccountLimitSummaryDto summaryDay1 = timeAwarePolicyService.getAccountLimitSummary(aliceAccount.getId(), TransactionType.TRANSFER);
        assertThat(summaryDay1.dailyCountLimit()).isEqualTo(2);
        assertThat(summaryDay1.dailyCountUsed()).isEqualTo(2);
        assertThat(summaryDay1.dailyCountRemaining()).isEqualTo(0);

        // 3rd transfer rejected on day 1
        assertThatThrownBy(() -> txTemplate.executeWithoutResult(status -> timeAwarePolicyService.evaluateAndRecordTransferLimits(
                aliceAccount,
                bobAccount,
                new BigDecimal("10.0000")
        ))).isInstanceOf(PolicyViolationException.class)
                .hasMessageContaining("Daily transaction count limit");

        // Advance clock across midnight UTC boundary to 00:00:00 UTC
        mutableClock.setInstant(Instant.parse("2026-10-01T00:00:00Z"));

        AccountLimitSummaryDto summaryDay2 = timeAwarePolicyService.getAccountLimitSummary(aliceAccount.getId(), TransactionType.TRANSFER);
        assertThat(summaryDay2.dailyCountUsed()).isEqualTo(0);
        assertThat(summaryDay2.dailyCountRemaining()).isEqualTo(2);

        // Transfer succeeds on day 2
        txTemplate.executeWithoutResult(status -> timeAwarePolicyService.evaluateAndRecordTransferLimits(aliceAccount, bobAccount, new BigDecimal("10.0000")));
        AccountLimitSummaryDto summaryDay2After = timeAwarePolicyService.getAccountLimitSummary(aliceAccount.getId(), TransactionType.TRANSFER);
        assertThat(summaryDay2After.dailyCountUsed()).isEqualTo(1);
        assertThat(summaryDay2After.dailyCountRemaining()).isEqualTo(1);
    }
}
