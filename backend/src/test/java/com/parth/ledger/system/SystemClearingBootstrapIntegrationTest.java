package com.parth.ledger.system;

import com.parth.ledger.BaseIntegrationTest;
import com.parth.ledger.account.Account;
import com.parth.ledger.account.AccountClosedException;
import com.parth.ledger.account.AccountFrozenException;
import com.parth.ledger.account.AccountNotFoundException;
import com.parth.ledger.account.AccountRepository;
import com.parth.ledger.account.AccountService;
import com.parth.ledger.account.AccountStatus;
import com.parth.ledger.account.AccountType;
import com.parth.ledger.account.InvalidAccountTypeException;
import com.parth.ledger.account.dto.CreateAccountRequestDto;
import com.parth.ledger.deposit.dto.DepositRequestDto;
import com.parth.ledger.deposit.service.DepositService;
import com.parth.ledger.ledger.LedgerEntry;
import com.parth.ledger.ledger.LedgerEntryRepository;
import com.parth.ledger.ledger.LedgerEntryType;
import com.parth.ledger.reconciliation.ReconciliationStatus;
import com.parth.ledger.reconciliation.dto.ReconciliationResultDto;
import com.parth.ledger.reconciliation.service.ReconciliationService;
import com.parth.ledger.transaction.Transaction;
import com.parth.ledger.transaction.TransactionRepository;
import com.parth.ledger.transaction.TransactionStatus;
import com.parth.ledger.transaction.TransactionType;
import com.parth.ledger.transaction.dto.TransactionResponseDto;
import com.parth.ledger.transaction.dto.TransferRequestDto;
import com.parth.ledger.transaction.dto.TransferResponseDto;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@DisplayName("SYSTEM_CLEARING Accounting Model Hardening (INR-Only) Integration Tests")
class SystemClearingBootstrapIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private DepositService depositService;

    @Autowired
    private WithdrawalService withdrawalService;

    @Autowired
    private TransferService transferService;

    @Autowired
    private AccountService accountService;

    @Autowired
    private ReconciliationService reconciliationService;

    @Autowired
    private SystemFundingService systemFundingService;

    private User aliceUser;
    private User bobUser;
    private User adminUser;
    private Account aliceAccount;
    private Account bobAccount;

    @BeforeEach
    void setUp() {
        clearRedis();
        if (jdbcTemplate != null) {
            jdbcTemplate.execute("TRUNCATE TABLE ledger_entries CASCADE");
        }
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();

        // Establish the double-entry system bootstrap funding in INR
        systemFundingService.bootstrapSystemFunding(SystemFundingService.DEFAULT_BOOTSTRAP_AMOUNT);

        aliceUser = userRepository.save(new User("alice.sys@ledger.com", "Alice"));
        bobUser = userRepository.save(new User("bob.sys@ledger.com", "Bob"));
        adminUser = userRepository.save(new User("admin.sys@ledger.com", "Admin"));

        aliceAccount = accountRepository.save(new Account(aliceUser, "INR", BigDecimal.ZERO.setScale(4)));
        bobAccount = accountRepository.save(new Account(bobUser, "INR", BigDecimal.ZERO.setScale(4)));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        if (jdbcTemplate != null) {
            jdbcTemplate.execute("TRUNCATE TABLE ledger_entries CASCADE");
        }
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();
    }

    // =========================================================================
    // TEST 1: FRESH BOOTSTRAP ACCOUNTING (INR)
    // =========================================================================
    @Test
    @DisplayName("TEST 1: Fresh bootstrap accounting establishes valid INR double-entry system accounts and passes reconciliation")
    void test01_freshBootstrapAccounting() {
        // 1. Ensure SYSTEM_CLEARING and SYSTEM_TREASURY exist
        Account clearing = accountRepository.findById(SystemFundingService.SYSTEM_CLEARING_ACCOUNT_ID)
                .orElseThrow(() -> new AssertionError("SYSTEM_CLEARING account not found"));
        Account treasury = accountRepository.findById(SystemFundingService.SYSTEM_TREASURY_ACCOUNT_ID)
                .orElseThrow(() -> new AssertionError("SYSTEM_TREASURY account not found"));

        assertThat(clearing.getAccountType()).isEqualTo(AccountType.SYSTEM_CLEARING);
        assertThat(clearing.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(clearing.getCurrency()).isEqualTo("INR");
        assertThat(clearing.getBalance()).isEqualByComparingTo(SystemFundingService.DEFAULT_BOOTSTRAP_AMOUNT);

        assertThat(treasury.getAccountType()).isEqualTo(AccountType.SYSTEM_TREASURY);
        assertThat(treasury.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(treasury.getCurrency()).isEqualTo("INR");
        assertThat(treasury.getBalance()).isEqualByComparingTo(SystemFundingService.DEFAULT_BOOTSTRAP_AMOUNT.negate());

        // 2. Ensure initial balance is backed by legitimate ledger entries & bootstrap transaction
        Transaction bootstrapTx = transactionRepository.findById(SystemFundingService.BOOTSTRAP_TRANSACTION_ID)
                .orElseThrow(() -> new AssertionError("Bootstrap transaction not found"));
        assertThat(bootstrapTx.getTransactionType()).isEqualTo(TransactionType.SYSTEM_FUNDING);
        assertThat(bootstrapTx.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(bootstrapTx.getCurrency()).isEqualTo("INR");
        assertThat(bootstrapTx.getAmount()).isEqualByComparingTo(SystemFundingService.DEFAULT_BOOTSTRAP_AMOUNT);

        List<LedgerEntry> entries = ledgerEntryRepository.findByTransactionId(bootstrapTx.getId());
        assertThat(entries).hasSize(2);

        LedgerEntry treasuryEntry = entries.stream()
                .filter(e -> e.getAccount().getId().equals(SystemFundingService.SYSTEM_TREASURY_ACCOUNT_ID))
                .findFirst().orElseThrow();
        assertThat(treasuryEntry.getEntryType()).isEqualTo(LedgerEntryType.DEBIT);
        assertThat(treasuryEntry.getCurrency()).isEqualTo("INR");
        assertThat(treasuryEntry.getAmount()).isEqualByComparingTo(SystemFundingService.DEFAULT_BOOTSTRAP_AMOUNT);

        LedgerEntry clearingEntry = entries.stream()
                .filter(e -> e.getAccount().getId().equals(SystemFundingService.SYSTEM_CLEARING_ACCOUNT_ID))
                .findFirst().orElseThrow();
        assertThat(clearingEntry.getEntryType()).isEqualTo(LedgerEntryType.CREDIT);
        assertThat(clearingEntry.getCurrency()).isEqualTo("INR");
        assertThat(clearingEntry.getAmount()).isEqualByComparingTo(SystemFundingService.DEFAULT_BOOTSTRAP_AMOUNT);

        // 3. Ensure reconciliation passes on both SYSTEM_CLEARING and SYSTEM_TREASURY without manual SQL
        ReconciliationResultDto clearingRecon = reconciliationService.reconcileAccountDirectly(SystemFundingService.SYSTEM_CLEARING_ACCOUNT_ID);
        assertThat(clearingRecon.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
        assertThat(clearingRecon.difference()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(clearingRecon.snapshotBalance()).isEqualByComparingTo(SystemFundingService.DEFAULT_BOOTSTRAP_AMOUNT);
        assertThat(clearingRecon.ledgerBalance()).isEqualByComparingTo(SystemFundingService.DEFAULT_BOOTSTRAP_AMOUNT);

        ReconciliationResultDto treasuryRecon = reconciliationService.reconcileAccountDirectly(SystemFundingService.SYSTEM_TREASURY_ACCOUNT_ID);
        assertThat(treasuryRecon.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
        assertThat(treasuryRecon.difference()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(treasuryRecon.snapshotBalance()).isEqualByComparingTo(SystemFundingService.DEFAULT_BOOTSTRAP_AMOUNT.negate());
        assertThat(treasuryRecon.ledgerBalance()).isEqualByComparingTo(SystemFundingService.DEFAULT_BOOTSTRAP_AMOUNT.negate());

        // Sum of system balances is exactly zero
        BigDecimal netSystemBalance = clearing.getBalance().add(treasury.getBalance());
        assertThat(netSystemBalance).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // =========================================================================
    // TEST 2: FIRST DEPOSIT AFTER FRESH MIGRATION (INR)
    // =========================================================================
    @Test
    @DisplayName("TEST 2: First deposit after fresh migration succeeds without manual SQL and maintains double-entry in INR")
    void test02_firstDepositAfterFreshMigration() throws Exception {
        BigDecimal depositAmount = new BigDecimal("250.0000");
        DepositRequestDto request = new DepositRequestDto(
                aliceAccount.getId(),
                depositAmount,
                "INR",
                "First user deposit"
        );

        // Perform deposit via API
        String responseJson = mockMvc.perform(post("/api/v1/deposits")
                        .with(user("alice.sys@ledger.com"))
                        .header("Idempotency-Key", "first-dep-test-02")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("COMPLETED")))
                .andExpect(jsonPath("$.transactionId", notNullValue()))
                .andExpect(jsonPath("$.currency", is("INR")))
                .andExpect(jsonPath("$.amount", is(250.0)))
                .andReturn().getResponse().getContentAsString();

        TransactionResponseDto response = objectMapper.readValue(responseJson, TransactionResponseDto.class);

        // Verify user balance and clearing balance update correctly
        Account updatedAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        Account updatedClearing = accountRepository.findById(SystemFundingService.SYSTEM_CLEARING_ACCOUNT_ID).orElseThrow();

        assertThat(updatedAlice.getBalance()).isEqualByComparingTo(depositAmount);
        assertThat(updatedClearing.getBalance()).isEqualByComparingTo(
                SystemFundingService.DEFAULT_BOOTSTRAP_AMOUNT.subtract(depositAmount)
        );

        // Verify transaction record is COMPLETED
        Transaction dbTx = transactionRepository.findById(response.transactionId()).orElseThrow();
        assertThat(dbTx.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(dbTx.getTransactionType()).isEqualTo(TransactionType.DEPOSIT);
        assertThat(dbTx.getCurrency()).isEqualTo("INR");
        assertThat(dbTx.getSourceAccount().getId()).isEqualTo(SystemFundingService.SYSTEM_CLEARING_ACCOUNT_ID);
        assertThat(dbTx.getDestinationAccount().getId()).isEqualTo(aliceAccount.getId());

        // Verify exactly two ledger entries are created (debit clearing, credit user)
        List<LedgerEntry> entries = ledgerEntryRepository.findByTransactionId(response.transactionId());
        assertThat(entries).hasSize(2);

        LedgerEntry clearingEntry = entries.stream()
                .filter(e -> e.getAccount().getId().equals(SystemFundingService.SYSTEM_CLEARING_ACCOUNT_ID))
                .findFirst().orElseThrow();
        assertThat(clearingEntry.getEntryType()).isEqualTo(LedgerEntryType.DEBIT);
        assertThat(clearingEntry.getCurrency()).isEqualTo("INR");
        assertThat(clearingEntry.getAmount()).isEqualByComparingTo(depositAmount);

        LedgerEntry userEntry = entries.stream()
                .filter(e -> e.getAccount().getId().equals(aliceAccount.getId()))
                .findFirst().orElseThrow();
        assertThat(userEntry.getEntryType()).isEqualTo(LedgerEntryType.CREDIT);
        assertThat(userEntry.getCurrency()).isEqualTo("INR");
        assertThat(userEntry.getAmount()).isEqualByComparingTo(depositAmount);

        // Verify debit amount == credit amount
        assertThat(clearingEntry.getAmount()).isEqualByComparingTo(userEntry.getAmount());

        // Verify reconciliation passes for both user and clearing
        executeAsUser("alice.sys@ledger.com", () -> {
            ReconciliationResultDto userRecon = reconciliationService.reconcileAccount(aliceAccount.getId());
            assertThat(userRecon.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
            assertThat(userRecon.difference()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(userRecon.snapshotBalance()).isEqualByComparingTo(depositAmount);
            assertThat(userRecon.ledgerBalance()).isEqualByComparingTo(depositAmount);
            return null;
        });

        ReconciliationResultDto clearingRecon = reconciliationService.reconcileAccountDirectly(SystemFundingService.SYSTEM_CLEARING_ACCOUNT_ID);
        BigDecimal expectedClearingBalance = SystemFundingService.DEFAULT_BOOTSTRAP_AMOUNT.subtract(depositAmount);
        assertThat(clearingRecon.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
        assertThat(clearingRecon.difference()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(clearingRecon.snapshotBalance()).isEqualByComparingTo(expectedClearingBalance);
        assertThat(clearingRecon.ledgerBalance()).isEqualByComparingTo(expectedClearingBalance);
    }

    // =========================================================================
    // TEST 3: BOOTSTRAP LEDGER INTEGRITY (INR)
    // =========================================================================
    @Test
    @DisplayName("TEST 3: Bootstrap funding transaction is balanced in INR and protected by ledger immutability trigger")
    void test03_bootstrapLedgerIntegrity() {
        Transaction bootstrapTx = transactionRepository.findById(SystemFundingService.BOOTSTRAP_TRANSACTION_ID).orElseThrow();

        assertThat(bootstrapTx.getTransactionType()).isEqualTo(TransactionType.SYSTEM_FUNDING);
        assertThat(bootstrapTx.getCurrency()).isEqualTo("INR");
        assertThat(bootstrapTx.getStatus()).isEqualTo(TransactionStatus.COMPLETED);

        List<LedgerEntry> entries = ledgerEntryRepository.findByTransactionId(bootstrapTx.getId());
        assertThat(entries).hasSize(2);

        BigDecimal totalDebits = entries.stream()
                .filter(e -> e.getEntryType() == LedgerEntryType.DEBIT)
                .map(LedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalCredits = entries.stream()
                .filter(e -> e.getEntryType() == LedgerEntryType.CREDIT)
                .map(LedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(totalDebits).isEqualByComparingTo(SystemFundingService.DEFAULT_BOOTSTRAP_AMOUNT);
        assertThat(totalCredits).isEqualByComparingTo(SystemFundingService.DEFAULT_BOOTSTRAP_AMOUNT);
        assertThat(totalDebits).isEqualByComparingTo(totalCredits);

        entries.forEach(e -> assertThat(e.getCurrency()).isEqualTo("INR"));

        // Verify ledger immutability trigger blocks UPDATE on bootstrap ledger entries
        UUID debitEntryId = entries.get(0).getId();
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE ledger_entries SET amount = 999.0000 WHERE id = ?",
                debitEntryId
        )).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("Ledger entries are immutable");

        // Verify ledger immutability trigger blocks DELETE on bootstrap ledger entries
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM ledger_entries WHERE id = ?",
                debitEntryId
        )).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("Ledger entries are immutable");
    }

    // =========================================================================
    // TEST 4: RECONCILIATION AFTER DEPOSIT
    // =========================================================================
    @Test
    @DisplayName("TEST 4: Reconciliation passes with zero discrepancy for user, clearing, and treasury after deposit")
    void test04_reconciliationAfterDeposit() {
        BigDecimal depositAmount = new BigDecimal("500.0000");
        DepositRequestDto request = new DepositRequestDto(aliceAccount.getId(), depositAmount, "INR");

        executeAsUser("alice.sys@ledger.com", () -> depositService.executeDeposit("recon-dep-004", request));

        // 1. Reconcile user account
        executeAsUser("alice.sys@ledger.com", () -> {
            ReconciliationResultDto userRecon = reconciliationService.reconcileAccount(aliceAccount.getId());
            assertThat(userRecon.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
            assertThat(userRecon.difference()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(userRecon.snapshotBalance()).isEqualByComparingTo(depositAmount);
            assertThat(userRecon.ledgerBalance()).isEqualByComparingTo(depositAmount);
            return null;
        });

        // 2. Reconcile clearing account
        BigDecimal expectedClearingBalance = SystemFundingService.DEFAULT_BOOTSTRAP_AMOUNT.subtract(depositAmount);
        ReconciliationResultDto clearingRecon = reconciliationService.reconcileAccountDirectly(SystemFundingService.SYSTEM_CLEARING_ACCOUNT_ID);
        assertThat(clearingRecon.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
        assertThat(clearingRecon.difference()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(clearingRecon.snapshotBalance()).isEqualByComparingTo(expectedClearingBalance);
        assertThat(clearingRecon.ledgerBalance()).isEqualByComparingTo(expectedClearingBalance);

        // 3. Reconcile treasury account
        BigDecimal expectedTreasuryBalance = SystemFundingService.DEFAULT_BOOTSTRAP_AMOUNT.negate();
        ReconciliationResultDto treasuryRecon = reconciliationService.reconcileAccountDirectly(SystemFundingService.SYSTEM_TREASURY_ACCOUNT_ID);
        assertThat(treasuryRecon.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
        assertThat(treasuryRecon.difference()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(treasuryRecon.snapshotBalance()).isEqualByComparingTo(expectedTreasuryBalance);
        assertThat(treasuryRecon.ledgerBalance()).isEqualByComparingTo(expectedTreasuryBalance);

        // 4. Net sum across all accounts in the system is exactly zero
        BigDecimal totalSystemNet = userReconSnapshot()
                .add(clearingRecon.snapshotBalance())
                .add(treasuryRecon.snapshotBalance());
        assertThat(totalSystemNet).isEqualByComparingTo(BigDecimal.ZERO);
    }

    private BigDecimal userReconSnapshot() {
        return accountRepository.findById(aliceAccount.getId()).orElseThrow().getBalance();
    }

    // =========================================================================
    // TEST 5: WITHDRAWAL AFTER BOOTSTRAP
    // =========================================================================
    @Test
    @DisplayName("TEST 5: Withdrawal after bootstrap updates balances, maintains symmetry, and reconciles in INR")
    void test05_withdrawalAfterBootstrap() {
        // First deposit 500.0000 into user account
        BigDecimal depositAmount = new BigDecimal("500.0000");
        DepositRequestDto depReq = new DepositRequestDto(aliceAccount.getId(), depositAmount, "INR");
        executeAsUser("alice.sys@ledger.com", () -> depositService.executeDeposit("wdr-prep-dep-005", depReq));

        // Withdraw 200.0000 from user account
        BigDecimal withdrawalAmount = new BigDecimal("200.0000");
        WithdrawalRequestDto wdrReq = new WithdrawalRequestDto(aliceAccount.getId(), withdrawalAmount, "INR", "ATM Withdrawal");
        TransactionResponseDto wdrResponse = executeAsUser("alice.sys@ledger.com",
                () -> withdrawalService.executeWithdrawal("wdr-boot-005", wdrReq));

        assertThat(wdrResponse.status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(wdrResponse.transactionType()).isEqualTo(TransactionType.WITHDRAWAL);
        assertThat(wdrResponse.currency()).isEqualTo("INR");

        // Balances: Alice = 300.0000, Clearing = 10,000,000 - 500 + 200 = 9,999,700.0000
        BigDecimal expectedAliceBalance = new BigDecimal("300.0000");
        BigDecimal expectedClearingBalance = SystemFundingService.DEFAULT_BOOTSTRAP_AMOUNT
                .subtract(depositAmount)
                .add(withdrawalAmount);

        Account checkAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        Account checkClearing = accountRepository.findById(SystemFundingService.SYSTEM_CLEARING_ACCOUNT_ID).orElseThrow();
        assertThat(checkAlice.getBalance()).isEqualByComparingTo(expectedAliceBalance);
        assertThat(checkClearing.getBalance()).isEqualByComparingTo(expectedClearingBalance);

        // Ledger entries: DEBIT user, CREDIT clearing
        List<LedgerEntry> entries = ledgerEntryRepository.findByTransactionId(wdrResponse.transactionId());
        assertThat(entries).hasSize(2);

        LedgerEntry userEntry = entries.stream()
                .filter(e -> e.getAccount().getId().equals(aliceAccount.getId()))
                .findFirst().orElseThrow();
        assertThat(userEntry.getEntryType()).isEqualTo(LedgerEntryType.DEBIT);
        assertThat(userEntry.getCurrency()).isEqualTo("INR");
        assertThat(userEntry.getAmount()).isEqualByComparingTo(withdrawalAmount);

        LedgerEntry clearingEntry = entries.stream()
                .filter(e -> e.getAccount().getId().equals(SystemFundingService.SYSTEM_CLEARING_ACCOUNT_ID))
                .findFirst().orElseThrow();
        assertThat(clearingEntry.getEntryType()).isEqualTo(LedgerEntryType.CREDIT);
        assertThat(clearingEntry.getCurrency()).isEqualTo("INR");
        assertThat(clearingEntry.getAmount()).isEqualByComparingTo(withdrawalAmount);

        // Double-entry symmetry
        assertThat(userEntry.getAmount()).isEqualByComparingTo(clearingEntry.getAmount());

        // Reconciliation passes for both
        executeAsUser("alice.sys@ledger.com", () -> {
            ReconciliationResultDto userRecon = reconciliationService.reconcileAccount(aliceAccount.getId());
            assertThat(userRecon.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
            assertThat(userRecon.difference()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(userRecon.snapshotBalance()).isEqualByComparingTo(expectedAliceBalance);
            assertThat(userRecon.ledgerBalance()).isEqualByComparingTo(expectedAliceBalance);
            return null;
        });

        ReconciliationResultDto clearingRecon = reconciliationService.reconcileAccountDirectly(SystemFundingService.SYSTEM_CLEARING_ACCOUNT_ID);
        assertThat(clearingRecon.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
        assertThat(clearingRecon.difference()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(clearingRecon.snapshotBalance()).isEqualByComparingTo(expectedClearingBalance);
        assertThat(clearingRecon.ledgerBalance()).isEqualByComparingTo(expectedClearingBalance);
    }

    // =========================================================================
    // TEST 6: EXISTING TRANSFER REGRESSION (INR)
    // =========================================================================
    @Test
    @DisplayName("TEST 6: Transfer between user checking accounts works unchanged in INR and does not affect SYSTEM_CLEARING")
    void test06_existingTransferRegression() {
        // Fund Alice ($500.0000) and Bob ($300.0000)
        executeAsUser("alice.sys@ledger.com", () -> depositService.executeDeposit(
                "tf-dep-alice",
                new DepositRequestDto(aliceAccount.getId(), new BigDecimal("500.0000"), "INR")
        ));
        executeAsUser("bob.sys@ledger.com", () -> depositService.executeDeposit(
                "tf-dep-bob",
                new DepositRequestDto(bobAccount.getId(), new BigDecimal("300.0000"), "INR")
        ));

        Account clearingBefore = accountRepository.findById(SystemFundingService.SYSTEM_CLEARING_ACCOUNT_ID).orElseThrow();
        BigDecimal clearingBalanceBefore = clearingBefore.getBalance();
        long clearingLedgerCountBefore = ledgerEntryRepository.findByAccountId(SystemFundingService.SYSTEM_CLEARING_ACCOUNT_ID).size();

        // Transfer 150.0000 from Alice to Bob
        BigDecimal transferAmount = new BigDecimal("150.0000");
        TransferRequestDto transferReq = new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                transferAmount,
                "INR",
                "Rent split"
        );

        TransferResponseDto transferResponse = executeAsUser("alice.sys@ledger.com",
                () -> transferService.executeTransfer("transfer-reg-006", transferReq));

        assertThat(transferResponse.status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(transferResponse.transactionType()).isEqualTo(TransactionType.TRANSFER);
        assertThat(transferResponse.currency()).isEqualTo("INR");

        // Alice: 500 - 150 = 350; Bob: 300 + 150 = 450
        Account aliceAfter = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        Account bobAfter = accountRepository.findById(bobAccount.getId()).orElseThrow();
        assertThat(aliceAfter.getBalance()).isEqualByComparingTo(new BigDecimal("350.0000"));
        assertThat(bobAfter.getBalance()).isEqualByComparingTo(new BigDecimal("450.0000"));

        // SYSTEM_CLEARING is completely unaffected
        Account clearingAfter = accountRepository.findById(SystemFundingService.SYSTEM_CLEARING_ACCOUNT_ID).orElseThrow();
        assertThat(clearingAfter.getBalance()).isEqualByComparingTo(clearingBalanceBefore);
        long clearingLedgerCountAfter = ledgerEntryRepository.findByAccountId(SystemFundingService.SYSTEM_CLEARING_ACCOUNT_ID).size();
        assertThat(clearingLedgerCountAfter).isEqualTo(clearingLedgerCountBefore);

        // Double-entry symmetry on transfer
        List<LedgerEntry> transferEntries = ledgerEntryRepository.findByTransactionId(transferResponse.transactionId());
        assertThat(transferEntries).hasSize(2);

        LedgerEntry debitEntry = transferEntries.stream()
                .filter(e -> e.getAccount().getId().equals(aliceAccount.getId()))
                .findFirst().orElseThrow();
        assertThat(debitEntry.getEntryType()).isEqualTo(LedgerEntryType.DEBIT);
        assertThat(debitEntry.getCurrency()).isEqualTo("INR");
        assertThat(debitEntry.getAmount()).isEqualByComparingTo(transferAmount);

        LedgerEntry creditEntry = transferEntries.stream()
                .filter(e -> e.getAccount().getId().equals(bobAccount.getId()))
                .findFirst().orElseThrow();
        assertThat(creditEntry.getEntryType()).isEqualTo(LedgerEntryType.CREDIT);
        assertThat(creditEntry.getCurrency()).isEqualTo("INR");
        assertThat(creditEntry.getAmount()).isEqualByComparingTo(transferAmount);

        // Both accounts reconcile cleanly
        executeAsUser("alice.sys@ledger.com", () -> {
            ReconciliationResultDto r = reconciliationService.reconcileAccount(aliceAccount.getId());
            assertThat(r.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
            assertThat(r.difference()).isEqualByComparingTo(BigDecimal.ZERO);
            return null;
        });

        executeAsUser("bob.sys@ledger.com", () -> {
            ReconciliationResultDto r = reconciliationService.reconcileAccount(bobAccount.getId());
            assertThat(r.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
            assertThat(r.difference()).isEqualByComparingTo(BigDecimal.ZERO);
            return null;
        });
    }

    // =========================================================================
    // TEST 7: EXISTING ACCOUNT LIFECYCLE REGRESSION
    // =========================================================================
    @Test
    @DisplayName("TEST 7: User checking account lifecycle transitions work and system clearing cannot be altered")
    void test07_existingAccountLifecycleRegression() {
        UUID aliceAccId = aliceAccount.getId();

        // 1. ACTIVE -> FROZEN by admin
        executeAsAdmin("admin.sys@ledger.com", () -> accountService.freezeAccount(aliceAccId));
        Account frozen = accountRepository.findById(aliceAccId).orElseThrow();
        assertThat(frozen.getStatus()).isEqualTo(AccountStatus.FROZEN);

        // Cannot deposit into FROZEN account
        DepositRequestDto depReq = new DepositRequestDto(aliceAccId, new BigDecimal("100.0000"), "INR");
        assertThatThrownBy(() -> executeAsUser("alice.sys@ledger.com",
                () -> depositService.executeDeposit("frozen-dep-fail", depReq)))
                .isInstanceOf(AccountFrozenException.class);

        // Cannot withdraw from FROZEN account
        WithdrawalRequestDto wdrReq = new WithdrawalRequestDto(aliceAccId, new BigDecimal("50.0000"), "INR");
        assertThatThrownBy(() -> executeAsUser("alice.sys@ledger.com",
                () -> withdrawalService.executeWithdrawal("frozen-wdr-fail", wdrReq)))
                .isInstanceOf(AccountFrozenException.class);

        // 2. FROZEN -> ACTIVE by admin
        executeAsAdmin("admin.sys@ledger.com", () -> accountService.unfreezeAccount(aliceAccId));
        Account unfrozen = accountRepository.findById(aliceAccId).orElseThrow();
        assertThat(unfrozen.getStatus()).isEqualTo(AccountStatus.ACTIVE);

        // 3. ACTIVE -> CLOSED by owner (balance is 0)
        executeAsUser("alice.sys@ledger.com", () -> accountService.closeAccount(aliceAccId));
        Account closed = accountRepository.findById(aliceAccId).orElseThrow();
        assertThat(closed.getStatus()).isEqualTo(AccountStatus.CLOSED);

        // Cannot deposit into CLOSED account
        assertThatThrownBy(() -> executeAsUser("alice.sys@ledger.com",
                () -> depositService.executeDeposit("closed-dep-fail", depReq)))
                .isInstanceOf(AccountClosedException.class);

        // Cannot withdraw from CLOSED account
        assertThatThrownBy(() -> executeAsUser("alice.sys@ledger.com",
                () -> withdrawalService.executeWithdrawal("closed-wdr-fail", wdrReq)))
                .isInstanceOf(AccountClosedException.class);

        // 4. SYSTEM_CLEARING cannot be closed or frozen via AccountService
        assertThatThrownBy(() -> executeAsAdmin("admin.sys@ledger.com",
                () -> accountService.freezeAccount(SystemFundingService.SYSTEM_CLEARING_ACCOUNT_ID)))
                .isInstanceOf(InvalidAccountTypeException.class);

        assertThatThrownBy(() -> executeAsUser("alice.sys@ledger.com",
                () -> accountService.closeAccount(SystemFundingService.SYSTEM_CLEARING_ACCOUNT_ID)))
                .isInstanceOf(AccountNotFoundException.class);

        // 5. Database trigger prevents changing SYSTEM_CLEARING or SYSTEM_TREASURY status
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE accounts SET status = 'FROZEN' WHERE id = ?",
                SystemFundingService.SYSTEM_CLEARING_ACCOUNT_ID
        )).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("cannot transition away from ACTIVE");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE accounts SET status = 'CLOSED' WHERE id = ?",
                SystemFundingService.SYSTEM_TREASURY_ACCOUNT_ID
        )).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("cannot transition away from ACTIVE");
    }

    // =========================================================================
    // TEST 8: EXISTING IDEMPOTENCY REGRESSION (INR)
    // =========================================================================
    @Test
    @DisplayName("TEST 8: Idempotent deposit retries return identical result with single balance and ledger mutation in INR")
    void test08_existingIdempotencyRegression() {
        BigDecimal depositAmount = new BigDecimal("150.0000");
        DepositRequestDto request = new DepositRequestDto(
                aliceAccount.getId(),
                depositAmount,
                "INR",
                "Idempotent deposit test"
        );

        String idempotencyKey = "idem-reg-008";

        // First attempt
        TransactionResponseDto first = executeAsUser("alice.sys@ledger.com",
                () -> depositService.executeDeposit(idempotencyKey, request));

        assertThat(first.status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(first.transactionId()).isNotNull();
        assertThat(first.currency()).isEqualTo("INR");

        // Second attempt with exact same key and parameters
        TransactionResponseDto second = executeAsUser("alice.sys@ledger.com",
                () -> depositService.executeDeposit(idempotencyKey, request));

        // Returns same transaction ID
        assertThat(second.transactionId()).isEqualTo(first.transactionId());
        assertThat(second.status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(second.currency()).isEqualTo("INR");
        assertThat(second.amount()).isEqualByComparingTo(first.amount());

        // Balances mutate only once
        Account aliceAfter = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        Account clearingAfter = accountRepository.findById(SystemFundingService.SYSTEM_CLEARING_ACCOUNT_ID).orElseThrow();

        assertThat(aliceAfter.getBalance()).isEqualByComparingTo(depositAmount);
        assertThat(clearingAfter.getBalance()).isEqualByComparingTo(
                SystemFundingService.DEFAULT_BOOTSTRAP_AMOUNT.subtract(depositAmount)
        );

        // Exactly one DEPOSIT transaction row exists
        List<Transaction> deposits = transactionRepository.findByTransactionType(TransactionType.DEPOSIT);
        assertThat(deposits).hasSize(1);
        assertThat(deposits.get(0).getId()).isEqualTo(first.transactionId());
        assertThat(deposits.get(0).getCurrency()).isEqualTo("INR");

        // Exactly one CREDIT entry for Alice exists
        List<LedgerEntry> aliceEntries = ledgerEntryRepository.findByAccountId(aliceAccount.getId());
        assertThat(aliceEntries).hasSize(1);
        assertThat(aliceEntries.get(0).getAmount()).isEqualByComparingTo(depositAmount);
        assertThat(aliceEntries.get(0).getEntryType()).isEqualTo(LedgerEntryType.CREDIT);
        assertThat(aliceEntries.get(0).getCurrency()).isEqualTo("INR");
    }

    // =========================================================================
    // INR-ONLY SPECIFIC VALIDATION AND ENFORCEMENT TESTS
    // =========================================================================

    @Test
    @DisplayName("INR-1: Creating INR account succeeds")
    void test09_creatingInrAccountSucceeds() throws Exception {
        CreateAccountRequestDto request = new CreateAccountRequestDto("INR");

        mockMvc.perform(post("/api/v1/accounts")
                        .with(user("alice.sys@ledger.com"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currency", is("INR")))
                .andExpect(jsonPath("$.status", is("ACTIVE")))
                .andExpect(jsonPath("$.accountType", is("USER_CHECKING")));
    }

    @Test
    @DisplayName("INR-2: Creating USD account is rejected at application level (HTTP 400)")
    void test10_creatingUsdAccountRejected() throws Exception {
        CreateAccountRequestDto request = new CreateAccountRequestDto("USD");

        mockMvc.perform(post("/api/v1/accounts")
                        .with(user("alice.sys@ledger.com"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Only INR currency is supported")));
    }

    @Test
    @DisplayName("INR-3: Creating EUR account is rejected at application level (HTTP 400)")
    void test11_creatingEurAccountRejected() throws Exception {
        CreateAccountRequestDto request = new CreateAccountRequestDto("EUR");

        mockMvc.perform(post("/api/v1/accounts")
                        .with(user("alice.sys@ledger.com"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Only INR currency is supported")));
    }

    @Test
    @DisplayName("INR-4: Deposit with INR succeeds")
    void test12_depositWithInrSucceeds() throws Exception {
        DepositRequestDto request = new DepositRequestDto(aliceAccount.getId(), new BigDecimal("100.0000"), "INR");

        mockMvc.perform(post("/api/v1/deposits")
                        .with(user("alice.sys@ledger.com"))
                        .header("Idempotency-Key", "dep-inr-ok")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currency", is("INR")))
                .andExpect(jsonPath("$.status", is("COMPLETED")));
    }

    @Test
    @DisplayName("INR-5: Deposit with USD is rejected at application level (HTTP 400)")
    void test13_depositWithUsdRejected() throws Exception {
        DepositRequestDto request = new DepositRequestDto(aliceAccount.getId(), new BigDecimal("100.0000"), "USD");

        mockMvc.perform(post("/api/v1/deposits")
                        .with(user("alice.sys@ledger.com"))
                        .header("Idempotency-Key", "dep-usd-fail")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Only INR currency is supported")));
    }

    @Test
    @DisplayName("INR-6: Withdrawal with INR succeeds")
    void test14_withdrawalWithInrSucceeds() throws Exception {
        // First deposit 200 INR
        executeAsUser("alice.sys@ledger.com", () -> depositService.executeDeposit(
                "wdr-fund-01",
                new DepositRequestDto(aliceAccount.getId(), new BigDecimal("200.0000"), "INR")
        ));

        WithdrawalRequestDto request = new WithdrawalRequestDto(aliceAccount.getId(), new BigDecimal("100.0000"), "INR");

        mockMvc.perform(post("/api/v1/withdrawals")
                        .with(user("alice.sys@ledger.com"))
                        .header("Idempotency-Key", "wdr-inr-ok")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currency", is("INR")))
                .andExpect(jsonPath("$.status", is("COMPLETED")));
    }

    @Test
    @DisplayName("INR-7: Withdrawal with USD is rejected at application level (HTTP 400)")
    void test15_withdrawalWithUsdRejected() throws Exception {
        WithdrawalRequestDto request = new WithdrawalRequestDto(aliceAccount.getId(), new BigDecimal("50.0000"), "USD");

        mockMvc.perform(post("/api/v1/withdrawals")
                        .with(user("alice.sys@ledger.com"))
                        .header("Idempotency-Key", "wdr-usd-fail")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Only INR currency is supported")));
    }

    @Test
    @DisplayName("INR-8: Transfer between INR accounts succeeds")
    void test16_transferBetweenInrAccountsSucceeds() throws Exception {
        executeAsUser("alice.sys@ledger.com", () -> depositService.executeDeposit(
                "tf-fund-01",
                new DepositRequestDto(aliceAccount.getId(), new BigDecimal("200.0000"), "INR")
        ));

        TransferRequestDto request = new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("75.0000"),
                "INR",
                "INR transfer"
        );

        mockMvc.perform(post("/api/v1/transfers")
                        .with(user("alice.sys@ledger.com"))
                        .header("Idempotency-Key", "tf-inr-ok")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency", is("INR")))
                .andExpect(jsonPath("$.status", is("COMPLETED")));
    }

    @Test
    @DisplayName("INR-9: Database CHECK constraints reject non-INR currency on accounts, transactions, and ledger entries")
    void test17_databaseConstraintsRejectNonInr() {
        // 1. Accounts constraint
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO accounts (id, user_id, currency, balance, version, created_at, updated_at, account_type, status, account_number) " +
                        "VALUES (?, ?, 'USD', 0, 0, NOW(), NOW(), 'USER_CHECKING', 'ACTIVE', ?)",
                UUID.randomUUID(), aliceUser.getId(), "ACCT-DB-USD"
        )).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_accounts_currency_inr");

        // 2. Transactions constraint
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO transactions (id, idempotency_key, amount, currency, status, transaction_type, source_account_id, destination_account_id, initiated_by_user_id, created_at) " +
                        "VALUES (?, ?, 10.0000, 'USD', 'COMPLETED', 'TRANSFER', ?, ?, ?, NOW())",
                UUID.randomUUID(), "key-db-usd-tx", aliceAccount.getId(), bobAccount.getId(), aliceUser.getId()
        )).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_transactions_currency_inr");

        // 3. Ledger entries constraint
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO ledger_entries (id, transaction_id, account_id, entry_type, amount, currency, created_at) " +
                        "VALUES (?, ?, ?, 'CREDIT', 10.0000, 'USD', NOW())",
                UUID.randomUUID(), SystemFundingService.BOOTSTRAP_TRANSACTION_ID, aliceAccount.getId()
        )).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_ledger_entries_currency_inr");
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================

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
}
