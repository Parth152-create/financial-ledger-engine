package com.parth.ledger.system;

import com.parth.ledger.account.Account;
import com.parth.ledger.account.AccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * Service managing platform treasury and system clearing initial funding.
 *
 * Enforces the strict double-entry invariant:
 * PLATFORM / TREASURY FUNDING -> SYSTEM_CLEARING -> USER_CHECKING
 *
 * Every balance in the system is rooted in an immutable double-entry ledger transaction.
 * SYSTEM_TREASURY carries the offsetting platform equity debit, while SYSTEM_CLEARING
 * carries the credited settlement liquidity.
 */
@Service
public class SystemFundingService {

    private static final Logger log = LoggerFactory.getLogger(SystemFundingService.class);

    public static final UUID SYSTEM_CLEARING_ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final UUID SYSTEM_TREASURY_ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    public static final UUID BOOTSTRAP_TRANSACTION_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    public static final UUID BOOTSTRAP_DEBIT_ENTRY_ID = UUID.fromString("00000000-0000-0000-0000-000000000011");
    public static final UUID BOOTSTRAP_CREDIT_ENTRY_ID = UUID.fromString("00000000-0000-0000-0000-000000000012");
    public static final String BOOTSTRAP_IDEMPOTENCY_KEY = "SYSTEM-BOOTSTRAP-FUNDING-INR-01";
    public static final String CLEARING_ACCOUNT_NUMBER = "ACCT-SYSTEM-CLEARING-01";
    public static final String TREASURY_ACCOUNT_NUMBER = "ACCT-SYSTEM-TREASURY-01";
    public static final BigDecimal DEFAULT_BOOTSTRAP_AMOUNT = new BigDecimal("10000000.0000");

    private final JdbcTemplate jdbcTemplate;
    private final AccountRepository accountRepository;

    public SystemFundingService(JdbcTemplate jdbcTemplate, AccountRepository accountRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.accountRepository = accountRepository;
    }

    /**
     * Bootstraps or resets the platform funding state using strict double-entry accounting.
     * Ensures SYSTEM_TREASURY and SYSTEM_CLEARING accounts exist, and establishes the
     * balanced funding transaction and corresponding debit/credit ledger entries.
     *
     * @param fundingAmount Amount to fund SYSTEM_CLEARING from SYSTEM_TREASURY.
     * @return The funded SYSTEM_CLEARING Account entity.
     */
    @Transactional
    public Account bootstrapSystemFunding(BigDecimal fundingAmount) {
        BigDecimal scaledAmount = fundingAmount.setScale(4, RoundingMode.HALF_UP);

        // 1. Ensure SYSTEM_TREASURY exists with counterbalancing negative equity balance
        jdbcTemplate.update("""
            INSERT INTO accounts (id, user_id, currency, balance, version, created_at, updated_at, account_type, status, account_number)
            VALUES (?, NULL, 'INR', ?, 0, NOW(), NOW(), 'SYSTEM_TREASURY', 'ACTIVE', ?)
            ON CONFLICT (id) DO UPDATE SET balance = EXCLUDED.balance, status = 'ACTIVE'
        """, SYSTEM_TREASURY_ACCOUNT_ID, scaledAmount.negate(), TREASURY_ACCOUNT_NUMBER);

        // 2. Ensure SYSTEM_CLEARING exists with credited settlement balance
        jdbcTemplate.update("""
            INSERT INTO accounts (id, user_id, currency, balance, version, created_at, updated_at, account_type, status, account_number)
            VALUES (?, NULL, 'INR', ?, 0, NOW(), NOW(), 'SYSTEM_CLEARING', 'ACTIVE', ?)
            ON CONFLICT (id) DO UPDATE SET balance = EXCLUDED.balance, status = 'ACTIVE'
        """, SYSTEM_CLEARING_ACCOUNT_ID, scaledAmount, CLEARING_ACCOUNT_NUMBER);

        // 3. Upsert bootstrap transaction
        jdbcTemplate.update("""
            INSERT INTO transactions (id, idempotency_key, amount, currency, status, source_account_id, destination_account_id, created_at, completed_at, transaction_type, initiated_by_user_id, description)
            VALUES (?, ?, ?, 'INR', 'COMPLETED', ?, ?, NOW(), NOW(), 'SYSTEM_FUNDING', NULL, 'Initial platform treasury funding to system clearing')
            ON CONFLICT (idempotency_key) DO UPDATE SET amount = EXCLUDED.amount, status = 'COMPLETED'
        """, BOOTSTRAP_TRANSACTION_ID, BOOTSTRAP_IDEMPOTENCY_KEY, scaledAmount, SYSTEM_TREASURY_ACCOUNT_ID, SYSTEM_CLEARING_ACCOUNT_ID);

        // 4. Ensure balanced ledger entries exist (ledger_entries are immutable; insert if not present)
        Integer entryCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries WHERE transaction_id = ?",
                Integer.class,
                BOOTSTRAP_TRANSACTION_ID
        );

        if (entryCount == null || entryCount == 0) {
            jdbcTemplate.update("""
                INSERT INTO ledger_entries (id, transaction_id, account_id, entry_type, amount, currency, created_at)
                VALUES (?, ?, ?, 'DEBIT', ?, 'INR', NOW())
            """, BOOTSTRAP_DEBIT_ENTRY_ID, BOOTSTRAP_TRANSACTION_ID, SYSTEM_TREASURY_ACCOUNT_ID, scaledAmount);

            jdbcTemplate.update("""
                INSERT INTO ledger_entries (id, transaction_id, account_id, entry_type, amount, currency, created_at)
                VALUES (?, ?, ?, 'CREDIT', ?, 'INR', NOW())
            """, BOOTSTRAP_CREDIT_ENTRY_ID, BOOTSTRAP_TRANSACTION_ID, SYSTEM_CLEARING_ACCOUNT_ID, scaledAmount);
        }

        log.info("Platform bootstrap funding established: treasury={} (DEBIT -{} INR), clearing={} (CREDIT +{} INR)",
                SYSTEM_TREASURY_ACCOUNT_ID, scaledAmount, SYSTEM_CLEARING_ACCOUNT_ID, scaledAmount);

        return accountRepository.findById(SYSTEM_CLEARING_ACCOUNT_ID).orElseThrow();
    }

    /**
     * Ensures bootstrap platform funding using the default funding amount ($10,000,000.0000).
     *
     * @return The funded SYSTEM_CLEARING Account entity.
     */
    @Transactional
    public Account ensureBootstrapFunding() {
        return bootstrapSystemFunding(DEFAULT_BOOTSTRAP_AMOUNT);
    }
}
