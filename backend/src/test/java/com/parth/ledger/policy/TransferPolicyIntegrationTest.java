package com.parth.ledger.policy;

import com.parth.ledger.BaseIntegrationTest;
import com.parth.ledger.account.Account;
import com.parth.ledger.account.AccountRepository;
import com.parth.ledger.ledger.LedgerEntryRepository;
import com.parth.ledger.system.SystemFundingService;
import com.parth.ledger.transaction.Transaction;
import com.parth.ledger.transaction.TransactionRepository;
import com.parth.ledger.transaction.TransactionType;
import com.parth.ledger.transaction.dto.TransferRequestDto;
import com.parth.ledger.transaction.dto.TransferResponseDto;
import com.parth.ledger.transaction.service.TransactionReversalService;
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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransferPolicyIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TransferService transferService;

    @Autowired
    private TransactionReversalService transactionReversalService;

    @Autowired
    private SystemFundingService systemFundingService;

    @Autowired
    private PolicyService policyService;

    @Autowired
    private FinancialPolicyRepository financialPolicyRepository;

    @Autowired
    private DailyPolicyUsageRepository dailyPolicyUsageRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

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

        aliceUser = userRepository.save(new User("alice.transfer@ledger.com", "Alice"));
        bobUser = userRepository.save(new User("bob.transfer@ledger.com", "Bob"));

        aliceAccount = accountRepository.save(new Account(aliceUser, "INR", new BigDecimal("100000.0000")));
        bobAccount = accountRepository.save(new Account(bobUser, "INR", new BigDecimal("1000.0000")));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice.transfer@ledger.com", null, Collections.emptyList())
        );
    }

    @Test
    @DisplayName("16. Transfer evaluates source outgoing limits (max, daily amount, daily count)")
    void verifyTransferEvaluatesSourceOutgoingLimits() {
        // Daily amount limit: 5,000; Daily count limit: 2; Max tx: 3,000
        financialPolicyRepository.save(new FinancialPolicy(
                aliceAccount.getId(),
                PolicyScope.ACCOUNT,
                TransactionType.TRANSFER,
                PolicyType.MAX_TRANSACTION_AMOUNT,
                new BigDecimal("3000.0000"),
                null,
                "INR",
                true
        ));
        financialPolicyRepository.save(new FinancialPolicy(
                aliceAccount.getId(),
                PolicyScope.ACCOUNT,
                TransactionType.TRANSFER,
                PolicyType.DAILY_TRANSACTION_AMOUNT,
                new BigDecimal("5000.0000"),
                null,
                "INR",
                true
        ));
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

        // Attempt 1: 3,500 exceeds max tx limit (3,000)
        assertThatThrownBy(() -> transferService.executeTransfer("tx-exceed-max", new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("3500.0000"),
                "INR"
        ))).isInstanceOf(PolicyViolationException.class)
                .extracting(e -> ((PolicyViolationException) e).getErrorCode())
                .isEqualTo(PolicyErrorCode.POLICY_TRANSACTION_LIMIT_EXCEEDED);

        // Tx 1: 2,500 succeeds (Count = 1, Daily = 2,500)
        TransferResponseDto res1 = transferService.executeTransfer("tx-valid-1", new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("2500.0000"),
                "INR"
        ));
        assertThat(res1.status()).isEqualTo(com.parth.ledger.transaction.TransactionStatus.COMPLETED);

        // Attempt 2: 3,000 would exceed daily amount (2,500 + 3,000 = 5,500 > 5,000)
        assertThatThrownBy(() -> transferService.executeTransfer("tx-exceed-daily-amt", new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("3000.0000"),
                "INR"
        ))).isInstanceOf(PolicyViolationException.class)
                .extracting(e -> ((PolicyViolationException) e).getErrorCode())
                .isEqualTo(PolicyErrorCode.POLICY_DAILY_AMOUNT_LIMIT_EXCEEDED);

        // Tx 2: 2,000 succeeds (Count = 2, Daily = 4,500 <= 5,000)
        TransferResponseDto res2 = transferService.executeTransfer("tx-valid-2", new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("2000.0000"),
                "INR"
        ));
        assertThat(res2.status()).isEqualTo(com.parth.ledger.transaction.TransactionStatus.COMPLETED);

        // Attempt 3: 100 exceeds daily count (Count would be 3 > 2)
        assertThatThrownBy(() -> transferService.executeTransfer("tx-exceed-count", new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("100.0000"),
                "INR"
        ))).isInstanceOf(PolicyViolationException.class)
                .extracting(e -> ((PolicyViolationException) e).getErrorCode())
                .isEqualTo(PolicyErrorCode.POLICY_DAILY_COUNT_LIMIT_EXCEEDED);
    }

    @Test
    @DisplayName("17. Transfer evaluates destination balance limit")
    void verifyTransferEvaluatesDestinationBalanceLimit() {
        // Bob has initial balance 1,000. Configure max balance limit: 5,000
        financialPolicyRepository.save(new FinancialPolicy(
                bobAccount.getId(),
                PolicyScope.ACCOUNT,
                null,
                PolicyType.ACCOUNT_BALANCE_LIMIT,
                new BigDecimal("5000.0000"),
                null,
                "INR",
                true
        ));

        // Transfer 4,000 brings Bob to 5,000 (exactly at limit) -> succeeds
        assertThatCode(() -> transferService.executeTransfer("tx-dest-bal-exact", new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("4000.0000"),
                "INR"
        ))).doesNotThrowAnyException();

        Account bobUpdated = accountRepository.findById(bobAccount.getId()).orElseThrow();
        assertThat(bobUpdated.getBalance()).isEqualByComparingTo("5000.0000");

        // Next transfer of 0.0001 would bring Bob to 5000.0001 -> rejected
        assertThatThrownBy(() -> transferService.executeTransfer("tx-dest-bal-overflow", new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("0.0001"),
                "INR"
        ))).isInstanceOf(PolicyViolationException.class)
                .extracting(e -> ((PolicyViolationException) e).getErrorCode())
                .isEqualTo(PolicyErrorCode.POLICY_BALANCE_LIMIT_EXCEEDED);
    }

    @Test
    @DisplayName("18. Recipient does not consume outgoing transaction count or daily amount")
    void verifyRecipientDoesNotConsumeOutgoingUsage() {
        // Bob has a daily transfer limit of 1,000 and count limit of 1
        financialPolicyRepository.save(new FinancialPolicy(
                bobAccount.getId(),
                PolicyScope.ACCOUNT,
                TransactionType.TRANSFER,
                PolicyType.DAILY_TRANSACTION_AMOUNT,
                new BigDecimal("1000.0000"),
                null,
                "INR",
                true
        ));
        financialPolicyRepository.save(new FinancialPolicy(
                bobAccount.getId(),
                PolicyScope.ACCOUNT,
                TransactionType.TRANSFER,
                PolicyType.DAILY_TRANSACTION_COUNT,
                null,
                1,
                "INR",
                true
        ));

        // Alice transfers 5,000 to Bob
        transferService.executeTransfer("tx-alice-to-bob", new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("5000.0000"),
                "INR"
        ));

        // Check Bob's daily transfer usage: should be completely empty (0 used, 0 count)
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Optional<DailyPolicyUsage> bobUsage = dailyPolicyUsageRepository.findByAccountIdAndTransactionTypeAndUsageDate(
                bobAccount.getId(),
                TransactionType.TRANSFER,
                today
        );
        assertThat(bobUsage).isEmpty();

        // Switch security context to Bob: Bob can still execute his own outgoing transfer of 1,000
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("bob.transfer@ledger.com", null, Collections.emptyList())
        );
        assertThatCode(() -> transferService.executeTransfer("tx-bob-outgoing", new TransferRequestDto(
                bobAccount.getId(),
                aliceAccount.getId(),
                new BigDecimal("1000.0000"),
                "INR"
        ))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("21. Reversals do not consume normal daily limits")
    void verifyReversalDoesNotConsumeNormalLimits() {
        // Alice has daily count limit = 1 and daily amount limit = 2,000
        financialPolicyRepository.save(new FinancialPolicy(
                aliceAccount.getId(),
                PolicyScope.ACCOUNT,
                TransactionType.TRANSFER,
                PolicyType.DAILY_TRANSACTION_AMOUNT,
                new BigDecimal("2000.0000"),
                null,
                "INR",
                true
        ));
        financialPolicyRepository.save(new FinancialPolicy(
                aliceAccount.getId(),
                PolicyScope.ACCOUNT,
                TransactionType.TRANSFER,
                PolicyType.DAILY_TRANSACTION_COUNT,
                null,
                1,
                "INR",
                true
        ));

        // Alice transfers 2,000 to Bob (consumes count = 1, amount = 2,000)
        TransferResponseDto txResponse = transferService.executeTransfer("tx-to-reverse", new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("2000.0000"),
                "INR"
        ));
        UUID txId = txResponse.transactionId();

        // Reversal is executed (admin operation)
        userRepository.save(new User("admin@ledger.com", "Admin"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin@ledger.com", null, Collections.singletonList(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")))
        );

        com.parth.ledger.transaction.dto.ReversalResponseDto reversal = transactionReversalService.executeReversal(
                txId,
                "rev-key-01",
                new com.parth.ledger.transaction.dto.ReversalRequestDto("Correction of transfer")
        );
        assertThat(reversal.status()).isEqualTo(com.parth.ledger.transaction.TransactionStatus.COMPLETED);

        // Daily usage for Alice should STILL be count = 1, amount = 2000 (reversal did NOT consume an additional count or amount)
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        DailyPolicyUsage usage = dailyPolicyUsageRepository.findByAccountIdAndTransactionTypeAndUsageDate(
                aliceAccount.getId(),
                TransactionType.TRANSFER,
                today
        ).orElseThrow();

        assertThat(usage.getTransactionCount()).isEqualTo(1);
        assertThat(usage.getAmountUsed()).isEqualByComparingTo("2000.0000");
    }

    @Test
    @DisplayName("22. System funding bypasses user limits")
    void verifySystemFundingBypassesUserLimits() {
        // Configure global limits on TRANSFER
        financialPolicyRepository.save(new FinancialPolicy(
                null,
                PolicyScope.GLOBAL,
                TransactionType.TRANSFER,
                PolicyType.MAX_TRANSACTION_AMOUNT,
                new BigDecimal("500.0000"),
                null,
                "INR",
                true
        ));

        // System bootstrap funding of 10,000,000 INR succeeds without being blocked by user policies
        Account clearing = systemFundingService.bootstrapSystemFunding(new BigDecimal("10000000.0000"));
        assertThat(clearing).isNotNull();
        assertThat(clearing.getBalance()).isGreaterThanOrEqualTo(new BigDecimal("10000000.0000"));
    }
}
