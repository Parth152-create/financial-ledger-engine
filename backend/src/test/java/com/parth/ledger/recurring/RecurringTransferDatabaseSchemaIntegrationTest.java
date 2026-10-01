package com.parth.ledger.recurring;

import com.parth.ledger.BaseIntegrationTest;
import com.parth.ledger.account.Account;
import com.parth.ledger.account.AccountRepository;
import com.parth.ledger.system.SystemFundingService;
import com.parth.ledger.user.User;
import com.parth.ledger.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("V2.6 Recurring Transfers Database Schema & Constraint Integration Tests")
class RecurringTransferDatabaseSchemaIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private RecurringTransferRepository recurringTransferRepository;

    @Autowired
    private RecurringTransferExecutionRepository executionRepository;

    @Autowired
    private SystemFundingService systemFundingService;

    private User alice;
    private Account sourceAccount;
    private Account destAccount;

    @BeforeEach
    void setUp() {
        if (jdbcTemplate != null) {
            jdbcTemplate.execute("TRUNCATE TABLE recurring_transfer_executions CASCADE");
            jdbcTemplate.execute("TRUNCATE TABLE recurring_transfers CASCADE");
            jdbcTemplate.execute("TRUNCATE TABLE transactions CASCADE");
            jdbcTemplate.execute("TRUNCATE TABLE accounts CASCADE");
            jdbcTemplate.execute("TRUNCATE TABLE users CASCADE");
        }
        systemFundingService.bootstrapSystemFunding(SystemFundingService.DEFAULT_BOOTSTRAP_AMOUNT);

        String runId = UUID.randomUUID().toString().substring(0, 8);
        alice = userRepository.save(new User("alice.schema." + runId + "@ledger.com", "Alice"));
        User bob = userRepository.save(new User("bob.schema." + runId + "@ledger.com", "Bob"));

        sourceAccount = accountRepository.save(new Account(alice, "INR", new BigDecimal("1000.0000")));
        destAccount = accountRepository.save(new Account(bob, "INR", new BigDecimal("500.0000")));
    }

    @Test
    @DisplayName("Valid recurring transfer schedule persists and loads accurately")
    void testValidSchedulePersists() {
        RecurringTransfer schedule = new RecurringTransfer(
                alice,
                sourceAccount,
                destAccount,
                new BigDecimal("150.0000"),
                "INR",
                RecurringFrequency.MONTHLY,
                LocalDate.of(2026, 11, 1),
                LocalDate.of(2027, 11, 1),
                Instant.parse("2026-11-01T00:00:00Z")
        );

        RecurringTransfer saved = recurringTransferRepository.save(schedule);
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getAmount()).isEqualByComparingTo(new BigDecimal("150.0000"));
        assertThat(saved.getCurrency()).isEqualTo("INR");
        assertThat(saved.getFrequency()).isEqualTo(RecurringFrequency.MONTHLY);
        assertThat(saved.getStatus()).isEqualTo(RecurringTransferStatus.ACTIVE);
        assertThat(saved.getExecutionCount()).isEqualTo(0);
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("DB Constraint: amount must be strictly greater than 0")
    void testAmountConstraint() {
        assertThatThrownBy(() -> {
            jdbcTemplate.execute(String.format(
                    "INSERT INTO recurring_transfers (id, user_id, source_account_id, destination_account_id, amount, currency, frequency, status, next_execution_at, start_date) " +
                            "VALUES ('%s', '%s', '%s', '%s', 0.0000, 'INR', 'DAILY', 'ACTIVE', NOW(), CURRENT_DATE)",
                    UUID.randomUUID(), alice.getId(), sourceAccount.getId(), destAccount.getId()
            ));
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("DB Constraint: currency must be INR")
    void testCurrencyConstraint() {
        assertThatThrownBy(() -> {
            jdbcTemplate.execute(String.format(
                    "INSERT INTO recurring_transfers (id, user_id, source_account_id, destination_account_id, amount, currency, frequency, status, next_execution_at, start_date) " +
                            "VALUES ('%s', '%s', '%s', '%s', 100.0000, 'USD', 'DAILY', 'ACTIVE', NOW(), CURRENT_DATE)",
                    UUID.randomUUID(), alice.getId(), sourceAccount.getId(), destAccount.getId()
            ));
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("DB Constraint: source account must not equal destination account")
    void testDifferentAccountsConstraint() {
        assertThatThrownBy(() -> {
            jdbcTemplate.execute(String.format(
                    "INSERT INTO recurring_transfers (id, user_id, source_account_id, destination_account_id, amount, currency, frequency, status, next_execution_at, start_date) " +
                            "VALUES ('%s', '%s', '%s', '%s', 100.0000, 'INR', 'DAILY', 'ACTIVE', NOW(), CURRENT_DATE)",
                    UUID.randomUUID(), alice.getId(), sourceAccount.getId(), sourceAccount.getId()
            ));
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("DB Constraint: end_date >= start_date when end_date exists")
    void testEndDateConstraint() {
        assertThatThrownBy(() -> {
            jdbcTemplate.execute(String.format(
                    "INSERT INTO recurring_transfers (id, user_id, source_account_id, destination_account_id, amount, currency, frequency, status, next_execution_at, start_date, end_date) " +
                            "VALUES ('%s', '%s', '%s', '%s', 100.0000, 'INR', 'DAILY', 'ACTIVE', NOW(), '2026-12-01', '2026-11-01')",
                    UUID.randomUUID(), alice.getId(), sourceAccount.getId(), destAccount.getId()
            ));
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("DB Uniqueness: (recurring_transfer_id, execution_key) prevents duplicate slot executions")
    void testExecutionSlotUniqueness() {
        RecurringTransfer schedule = recurringTransferRepository.save(new RecurringTransfer(
                alice,
                sourceAccount,
                destAccount,
                new BigDecimal("100.0000"),
                "INR",
                RecurringFrequency.DAILY,
                LocalDate.of(2026, 11, 1),
                null,
                Instant.parse("2026-11-01T00:00:00Z")
        ));

        RecurringTransferExecution exec1 = new RecurringTransferExecution(
                schedule,
                "EXEC_SLOT_001",
                Instant.parse("2026-11-01T00:00:00Z"),
                null,
                RecurringExecutionStatus.FAILED,
                "Insufficient funds",
                Instant.now()
        );
        executionRepository.save(exec1);

        // Attempting to insert duplicate execution for identical slot
        RecurringTransferExecution exec2 = new RecurringTransferExecution(
                schedule,
                "EXEC_SLOT_001",
                Instant.parse("2026-11-01T00:00:00Z"),
                null,
                RecurringExecutionStatus.FAILED,
                "Another error",
                Instant.now()
        );

        assertThatThrownBy(() -> executionRepository.saveAndFlush(exec2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
