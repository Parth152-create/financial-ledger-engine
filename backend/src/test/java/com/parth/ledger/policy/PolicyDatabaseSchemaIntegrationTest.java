package com.parth.ledger.policy;

import com.parth.ledger.BaseIntegrationTest;
import com.parth.ledger.account.Account;
import com.parth.ledger.account.AccountRepository;
import com.parth.ledger.user.User;
import com.parth.ledger.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PolicyDatabaseSchemaIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    private Account testAccount;

    @BeforeEach
    void setUp() {
        if (jdbcTemplate != null) {
            jdbcTemplate.execute("TRUNCATE TABLE audit_events CASCADE");
            jdbcTemplate.execute("TRUNCATE TABLE ledger_entries CASCADE");
            jdbcTemplate.execute("TRUNCATE TABLE transactions CASCADE");
            jdbcTemplate.execute("TRUNCATE TABLE policy_usage_daily CASCADE");
            jdbcTemplate.execute("TRUNCATE TABLE financial_policies CASCADE");
            jdbcTemplate.execute("TRUNCATE TABLE accounts CASCADE");
            jdbcTemplate.execute("TRUNCATE TABLE users CASCADE");
        }

        User user = userRepository.save(new User("schema.test@ledger.com", "Schema User"));
        testAccount = accountRepository.save(new Account(user, "INR", new BigDecimal("1000.0000")));
    }

    @Test
    @DisplayName("DB Constraint: Currency must strictly be 'INR'")
    void verifyCurrencyConstraint() {
        assertThatThrownBy(() -> jdbcTemplate.execute(String.format("""
                INSERT INTO financial_policies (id, account_id, policy_scope, transaction_type, policy_type, amount_limit, currency, enabled, created_at, updated_at)
                VALUES ('%s', NULL, 'GLOBAL', 'TRANSFER', 'MAX_TRANSACTION_AMOUNT', 1000.0000, 'USD', TRUE, NOW(), NOW())
                """, UUID.randomUUID())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("DB Constraint: GLOBAL policy cannot have account_id")
    void verifyGlobalPolicyCannotHaveAccountId() {
        assertThatThrownBy(() -> jdbcTemplate.execute(String.format("""
                INSERT INTO financial_policies (id, account_id, policy_scope, transaction_type, policy_type, amount_limit, currency, enabled, created_at, updated_at)
                VALUES ('%s', '%s', 'GLOBAL', 'TRANSFER', 'MAX_TRANSACTION_AMOUNT', 1000.0000, 'INR', TRUE, NOW(), NOW())
                """, UUID.randomUUID(), testAccount.getId())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("DB Constraint: ACCOUNT policy must have non-null account_id")
    void verifyAccountPolicyMustHaveAccountId() {
        assertThatThrownBy(() -> jdbcTemplate.execute(String.format("""
                INSERT INTO financial_policies (id, account_id, policy_scope, transaction_type, policy_type, amount_limit, currency, enabled, created_at, updated_at)
                VALUES ('%s', NULL, 'ACCOUNT', 'TRANSFER', 'MAX_TRANSACTION_AMOUNT', 1000.0000, 'INR', TRUE, NOW(), NOW())
                """, UUID.randomUUID())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("DB Constraint: ACCOUNT_BALANCE_LIMIT must have null transaction_type")
    void verifyAccountBalanceLimitTransactionTypeNull() {
        // Balance limit with non-null transaction_type is rejected
        assertThatThrownBy(() -> jdbcTemplate.execute(String.format("""
                INSERT INTO financial_policies (id, account_id, policy_scope, transaction_type, policy_type, amount_limit, currency, enabled, created_at, updated_at)
                VALUES ('%s', '%s', 'ACCOUNT', 'TRANSFER', 'ACCOUNT_BALANCE_LIMIT', 50000.0000, 'INR', TRUE, NOW(), NOW())
                """, UUID.randomUUID(), testAccount.getId())))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Balance limit with null transaction_type succeeds
        jdbcTemplate.execute(String.format("""
                INSERT INTO financial_policies (id, account_id, policy_scope, transaction_type, policy_type, amount_limit, currency, enabled, created_at, updated_at)
                VALUES ('%s', '%s', 'ACCOUNT', NULL, 'ACCOUNT_BALANCE_LIMIT', 50000.0000, 'INR', TRUE, NOW(), NOW())
                """, UUID.randomUUID(), testAccount.getId()));
    }

    @Test
    @DisplayName("DB Constraint: Non-balance policies strictly require non-null transaction_type (V13 hardened)")
    void verifyNonBalancePolicyRequiresNonNullTransactionType() {
        // MAX_TRANSACTION_AMOUNT with null transaction_type MUST fail
        assertThatThrownBy(() -> jdbcTemplate.execute(String.format("""
                INSERT INTO financial_policies (id, account_id, policy_scope, transaction_type, policy_type, amount_limit, currency, enabled, created_at, updated_at)
                VALUES ('%s', NULL, 'GLOBAL', NULL, 'MAX_TRANSACTION_AMOUNT', 100000.0000, 'INR', TRUE, NOW(), NOW())
                """, UUID.randomUUID())))
                .isInstanceOf(DataIntegrityViolationException.class);

        // DAILY_TRANSACTION_AMOUNT with null transaction_type MUST fail
        assertThatThrownBy(() -> jdbcTemplate.execute(String.format("""
                INSERT INTO financial_policies (id, account_id, policy_scope, transaction_type, policy_type, amount_limit, currency, enabled, created_at, updated_at)
                VALUES ('%s', NULL, 'GLOBAL', NULL, 'DAILY_TRANSACTION_AMOUNT', 500000.0000, 'INR', TRUE, NOW(), NOW())
                """, UUID.randomUUID())))
                .isInstanceOf(DataIntegrityViolationException.class);

        // DAILY_TRANSACTION_COUNT with null transaction_type MUST fail
        assertThatThrownBy(() -> jdbcTemplate.execute(String.format("""
                INSERT INTO financial_policies (id, account_id, policy_scope, transaction_type, policy_type, count_limit, currency, enabled, created_at, updated_at)
                VALUES ('%s', NULL, 'GLOBAL', NULL, 'DAILY_TRANSACTION_COUNT', 20, 'INR', TRUE, NOW(), NOW())
                """, UUID.randomUUID())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("DB Constraint: Non-balance policies succeed with valid transaction_type and fail with invalid")
    void verifyNonBalancePolicyTransactionTypeValidation() {
        // Valid transaction_types succeed
        jdbcTemplate.execute(String.format("""
                INSERT INTO financial_policies (id, account_id, policy_scope, transaction_type, policy_type, amount_limit, currency, enabled, created_at, updated_at)
                VALUES ('%s', NULL, 'GLOBAL', 'TRANSFER', 'MAX_TRANSACTION_AMOUNT', 100000.0000, 'INR', TRUE, NOW(), NOW())
                """, UUID.randomUUID()));

        jdbcTemplate.execute(String.format("""
                INSERT INTO financial_policies (id, account_id, policy_scope, transaction_type, policy_type, amount_limit, currency, enabled, created_at, updated_at)
                VALUES ('%s', NULL, 'GLOBAL', 'DEPOSIT', 'DAILY_TRANSACTION_AMOUNT', 500000.0000, 'INR', TRUE, NOW(), NOW())
                """, UUID.randomUUID()));

        jdbcTemplate.execute(String.format("""
                INSERT INTO financial_policies (id, account_id, policy_scope, transaction_type, policy_type, count_limit, currency, enabled, created_at, updated_at)
                VALUES ('%s', NULL, 'GLOBAL', 'WITHDRAWAL', 'DAILY_TRANSACTION_COUNT', 20, 'INR', TRUE, NOW(), NOW())
                """, UUID.randomUUID()));

        // Invalid transaction_type fails
        assertThatThrownBy(() -> jdbcTemplate.execute(String.format("""
                INSERT INTO financial_policies (id, account_id, policy_scope, transaction_type, policy_type, amount_limit, currency, enabled, created_at, updated_at)
                VALUES ('%s', NULL, 'GLOBAL', 'INVESTMENT', 'MAX_TRANSACTION_AMOUNT', 100000.0000, 'INR', TRUE, NOW(), NOW())
                """, UUID.randomUUID())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("DB Partial Unique Index: uk_audit_events_policy_rejection_correlation enforces single audit event per correlationId")
    void verifyPolicyRejectionAuditUniqueIndex() {
        String correlationId = "corr-" + UUID.randomUUID();

        // First policy rejection audit event insertion succeeds
        jdbcTemplate.execute(String.format("""
                INSERT INTO audit_events (id, actor_user_id, event_type, entity_type, entity_id, metadata, created_at)
                VALUES ('%s', NULL, 'TRANSFER_REJECTED_POLICY', 'ACCOUNT', '%s', '{"correlationId": "%s"}'::jsonb, NOW())
                """, UUID.randomUUID(), testAccount.getId(), correlationId));

        // Duplicate policy rejection audit event insertion with identical correlationId FAILS at database level
        assertThatThrownBy(() -> jdbcTemplate.execute(String.format("""
                INSERT INTO audit_events (id, actor_user_id, event_type, entity_type, entity_id, metadata, created_at)
                VALUES ('%s', NULL, 'TRANSFER_REJECTED_POLICY', 'ACCOUNT', '%s', '{"correlationId": "%s"}'::jsonb, NOW())
                """, UUID.randomUUID(), testAccount.getId(), correlationId)))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Different correlationId succeeds
        String differentCorrelationId = "corr-" + UUID.randomUUID();
        jdbcTemplate.execute(String.format("""
                INSERT INTO audit_events (id, actor_user_id, event_type, entity_type, entity_id, metadata, created_at)
                VALUES ('%s', NULL, 'TRANSFER_REJECTED_POLICY', 'ACCOUNT', '%s', '{"correlationId": "%s"}'::jsonb, NOW())
                """, UUID.randomUUID(), testAccount.getId(), differentCorrelationId));
    }

    @Test
    @DisplayName("DB Constraint: Positive limits enforced, zero and negative rejected")
    void verifyPositiveLimitsEnforced() {
        // Zero amount rejected
        assertThatThrownBy(() -> jdbcTemplate.execute(String.format("""
                INSERT INTO financial_policies (id, account_id, policy_scope, transaction_type, policy_type, amount_limit, currency, enabled, created_at, updated_at)
                VALUES ('%s', NULL, 'GLOBAL', 'TRANSFER', 'MAX_TRANSACTION_AMOUNT', 0.0000, 'INR', TRUE, NOW(), NOW())
                """, UUID.randomUUID())))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Negative amount rejected
        assertThatThrownBy(() -> jdbcTemplate.execute(String.format("""
                INSERT INTO financial_policies (id, account_id, policy_scope, transaction_type, policy_type, amount_limit, currency, enabled, created_at, updated_at)
                VALUES ('%s', NULL, 'GLOBAL', 'TRANSFER', 'MAX_TRANSACTION_AMOUNT', -100.0000, 'INR', TRUE, NOW(), NOW())
                """, UUID.randomUUID())))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Zero count rejected
        assertThatThrownBy(() -> jdbcTemplate.execute(String.format("""
                INSERT INTO financial_policies (id, account_id, policy_scope, transaction_type, policy_type, count_limit, currency, enabled, created_at, updated_at)
                VALUES ('%s', NULL, 'GLOBAL', 'TRANSFER', 'DAILY_TRANSACTION_COUNT', 0, 'INR', TRUE, NOW(), NOW())
                """, UUID.randomUUID())))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Negative count rejected
        assertThatThrownBy(() -> jdbcTemplate.execute(String.format("""
                INSERT INTO financial_policies (id, account_id, policy_scope, transaction_type, policy_type, count_limit, currency, enabled, created_at, updated_at)
                VALUES ('%s', NULL, 'GLOBAL', 'TRANSFER', 'DAILY_TRANSACTION_COUNT', -5, 'INR', TRUE, NOW(), NOW())
                """, UUID.randomUUID())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("DB Unique Constraint: uk_financial_policies prevents duplicate definitions (UNIQUE NULLS NOT DISTINCT)")
    void verifyUniquePolicyConstraint() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        // Insert first global policy
        jdbcTemplate.execute(String.format("""
                INSERT INTO financial_policies (id, account_id, policy_scope, transaction_type, policy_type, amount_limit, currency, enabled, created_at, updated_at)
                VALUES ('%s', NULL, 'GLOBAL', 'TRANSFER', 'MAX_TRANSACTION_AMOUNT', 100000.0000, 'INR', TRUE, NOW(), NOW())
                """, id1));

        // Attempt duplicate global policy with identical (account_id NULL, policy_type, transaction_type)
        assertThatThrownBy(() -> jdbcTemplate.execute(String.format("""
                INSERT INTO financial_policies (id, account_id, policy_scope, transaction_type, policy_type, amount_limit, currency, enabled, created_at, updated_at)
                VALUES ('%s', NULL, 'GLOBAL', 'TRANSFER', 'MAX_TRANSACTION_AMOUNT', 200000.0000, 'INR', TRUE, NOW(), NOW())
                """, id2)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("DB Unique Constraint: uk_policy_usage_daily prevents duplicate account/type/date records")
    void verifyUniqueDailyUsageConstraint() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        jdbcTemplate.execute(String.format("""
                INSERT INTO policy_usage_daily (id, account_id, transaction_type, usage_date, amount_used, transaction_count, created_at, updated_at)
                VALUES ('%s', '%s', 'TRANSFER', CURRENT_DATE, 500.0000, 1, NOW(), NOW())
                """, id1, testAccount.getId()));

        assertThatThrownBy(() -> jdbcTemplate.execute(String.format("""
                INSERT INTO policy_usage_daily (id, account_id, transaction_type, usage_date, amount_used, transaction_count, created_at, updated_at)
                VALUES ('%s', '%s', 'TRANSFER', CURRENT_DATE, 200.0000, 1, NOW(), NOW())
                """, id2, testAccount.getId())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("DB Foreign Key: Invalid account_id in policy is rejected")
    void verifyAccountForeignKey() {
        UUID fakeAccountId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbcTemplate.execute(String.format("""
                INSERT INTO financial_policies (id, account_id, policy_scope, transaction_type, policy_type, amount_limit, currency, enabled, created_at, updated_at)
                VALUES ('%s', '%s', 'ACCOUNT', 'TRANSFER', 'MAX_TRANSACTION_AMOUNT', 1000.0000, 'INR', TRUE, NOW(), NOW())
                """, UUID.randomUUID(), fakeAccountId)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("DB Indexes exist on financial_policies and policy_usage_daily")
    void verifyIndexesExist() {
        List<String> policyIndexes = jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'financial_policies'",
                String.class
        );
        assertThat(policyIndexes).contains(
                "idx_financial_policies_account_id",
                "idx_financial_policies_scope_type_tx",
                "idx_financial_policies_enabled"
        );

        List<String> usageIndexes = jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'policy_usage_daily'",
                String.class
        );
        assertThat(usageIndexes).contains("idx_policy_usage_daily_lookup");
    }
}
