package com.parth.ledger.transaction;

import com.parth.ledger.BaseIntegrationTest;
import com.parth.ledger.account.Account;
import com.parth.ledger.account.AccountClosedException;
import com.parth.ledger.account.AccountFrozenException;
import com.parth.ledger.account.AccountRepository;
import com.parth.ledger.account.AccountStatus;
import com.parth.ledger.account.AccountType;
import com.parth.ledger.audit.AuditEvent;
import com.parth.ledger.audit.AuditEventRepository;
import com.parth.ledger.audit.AuditEventType;
import com.parth.ledger.deposit.dto.DepositRequestDto;
import com.parth.ledger.deposit.service.DepositService;
import com.parth.ledger.idempotency.IdempotencyCacheService;
import com.parth.ledger.ledger.LedgerEntry;
import com.parth.ledger.ledger.LedgerEntryRepository;
import com.parth.ledger.ledger.LedgerEntryType;
import com.parth.ledger.reconciliation.ReconciliationStatus;
import com.parth.ledger.reconciliation.dto.ReconciliationResultDto;
import com.parth.ledger.reconciliation.service.ReconciliationService;
import com.parth.ledger.security.AccountOwnershipException;
import com.parth.ledger.system.SystemFundingService;
import com.parth.ledger.transaction.dto.ReversalRequestDto;
import com.parth.ledger.transaction.dto.ReversalResponseDto;
import com.parth.ledger.transaction.dto.TransactionResponseDto;
import com.parth.ledger.transaction.dto.TransferRequestDto;
import com.parth.ledger.transaction.dto.TransferResponseDto;
import com.parth.ledger.transaction.exception.IdempotencyConflictException;
import com.parth.ledger.transaction.exception.InsufficientBalanceException;
import com.parth.ledger.transaction.exception.TransactionAlreadyReversedException;
import com.parth.ledger.transaction.exception.TransactionNotFoundException;
import com.parth.ledger.transaction.exception.TransactionNotReversibleException;
import com.parth.ledger.transaction.service.TransactionReversalService;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

class TransactionReversalIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TransactionReversalService reversalService;

    @Autowired
    private TransferService transferService;

    @Autowired
    private DepositService depositService;

    @Autowired
    private WithdrawalService withdrawalService;

    @Autowired
    private ReconciliationService reconciliationService;

    @Autowired
    private SystemFundingService systemFundingService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @MockitoSpyBean
    private LedgerEntryRepository ledgerEntryRepository;

    @MockitoSpyBean
    private IdempotencyCacheService idempotencyCacheService;

    private User aliceUser;
    private User bobUser;
    private User charlieUser;
    private User adminUser;

    private Account aliceAccount;
    private Account bobAccount;
    private Account clearingAccount;

    private static final BigDecimal INITIAL_CLEARING_BALANCE = new BigDecimal("100000.0000");

    @BeforeEach
    void setUp() {
        clearRedis();
        if (jdbcTemplate != null) {
            jdbcTemplate.execute("TRUNCATE TABLE audit_events CASCADE");
            jdbcTemplate.execute("TRUNCATE TABLE ledger_entries CASCADE");
            jdbcTemplate.execute("TRUNCATE TABLE transactions CASCADE");
            jdbcTemplate.execute("TRUNCATE TABLE accounts CASCADE");
            jdbcTemplate.execute("TRUNCATE TABLE users CASCADE");
        }

        reset(ledgerEntryRepository);
        reset(idempotencyCacheService);

        aliceUser = userRepository.save(new User("alice.rev@ledger.com", "Alice"));
        bobUser = userRepository.save(new User("bob.rev@ledger.com", "Bob"));
        charlieUser = userRepository.save(new User("charlie.rev@ledger.com", "Charlie"));
        adminUser = userRepository.save(new User("admin.rev@ledger.com", "Admin"));

        aliceAccount = accountRepository.save(new Account(aliceUser, "INR", BigDecimal.ZERO.setScale(4), AccountType.USER_CHECKING, AccountStatus.ACTIVE, "ACCT-ALICE-REV"));
        bobAccount = accountRepository.save(new Account(bobUser, "INR", BigDecimal.ZERO.setScale(4), AccountType.USER_CHECKING, AccountStatus.ACTIVE, "ACCT-BOB-REV"));

        clearingAccount = systemFundingService.bootstrapSystemFunding(INITIAL_CLEARING_BALANCE);

        // Fund Alice with 10,000 via standard deposit backed by double-entry ledger entries
        executeAsUser(aliceUser.getEmail(), () ->
                depositService.executeDeposit("alice-setup-funding", new DepositRequestDto(
                        aliceAccount.getId(),
                        new BigDecimal("10000.0000"),
                        "INR",
                        "Initial Alice Funding"
                ))
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        if (jdbcTemplate != null) {
            jdbcTemplate.execute("TRUNCATE TABLE audit_events CASCADE");
            jdbcTemplate.execute("TRUNCATE TABLE ledger_entries CASCADE");
            jdbcTemplate.execute("TRUNCATE TABLE transactions CASCADE");
            jdbcTemplate.execute("TRUNCATE TABLE accounts CASCADE");
            jdbcTemplate.execute("TRUNCATE TABLE users CASCADE");
        }
    }

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
                new UsernamePasswordAuthenticationToken(
                        email,
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
                )
        );
        try {
            return action.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    // =========================================================================
    // 1. TRANSFER REVERSAL
    // =========================================================================

    @Test
    @DisplayName("Should successfully reverse transfer with compensating ledger entries and preserved reconciliation")
    void shouldSuccessfullyReverseTransferWithCompensatingLedgerEntriesAndReconciliation() {
        // Step 1: Alice transfers 5,000 to Bob
        TransferResponseDto transferResponse = executeAsUser(aliceUser.getEmail(), () ->
                transferService.executeTransfer("transfer-key-1", new TransferRequestDto(
                        aliceAccount.getId(),
                        bobAccount.getId(),
                        new BigDecimal("5000.0000"),
                        "INR",
                        "Original transfer from Alice to Bob"
                ))
        );

        UUID originalTxId = transferResponse.transactionId();

        // Verify balances after transfer
        Account aliceAfterTransfer = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        Account bobAfterTransfer = accountRepository.findById(bobAccount.getId()).orElseThrow();
        assertThat(aliceAfterTransfer.getBalance()).isEqualByComparingTo("5000.0000");
        assertThat(bobAfterTransfer.getBalance()).isEqualByComparingTo("5000.0000");

        // Step 2: Bob (owner of the debited account) authorizes the reversal
        ReversalResponseDto reversalResponse = executeAsUser(bobUser.getEmail(), () ->
                reversalService.executeReversal(originalTxId, "reversal-key-1", new ReversalRequestDto("Duplicate transfer error"))
        );

        // Verify reversal response DTO (display-safe fields only, internal account UUIDs omitted)
        assertThat(reversalResponse).isNotNull();
        assertThat(reversalResponse.originalTransactionId()).isEqualTo(originalTxId);
        assertThat(reversalResponse.transactionType()).isEqualTo(TransactionType.REVERSAL);
        assertThat(reversalResponse.status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(reversalResponse.amount()).isEqualByComparingTo("5000.0000");
        assertThat(reversalResponse.currency()).isEqualTo("INR");

        // Verify balances after reversal: Alice back to 10,000, Bob back to 0
        Account aliceAfterReversal = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        Account bobAfterReversal = accountRepository.findById(bobAccount.getId()).orElseThrow();
        assertThat(aliceAfterReversal.getBalance()).isEqualByComparingTo("10000.0000");
        assertThat(bobAfterReversal.getBalance()).isEqualByComparingTo("0.0000");

        // Verify original transaction remains completely unchanged in database
        Transaction originalTx = transactionRepository.findById(originalTxId).orElseThrow();
        assertThat(originalTx.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(originalTx.getTransactionType()).isEqualTo(TransactionType.TRANSFER);
        assertThat(originalTx.getAmount()).isEqualByComparingTo("5000.0000");

        // Verify reversal transaction in database
        Transaction reversalTx = transactionRepository.findById(reversalResponse.reversalTransactionId()).orElseThrow();
        assertThat(reversalTx.getTransactionType()).isEqualTo(TransactionType.REVERSAL);
        assertThat(reversalTx.getReversesTransactionId()).isEqualTo(originalTxId);
        assertThat(reversalTx.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(reversalTx.getSourceAccount().getId()).isEqualTo(bobAccount.getId()); // Debited Bob
        assertThat(reversalTx.getDestinationAccount().getId()).isEqualTo(aliceAccount.getId()); // Credited Alice

        // Verify ledger entries for reversal
        List<LedgerEntry> reversalEntries = ledgerEntryRepository.findByTransactionId(reversalTx.getId());
        assertThat(reversalEntries).hasSize(2);

        LedgerEntry bobDebit = reversalEntries.stream()
                .filter(e -> e.getAccount().getId().equals(bobAccount.getId()))
                .findFirst().orElseThrow();
        assertThat(bobDebit.getEntryType()).isEqualTo(LedgerEntryType.DEBIT);
        assertThat(bobDebit.getAmount()).isEqualByComparingTo("5000.0000");

        LedgerEntry aliceCredit = reversalEntries.stream()
                .filter(e -> e.getAccount().getId().equals(aliceAccount.getId()))
                .findFirst().orElseThrow();
        assertThat(aliceCredit.getEntryType()).isEqualTo(LedgerEntryType.CREDIT);
        assertThat(aliceCredit.getAmount()).isEqualByComparingTo("5000.0000");

        // Verify total debits == total credits
        assertThat(bobDebit.getAmount()).isEqualTo(aliceCredit.getAmount());

        // Verify reconciliation for both accounts remains CONSISTENT
        ReconciliationResultDto aliceRecon = executeAsUser(aliceUser.getEmail(), () ->
                reconciliationService.reconcileAccount(aliceAccount.getId())
        );
        assertThat(aliceRecon.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
        assertThat(aliceRecon.difference()).isEqualByComparingTo("0.0000");
        assertThat(aliceRecon.snapshotBalance()).isEqualByComparingTo("10000.0000");
        assertThat(aliceRecon.ledgerBalance()).isEqualByComparingTo("10000.0000");

        ReconciliationResultDto bobRecon = executeAsUser(bobUser.getEmail(), () ->
                reconciliationService.reconcileAccount(bobAccount.getId())
        );
        assertThat(bobRecon.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
        assertThat(bobRecon.difference()).isEqualByComparingTo("0.0000");
        assertThat(bobRecon.snapshotBalance()).isEqualByComparingTo("0.0000");
        assertThat(bobRecon.ledgerBalance()).isEqualByComparingTo("0.0000");

        // Verify operational audit trail contains TRANSACTION_REVERSED
        List<AuditEvent> auditEvents = auditEventRepository.findByEntityTypeAndEntityId(
                com.parth.ledger.audit.AuditEntityType.TRANSACTION,
                originalTxId
        );
        assertThat(auditEvents).hasSize(2); // TRANSFER_COMPLETED then TRANSACTION_REVERSED

        assertThat(auditEvents).anySatisfy(event -> {
            assertThat(event.getEventType()).isEqualTo(AuditEventType.TRANSACTION_REVERSED);
            assertThat(event.getActorUserId()).isEqualTo(bobUser.getId());
            assertThat(event.getMetadata()).containsEntry("reversalTransactionId", reversalTx.getId().toString())
                    .containsEntry("originalTransactionId", originalTxId.toString())
                    .containsEntry("amount", 5000.0);
        });
    }

    @Test
    @DisplayName("Unauthorized counterparty cannot cause an unauthorized debit from recipient account")
    void shouldRejectUnauthorizedCounterpartyAttemptingToReverseTransfer() {
        // Step 1: Alice transfers 3,000 to Bob
        TransferResponseDto transferResponse = executeAsUser(aliceUser.getEmail(), () ->
                transferService.executeTransfer("transfer-key-unauth-cparty", new TransferRequestDto(
                        aliceAccount.getId(),
                        bobAccount.getId(),
                        new BigDecimal("3000.0000"),
                        "INR",
                        "Transfer from Alice to Bob"
                ))
        );

        UUID originalTxId = transferResponse.transactionId();

        // Step 2: Alice (the sender) attempts to reverse the transfer, which would cause an unauthorized debit on Bob.
        assertThatThrownBy(() ->
                executeAsUser(aliceUser.getEmail(), () ->
                        reversalService.executeReversal(originalTxId, "rev-alice-unauth-key", null)
                )
        ).isInstanceOf(AccountOwnershipException.class)
                .hasMessageContaining("not own the account debited by this reversal");

        // Balances must remain unchanged: Alice 7,000, Bob 3,000
        Account alice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        Account bob = accountRepository.findById(bobAccount.getId()).orElseThrow();
        assertThat(alice.getBalance()).isEqualByComparingTo("7000.0000");
        assertThat(bob.getBalance()).isEqualByComparingTo("3000.0000");

        // No reversal transaction created
        assertThat(transactionRepository.findByTransactionType(TransactionType.REVERSAL)).isEmpty();
    }

    // =========================================================================
    // 2. DEPOSIT REVERSAL
    // =========================================================================

    @Test
    @DisplayName("Should successfully reverse deposit with system clearing account balance restored")
    void shouldSuccessfullyReverseDepositWithClearingAccountBalanceRestored() {
        BigDecimal initialClearing = accountRepository.findById(clearingAccount.getId()).orElseThrow().getBalance();

        // Alice deposits 3,000
        TransactionResponseDto depositResponse = executeAsUser(aliceUser.getEmail(), () ->
                depositService.executeDeposit("deposit-key-1", new DepositRequestDto(
                        aliceAccount.getId(),
                        new BigDecimal("3000.0000"),
                        "INR",
                        "Initial deposit"
                ))
        );

        UUID depositTxId = depositResponse.transactionId();

        Account aliceAfterDeposit = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        Account clearingAfterDeposit = accountRepository.findById(clearingAccount.getId()).orElseThrow();

        assertThat(aliceAfterDeposit.getBalance()).isEqualByComparingTo("13000.0000");
        assertThat(clearingAfterDeposit.getBalance()).isEqualByComparingTo(initialClearing.subtract(new BigDecimal("3000.0000")));

        // Reverse the deposit
        ReversalResponseDto reversal = executeAsUser(aliceUser.getEmail(), () ->
                reversalService.executeReversal(depositTxId, "rev-deposit-1", new ReversalRequestDto("Deposit reversal"))
        );

        assertThat(reversal.originalTransactionId()).isEqualTo(depositTxId);
        Transaction reversalTx = transactionRepository.findById(reversal.reversalTransactionId()).orElseThrow();
        assertThat(reversalTx.getSourceAccount().getId()).isEqualTo(aliceAccount.getId()); // Debited Alice
        assertThat(reversalTx.getDestinationAccount().getId()).isEqualTo(clearingAccount.getId()); // Credited clearing

        Account aliceRestored = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        Account clearingRestored = accountRepository.findById(clearingAccount.getId()).orElseThrow();

        assertThat(aliceRestored.getBalance()).isEqualByComparingTo("10000.0000");
        assertThat(clearingRestored.getBalance()).isEqualByComparingTo(initialClearing);

        // Reconciliation
        ReconciliationResultDto aliceRecon = executeAsUser(aliceUser.getEmail(), () ->
                reconciliationService.reconcileAccount(aliceAccount.getId())
        );
        assertThat(aliceRecon.status()).isEqualTo(ReconciliationStatus.CONSISTENT);

        ReconciliationResultDto clearingRecon = reconciliationService.reconcileAccountDirectly(clearingAccount.getId());
        assertThat(cleconConsistent(clearingRecon)).isTrue();
    }

    private boolean cleconConsistent(ReconciliationResultDto r) {
        return r.status() == ReconciliationStatus.CONSISTENT;
    }

    // =========================================================================
    // 3. WITHDRAWAL REVERSAL
    // =========================================================================

    @Test
    @DisplayName("Should successfully reverse withdrawal with clearing account compensated")
    void shouldSuccessfullyReverseWithdrawalWithClearingAccountCompensated() {
        BigDecimal initialClearing = accountRepository.findById(clearingAccount.getId()).orElseThrow().getBalance();

        // Alice withdraws 4,000
        TransactionResponseDto withdrawalResponse = executeAsUser(aliceUser.getEmail(), () ->
                withdrawalService.executeWithdrawal("wdr-key-1", new WithdrawalRequestDto(
                        aliceAccount.getId(),
                        new BigDecimal("4000.0000"),
                        "INR",
                        "Cash withdrawal"
                ))
        );

        UUID withdrawalTxId = withdrawalResponse.transactionId();

        Account aliceAfterWdr = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        Account clearingAfterWdr = accountRepository.findById(clearingAccount.getId()).orElseThrow();

        assertThat(aliceAfterWdr.getBalance()).isEqualByComparingTo("6000.0000");
        assertThat(clearingAfterWdr.getBalance()).isEqualByComparingTo(initialClearing.add(new BigDecimal("4000.0000")));

        // Reverse the withdrawal
        ReversalResponseDto reversal = executeAsUser(aliceUser.getEmail(), () ->
                reversalService.executeReversal(withdrawalTxId, "rev-wdr-1", new ReversalRequestDto("Withdrawal failed at ATM"))
        );

        assertThat(reversal.originalTransactionId()).isEqualTo(withdrawalTxId);
        Transaction reversalWdrTx = transactionRepository.findById(reversal.reversalTransactionId()).orElseThrow();
        assertThat(reversalWdrTx.getSourceAccount().getId()).isEqualTo(clearingAccount.getId()); // Debited clearing
        assertThat(reversalWdrTx.getDestinationAccount().getId()).isEqualTo(aliceAccount.getId()); // Credited Alice

        Account aliceRestored = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        Account clearingRestored = accountRepository.findById(clearingAccount.getId()).orElseThrow();

        assertThat(aliceRestored.getBalance()).isEqualByComparingTo("10000.0000");
        assertThat(clearingRestored.getBalance()).isEqualByComparingTo(initialClearing);

        // Reconciliation
        ReconciliationResultDto aliceRecon = executeAsUser(aliceUser.getEmail(), () ->
                reconciliationService.reconcileAccount(aliceAccount.getId())
        );
        assertThat(aliceRecon.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
    }

    // =========================================================================
    // 4. IDEMPOTENCY & DUPLICATE REVERSALS
    // =========================================================================

    @Test
    @DisplayName("Same idempotency key replay returns existing reversal without duplicate processing")
    void shouldReplayIdempotentlyWithSameKey() {
        TransferResponseDto transfer = executeAsUser(aliceUser.getEmail(), () ->
                transferService.executeTransfer("transfer-key-idem", new TransferRequestDto(
                        aliceAccount.getId(),
                        bobAccount.getId(),
                        new BigDecimal("2000.0000"),
                        "INR",
                        "Idempotency transfer"
                ))
        );

        UUID origTxId = transfer.transactionId();

        // First reversal call
        ReversalResponseDto firstResponse = executeAsUser(bobUser.getEmail(), () ->
                reversalService.executeReversal(origTxId, "rev-idem-key-1", new ReversalRequestDto("Duplicate"))
        );

        // Second reversal call with SAME key
        ReversalResponseDto replayResponse = executeAsUser(bobUser.getEmail(), () ->
                reversalService.executeReversal(origTxId, "rev-idem-key-1", new ReversalRequestDto("Duplicate"))
        );

        assertThat(replayResponse.reversalTransactionId()).isEqualTo(firstResponse.reversalTransactionId());

        // Balances changed exactly once!
        Account alice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        Account bob = accountRepository.findById(bobAccount.getId()).orElseThrow();
        assertThat(alice.getBalance()).isEqualByComparingTo("10000.0000");
        assertThat(bob.getBalance()).isEqualByComparingTo("0.0000");

        // Exactly one reversal transaction exists in DB
        List<Transaction> reversals = transactionRepository.findByTransactionType(TransactionType.REVERSAL);
        assertThat(reversals).hasSize(1);
    }

    @Test
    @DisplayName("Different idempotency key duplicate reversal attempt is rejected with 409 Conflict")
    void shouldRejectDuplicateReversalWithDifferentKey() {
        TransferResponseDto transfer = executeAsUser(aliceUser.getEmail(), () ->
                transferService.executeTransfer("transfer-key-dup", new TransferRequestDto(
                        aliceAccount.getId(),
                        bobAccount.getId(),
                        new BigDecimal("1000.0000"),
                        "INR",
                        "Transfer for duplicate test"
                ))
        );

        UUID origTxId = transfer.transactionId();

        // First reversal succeeds
        executeAsUser(bobUser.getEmail(), () ->
                reversalService.executeReversal(origTxId, "rev-dup-key-A", null)
        );

        // Second reversal with DIFFERENT key must fail
        assertThatThrownBy(() ->
                executeAsUser(bobUser.getEmail(), () ->
                        reversalService.executeReversal(origTxId, "rev-dup-key-B", null)
                )
        ).isInstanceOf(TransactionAlreadyReversedException.class)
                .hasMessageContaining("already been reversed");

        // Balances must not be altered a second time
        Account alice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        Account bob = accountRepository.findById(bobAccount.getId()).orElseThrow();
        assertThat(alice.getBalance()).isEqualByComparingTo("10000.0000");
        assertThat(bob.getBalance()).isEqualByComparingTo("0.0000");
    }

    // =========================================================================
    // 5. BALANCE SAFETY & ACCOUNT LIFECYCLE
    // =========================================================================

    @Test
    @DisplayName("Should reject reversal if debited account has insufficient balance to compensate")
    void shouldRejectReversalWhenDebitedAccountHasInsufficientBalance() {
        // Alice transfers 5,000 to Bob
        TransferResponseDto transfer = executeAsUser(aliceUser.getEmail(), () ->
                transferService.executeTransfer("transfer-key-insuf", new TransferRequestDto(
                        aliceAccount.getId(),
                        bobAccount.getId(),
                        new BigDecimal("5000.0000"),
                        "INR",
                        "Transfer"
                ))
        );

        // Bob withdraws 4,000. Bob now has 1,000.
        executeAsUser(bobUser.getEmail(), () ->
                withdrawalService.executeWithdrawal("bob-wdr-1", new WithdrawalRequestDto(
                        bobAccount.getId(),
                        new BigDecimal("4000.0000"),
                        "INR",
                        "Withdrawal"
                ))
        );

        Account bobCurrent = accountRepository.findById(bobAccount.getId()).orElseThrow();
        assertThat(bobCurrent.getBalance()).isEqualByComparingTo("1000.0000");

        // Bob attempts to reverse the original 5,000 transfer.
        // Bob must be debited 5,000, but only has 1,000. Must reject!
        assertThatThrownBy(() ->
                executeAsUser(bobUser.getEmail(), () ->
                        reversalService.executeReversal(transfer.transactionId(), "rev-insuf-key", null)
                )
        ).isInstanceOf(InsufficientBalanceException.class)
                .hasMessageContaining("Insufficient balance");

        // Verify Bob's balance stays 1,000 and Alice stays 5,000
        Account bobFinal = accountRepository.findById(bobAccount.getId()).orElseThrow();
        Account aliceFinal = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        assertThat(bobFinal.getBalance()).isEqualByComparingTo("1000.0000");
        assertThat(aliceFinal.getBalance()).isEqualByComparingTo("5000.0000");

        // No reversal transaction created
        assertThat(transactionRepository.findByTransactionType(TransactionType.REVERSAL)).isEmpty();
    }

    @Test
    @DisplayName("Should reject reversal when debited account is FROZEN")
    void shouldRejectReversalWhenAccountIsFrozen() {
        TransferResponseDto transfer = executeAsUser(aliceUser.getEmail(), () ->
                transferService.executeTransfer("transfer-key-frz", new TransferRequestDto(
                        aliceAccount.getId(),
                        bobAccount.getId(),
                        new BigDecimal("1000.0000"),
                        "INR",
                        "Transfer"
                ))
        );

        // Freeze Bob's account
        Account bobToFreeze = accountRepository.findById(bobAccount.getId()).orElseThrow();
        bobToFreeze.setStatus(AccountStatus.FROZEN);
        accountRepository.save(bobToFreeze);

        assertThatThrownBy(() ->
                executeAsUser(bobUser.getEmail(), () ->
                        reversalService.executeReversal(transfer.transactionId(), "rev-frz-key", null)
                )
        ).isInstanceOf(AccountFrozenException.class)
                .hasMessageContaining("FROZEN");
    }

    @Test
    @DisplayName("Should reject reversal when credited account is CLOSED")
    void shouldRejectReversalWhenAccountIsClosed() {
        TransferResponseDto transfer = executeAsUser(aliceUser.getEmail(), () ->
                transferService.executeTransfer("transfer-key-cls", new TransferRequestDto(
                        aliceAccount.getId(),
                        bobAccount.getId(),
                        new BigDecimal("10000.0000"),
                        "INR",
                        "Transfer"
                ))
        );

        // Alice now has 0.0000 balance, satisfying chk_accounts_closed_zero_balance
        Account aliceToClose = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        assertThat(aliceToClose.getBalance()).isEqualByComparingTo("0.0000");
        aliceToClose.setStatus(AccountStatus.CLOSED);
        accountRepository.save(aliceToClose);

        assertThatThrownBy(() ->
                executeAsUser(bobUser.getEmail(), () ->
                        reversalService.executeReversal(transfer.transactionId(), "rev-cls-key", null)
                )
        ).isInstanceOf(AccountClosedException.class)
                .hasMessageContaining("CLOSED");
    }

    // =========================================================================
    // 6. ELIGIBILITY RULES
    // =========================================================================

    @Test
    @DisplayName("Should reject reversal of a reversal transaction")
    void shouldRejectReversalOfReversal() {
        TransferResponseDto transfer = executeAsUser(aliceUser.getEmail(), () ->
                transferService.executeTransfer("transfer-key-revofrev", new TransferRequestDto(
                        aliceAccount.getId(),
                        bobAccount.getId(),
                        new BigDecimal("1000.0000"),
                        "INR",
                        "Transfer"
                ))
        );

        ReversalResponseDto reversal = executeAsUser(bobUser.getEmail(), () ->
                reversalService.executeReversal(transfer.transactionId(), "rev-first-key", null)
        );

        // Alice owns the account debited in reversing this reversal; attempts to reverse the reversal
        assertThatThrownBy(() ->
                executeAsUser(aliceUser.getEmail(), () ->
                        reversalService.executeReversal(reversal.reversalTransactionId(), "rev-second-key", null)
                )
        ).isInstanceOf(TransactionNotReversibleException.class)
                .hasMessageContaining("reversal transaction cannot be reversed");
    }

    @Test
    @DisplayName("Should reject reversal of system funding")
    void shouldRejectReversalOfSystemFunding() {
        UUID fundingTxId = SystemFundingService.BOOTSTRAP_TRANSACTION_ID;

        assertThatThrownBy(() ->
                executeAsAdmin(adminUser.getEmail(), () ->
                        reversalService.executeReversal(fundingTxId, "rev-funding-key", null)
                )
        ).isInstanceOf(TransactionNotReversibleException.class)
                .hasMessageContaining("System funding transactions cannot be reversed");
    }

    // =========================================================================
    // 7. AUTHORIZATION & ANTI-ENUMERATION
    // =========================================================================

    @Test
    @DisplayName("Anti-enumeration: Should return 404 TransactionNotFoundException when unrelated user attempts reversal")
    void shouldRejectReversalByUnauthorizedUser() {
        TransferResponseDto transfer = executeAsUser(aliceUser.getEmail(), () ->
                transferService.executeTransfer("transfer-key-auth", new TransferRequestDto(
                        aliceAccount.getId(),
                        bobAccount.getId(),
                        new BigDecimal("1000.0000"),
                        "INR",
                        "Transfer"
                ))
        );

        // Charlie (unrelated third party) tries to reverse Alice's transfer -> 404 anti-enumeration
        assertThatThrownBy(() ->
                executeAsUser(charlieUser.getEmail(), () ->
                        reversalService.executeReversal(transfer.transactionId(), "rev-charlie-key", null)
                )
        ).isInstanceOf(TransactionNotFoundException.class)
                .hasMessageContaining("Transaction not found");
    }

    @Test
    @DisplayName("Anti-enumeration: Unauthorized user querying nonexistent, completed, already-reversed, or non-reversible transaction receives identical 404")
    void shouldEnforceAntiEnumerationAcrossAllTransactionStatesForUnauthorizedUser() {
        TransferResponseDto transfer = executeAsUser(aliceUser.getEmail(), () ->
                transferService.executeTransfer("transfer-probe-test", new TransferRequestDto(
                        aliceAccount.getId(),
                        bobAccount.getId(),
                        new BigDecimal("1000.0000"),
                        "INR",
                        "Transfer"
                ))
        );
        UUID completedTxId = transfer.transactionId();

        // 1. Unauthorized user queries nonexistent transaction -> 404
        UUID nonExistentId = UUID.randomUUID();
        assertThatThrownBy(() ->
                executeAsUser(charlieUser.getEmail(), () ->
                        reversalService.executeReversal(nonExistentId, "probe-nonexistent", null)
                )
        ).isInstanceOf(TransactionNotFoundException.class)
                .hasMessageContaining("Transaction not found");

        // 2. Unauthorized user queries completed transaction -> 404
        assertThatThrownBy(() ->
                executeAsUser(charlieUser.getEmail(), () ->
                        reversalService.executeReversal(completedTxId, "probe-completed", null)
                )
        ).isInstanceOf(TransactionNotFoundException.class)
                .hasMessageContaining("Transaction not found");

        // 3. Unauthorized user queries already-reversed transaction -> 404
        executeAsUser(bobUser.getEmail(), () ->
                reversalService.executeReversal(completedTxId, "bob-valid-reversal", null)
        );

        assertThatThrownBy(() ->
                executeAsUser(charlieUser.getEmail(), () ->
                        reversalService.executeReversal(completedTxId, "probe-reversed", null)
                )
        ).isInstanceOf(TransactionNotFoundException.class)
                .hasMessageContaining("Transaction not found");

        // 4. Unauthorized user queries non-reversible transaction (system funding) -> 404
        assertThatThrownBy(() ->
                executeAsUser(charlieUser.getEmail(), () ->
                        reversalService.executeReversal(SystemFundingService.BOOTSTRAP_TRANSACTION_ID, "probe-funding", null)
                )
        ).isInstanceOf(TransactionNotFoundException.class)
                .hasMessageContaining("Transaction not found");
    }

    @Test
    @DisplayName("Should allow admin to reverse transaction across platform")
    void shouldAllowAdminToReverseAnyTransaction() {
        TransferResponseDto transfer = executeAsUser(aliceUser.getEmail(), () ->
                transferService.executeTransfer("transfer-key-admin", new TransferRequestDto(
                        aliceAccount.getId(),
                        bobAccount.getId(),
                        new BigDecimal("1000.0000"),
                        "INR",
                        "Transfer"
                ))
        );

        ReversalResponseDto reversal = executeAsAdmin(adminUser.getEmail(), () ->
                reversalService.executeReversal(transfer.transactionId(), "rev-admin-key", new ReversalRequestDto("Admin compliance reversal"))
        );

        assertThat(reversal).isNotNull();
        assertThat(reversal.originalTransactionId()).isEqualTo(transfer.transactionId());
        assertThat(reversal.status()).isEqualTo(TransactionStatus.COMPLETED);
    }

    // =========================================================================
    // 8. CROSS-ORIGINAL IDEMPOTENCY SAFETY
    // =========================================================================

    @Test
    @DisplayName("Same idempotency key with different original transactions returns clean 409 conflict without cross-transaction confusion")
    void shouldRejectWhenSameIdempotencyKeyUsedForDifferentOriginalTransactions() {
        TransferResponseDto tx1 = executeAsUser(aliceUser.getEmail(), () ->
                transferService.executeTransfer("transfer-tx1", new TransferRequestDto(
                        aliceAccount.getId(),
                        bobAccount.getId(),
                        new BigDecimal("1000.0000"),
                        "INR",
                        "Transfer 1"
                ))
        );

        TransferResponseDto tx2 = executeAsUser(aliceUser.getEmail(), () ->
                transferService.executeTransfer("transfer-tx2", new TransferRequestDto(
                        aliceAccount.getId(),
                        bobAccount.getId(),
                        new BigDecimal("2000.0000"),
                        "INR",
                        "Transfer 2"
                ))
        );

        // Bob reverses TX1 with key K
        ReversalResponseDto rev1 = executeAsUser(bobUser.getEmail(), () ->
                reversalService.executeReversal(tx1.transactionId(), "shared-idempotency-key", null)
        );
        assertThat(rev1).isNotNull();
        assertThat(rev1.originalTransactionId()).isEqualTo(tx1.transactionId());

        // Bob attempts to reverse TX2 with the SAME key K
        assertThatThrownBy(() ->
                executeAsUser(bobUser.getEmail(), () ->
                        reversalService.executeReversal(tx2.transactionId(), "shared-idempotency-key", null)
                )
        ).isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("already used for a different transaction");

        // Verify TX2 was NOT reversed
        Transaction tx2Db = transactionRepository.findById(tx2.transactionId()).orElseThrow();
        assertThat(tx2Db.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(transactionRepository.findByReversesTransactionId(tx2.transactionId())).isEmpty();

        // Exactly one reversal exists (for TX1)
        List<Transaction> reversals = transactionRepository.findByTransactionType(TransactionType.REVERSAL);
        assertThat(reversals).hasSize(1);
        assertThat(reversals.get(0).getReversesTransactionId()).isEqualTo(tx1.transactionId());
    }

    @Test
    @DisplayName("Concurrent reversal requests with same idempotency key for different original transactions results in clean conflict without cross-transaction confusion")
    void shouldHandleConcurrentCrossOriginalIdempotencyKeyCollision() throws InterruptedException {
        TransferResponseDto tx1 = executeAsUser(aliceUser.getEmail(), () ->
                transferService.executeTransfer("transfer-race-tx1", new TransferRequestDto(
                        aliceAccount.getId(),
                        bobAccount.getId(),
                        new BigDecimal("1000.0000"),
                        "INR",
                        "Transfer 1"
                ))
        );

        TransferResponseDto tx2 = executeAsUser(aliceUser.getEmail(), () ->
                transferService.executeTransfer("transfer-race-tx2", new TransferRequestDto(
                        aliceAccount.getId(),
                        bobAccount.getId(),
                        new BigDecimal("2000.0000"),
                        "INR",
                        "Transfer 2"
                ))
        );

        String sharedKey = "concurrent-cross-original-key";
        int threads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);
        List<UUID> completedReversalOrigIds = Collections.synchronizedList(new ArrayList<>());

        executor.submit(() -> {
            try {
                startLatch.await();
                ReversalResponseDto res = executeAsUser(bobUser.getEmail(), () ->
                        reversalService.executeReversal(tx1.transactionId(), sharedKey, null)
                );
                successCount.incrementAndGet();
                completedReversalOrigIds.add(res.originalTransactionId());
            } catch (Exception e) {
                if (e instanceof IdempotencyConflictException || (e.getMessage() != null && e.getMessage().contains("idempotency"))) {
                    conflictCount.incrementAndGet();
                }
            } finally {
                doneLatch.countDown();
            }
        });

        executor.submit(() -> {
            try {
                startLatch.await();
                ReversalResponseDto res = executeAsUser(bobUser.getEmail(), () ->
                        reversalService.executeReversal(tx2.transactionId(), sharedKey, null)
                );
                successCount.incrementAndGet();
                completedReversalOrigIds.add(res.originalTransactionId());
            } catch (Exception e) {
                if (e instanceof IdempotencyConflictException || (e.getMessage() != null && e.getMessage().contains("idempotency"))) {
                    conflictCount.incrementAndGet();
                }
            } finally {
                doneLatch.countDown();
            }
        });

        startLatch.countDown();
        boolean finished = doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(finished).isTrue();
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(conflictCount.get()).isEqualTo(1);

        // Exactly one reversal transaction in database with sharedKey
        Optional<Transaction> dbTx = transactionRepository.findByIdempotencyKey(sharedKey);
        assertThat(dbTx).isPresent();
        assertThat(completedReversalOrigIds).containsExactly(dbTx.get().getReversesTransactionId());
    }

    // =========================================================================
    // 9. ATOMIC ROLLBACK
    // =========================================================================

    @Test
    @DisplayName("Should atomically rollback entire transaction when staged operation fails")
    void shouldAtomicallyRollbackWhenStagedOperationFails() {
        TransferResponseDto transfer = executeAsUser(aliceUser.getEmail(), () ->
                transferService.executeTransfer("transfer-key-rollback", new TransferRequestDto(
                        aliceAccount.getId(),
                        bobAccount.getId(),
                        new BigDecimal("3000.0000"),
                        "INR",
                        "Transfer"
                ))
        );

        Account alicePreReversal = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        Account bobPreReversal = accountRepository.findById(bobAccount.getId()).orElseThrow();

        // Introduce simulated failure when saving ledger entries (after account balances mutated and tx created)
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("Simulated catastrophic disk / database failure during reversal ledger write"))
                .when(ledgerEntryRepository).save(any(LedgerEntry.class));

        assertThatThrownBy(() ->
                executeAsUser(bobUser.getEmail(), () ->
                        reversalService.executeReversal(transfer.transactionId(), "rev-rollback-key", null)
                )
        ).isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class)
                .hasMessageContaining("Simulated catastrophic disk / database failure");

        // Reset spy to allow subsequent database operations and assertions
        reset(ledgerEntryRepository);

        // Verify atomicity:
        // 1. Account balances unchanged
        Account alicePostRollback = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        Account bobPostRollback = accountRepository.findById(bobAccount.getId()).orElseThrow();
        assertThat(alicePostRollback.getBalance()).isEqualByComparingTo(alicePreReversal.getBalance());
        assertThat(bobPostRollback.getBalance()).isEqualByComparingTo(bobPreReversal.getBalance());

        // 2. No reversal transaction in database
        assertThat(transactionRepository.findByTransactionType(TransactionType.REVERSAL)).isEmpty();

        // 3. No TRANSACTION_REVERSED audit event in database
        List<AuditEvent> reversalAudits = auditEventRepository.findByEventType(AuditEventType.TRANSACTION_REVERSED);
        assertThat(reversalAudits).isEmpty();
    }

    // =========================================================================
    // 10. CONCURRENT REVERSAL RACE
    // =========================================================================

    @Test
    @DisplayName("Multiple concurrent reversal requests on same transaction result in exactly one success")
    void shouldHandleConcurrentReversalsSafelyWithSingleWinner() throws InterruptedException {
        TransferResponseDto transfer = executeAsUser(aliceUser.getEmail(), () ->
                transferService.executeTransfer("transfer-key-race", new TransferRequestDto(
                        aliceAccount.getId(),
                        bobAccount.getId(),
                        new BigDecimal("2000.0000"),
                        "INR",
                        "Transfer for race test"
                ))
        );

        UUID origTxId = transfer.transactionId();
        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);
        Set<UUID> reversalIds = ConcurrentHashMap.newKeySet();

        for (int i = 0; i < threads; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    ReversalResponseDto res = executeAsUser(bobUser.getEmail(), () ->
                            reversalService.executeReversal(origTxId, "concurrent-key-" + index, new ReversalRequestDto("Race " + index))
                    );
                    successCount.incrementAndGet();
                    reversalIds.add(res.reversalTransactionId());
                } catch (TransactionAlreadyReversedException e) {
                    conflictCount.incrementAndGet();
                } catch (Exception e) {
                    // Check if cause is DataIntegrityViolation
                    if (e.getMessage() != null && e.getMessage().contains("already been reversed")) {
                        conflictCount.incrementAndGet();
                    }
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(conflictCount.get()).isEqualTo(threads - 1);
        assertThat(reversalIds).hasSize(1);

        // Balances inverted exactly once!
        Account alice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        Account bob = accountRepository.findById(bobAccount.getId()).orElseThrow();
        assertThat(alice.getBalance()).isEqualByComparingTo("10000.0000");
        assertThat(bob.getBalance()).isEqualByComparingTo("0.0000");

        // Exactly one reversal transaction in database
        List<Transaction> dbReversals = transactionRepository.findByTransactionType(TransactionType.REVERSAL);
        assertThat(dbReversals).hasSize(1);

        // Exactly one TRANSACTION_REVERSED audit event
        List<AuditEvent> auditEvents = auditEventRepository.findByEventType(AuditEventType.TRANSACTION_REVERSED);
        assertThat(auditEvents).hasSize(1);
    }
}
