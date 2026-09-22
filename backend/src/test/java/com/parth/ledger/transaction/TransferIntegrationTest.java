package com.parth.ledger.transaction;

import com.parth.ledger.BaseIntegrationTest;
import com.parth.ledger.account.Account;
import com.parth.ledger.account.AccountNotFoundException;
import com.parth.ledger.account.AccountRepository;
import com.parth.ledger.ledger.LedgerEntry;
import com.parth.ledger.ledger.LedgerEntryRepository;
import com.parth.ledger.ledger.LedgerEntryType;
import com.parth.ledger.transaction.dto.TransferRequestDto;
import com.parth.ledger.transaction.dto.TransferResponseDto;
import com.parth.ledger.transaction.exception.CurrencyMismatchException;
import com.parth.ledger.transaction.exception.IdempotencyConflictException;
import com.parth.ledger.transaction.exception.InsufficientBalanceException;
import com.parth.ledger.transaction.exception.InvalidAmountException;
import com.parth.ledger.transaction.exception.SameAccountTransferException;
import com.parth.ledger.transaction.service.TransferService;
import com.parth.ledger.user.User;
import com.parth.ledger.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;

class TransferIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TransferService transferService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @MockitoSpyBean
    private LedgerEntryRepository ledgerEntryRepository;

    private User aliceUser;
    private User bobUser;
    private User charlieUser;

    private Account aliceUsdAccount;
    private Account bobUsdAccount;
    private Account charlieEurAccount;

    @BeforeEach
    void setUp() {
        ledgerEntryRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();

        aliceUser = userRepository.save(new User("alice@ledger.com", "Alice"));
        bobUser = userRepository.save(new User("bob@ledger.com", "Bob"));
        charlieUser = userRepository.save(new User("charlie@ledger.com", "Charlie"));

        aliceUsdAccount = accountRepository.save(new Account(aliceUser, "USD", new BigDecimal("1000.0000")));
        bobUsdAccount = accountRepository.save(new Account(bobUser, "USD", new BigDecimal("500.0000")));
        charlieEurAccount = accountRepository.save(new Account(charlieUser, "EUR", new BigDecimal("300.0000")));
    }

    @Test
    @DisplayName("Basic successful transfer: debits source, credits destination, balances ledger, records COMPLETED transaction")
    void verifyBasicSuccessfulTransfer() {
        String idempotencyKey = "tx-basic-success-001";
        BigDecimal transferAmount = new BigDecimal("100.0000");

        TransferRequestDto request = new TransferRequestDto(
                aliceUsdAccount.getId(),
                bobUsdAccount.getId(),
                transferAmount,
                "USD"
        );

        TransferResponseDto response = transferService.executeTransfer(idempotencyKey, request);

        assertThat(response).isNotNull();
        assertThat(response.transactionId()).isNotNull();
        assertThat(response.status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(response.sourceAccountId()).isEqualTo(aliceUsdAccount.getId());
        assertThat(response.destinationAccountId()).isEqualTo(bobUsdAccount.getId());
        assertThat(response.amount()).isEqualByComparingTo(transferAmount);
        assertThat(response.currency()).isEqualTo("USD");
        assertThat(response.createdAt()).isNotNull();
        assertThat(response.completedAt()).isNotNull();

        // Verify account balance snapshots
        Account updatedAlice = accountRepository.findById(aliceUsdAccount.getId()).orElseThrow();
        Account updatedBob = accountRepository.findById(bobUsdAccount.getId()).orElseThrow();
        assertThat(updatedAlice.getBalance()).isEqualByComparingTo(new BigDecimal("900.0000"));
        assertThat(updatedBob.getBalance()).isEqualByComparingTo(new BigDecimal("600.0000"));

        // Verify ledger entries
        List<LedgerEntry> entries = ledgerEntryRepository.findByTransactionId(response.transactionId());
        assertThat(entries).hasSize(2);

        LedgerEntry debitEntry = entries.stream()
                .filter(e -> e.getEntryType() == LedgerEntryType.DEBIT)
                .findFirst().orElseThrow();
        assertThat(debitEntry.getAccount().getId()).isEqualTo(aliceUsdAccount.getId());
        assertThat(debitEntry.getAmount()).isEqualByComparingTo(transferAmount);

        LedgerEntry creditEntry = entries.stream()
                .filter(e -> e.getEntryType() == LedgerEntryType.CREDIT)
                .findFirst().orElseThrow();
        assertThat(creditEntry.getAccount().getId()).isEqualTo(bobUsdAccount.getId());
        assertThat(creditEntry.getAmount()).isEqualByComparingTo(transferAmount);

        // Verify double-entry balance invariant
        assertThat(debitEntry.getAmount()).isEqualByComparingTo(creditEntry.getAmount());

        // Verify transaction record in database
        Transaction dbTx = transactionRepository.findById(response.transactionId()).orElseThrow();
        assertThat(dbTx.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(dbTx.getIdempotencyKey()).isEqualTo(idempotencyKey);
        assertThat(dbTx.getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("Insufficient balance: rejects transfer, leaves balances unchanged, creates no transaction or ledger records")
    void verifyInsufficientBalanceRejection() {
        Account lowBalanceAccount = accountRepository.save(new Account(aliceUser, "USD", new BigDecimal("50.0000")));
        String idempotencyKey = "tx-insufficient-001";
        BigDecimal transferAmount = new BigDecimal("100.0000");

        TransferRequestDto request = new TransferRequestDto(
                lowBalanceAccount.getId(),
                bobUsdAccount.getId(),
                transferAmount,
                "USD"
        );

        assertThatThrownBy(() -> transferService.executeTransfer(idempotencyKey, request))
                .isInstanceOf(InsufficientBalanceException.class)
                .hasMessageContaining("Insufficient balance");

        // Verify balances unchanged
        Account checkAlice = accountRepository.findById(lowBalanceAccount.getId()).orElseThrow();
        Account checkBob = accountRepository.findById(bobUsdAccount.getId()).orElseThrow();
        assertThat(checkAlice.getBalance()).isEqualByComparingTo(new BigDecimal("50.0000"));
        assertThat(checkBob.getBalance()).isEqualByComparingTo(new BigDecimal("500.0000"));

        // Verify no transaction record
        assertThat(transactionRepository.findByIdempotencyKey(idempotencyKey)).isEmpty();
        assertThat(ledgerEntryRepository.count()).isZero();
    }

    @Test
    @DisplayName("Same account transfer: rejects request with SameAccountTransferException")
    void verifySameAccountTransferRejection() {
        TransferRequestDto request = new TransferRequestDto(
                aliceUsdAccount.getId(),
                aliceUsdAccount.getId(),
                new BigDecimal("50.0000"),
                "USD"
        );

        assertThatThrownBy(() -> transferService.executeTransfer("tx-same-account-001", request))
                .isInstanceOf(SameAccountTransferException.class)
                .hasMessageContaining("must be different");

        Account checkAlice = accountRepository.findById(aliceUsdAccount.getId()).orElseThrow();
        assertThat(checkAlice.getBalance()).isEqualByComparingTo(new BigDecimal("1000.0000"));
        assertThat(transactionRepository.count()).isZero();
        assertThat(ledgerEntryRepository.count()).isZero();
    }

    @Test
    @DisplayName("Currency mismatch: rejects transfer when accounts or request currencies differ")
    void verifyCurrencyMismatchRejection() {
        // Source USD, Destination EUR
        TransferRequestDto request1 = new TransferRequestDto(
                aliceUsdAccount.getId(),
                charlieEurAccount.getId(),
                new BigDecimal("50.0000"),
                "USD"
        );

        assertThatThrownBy(() -> transferService.executeTransfer("tx-currency-001", request1))
                .isInstanceOf(CurrencyMismatchException.class);

        // Source USD, Destination USD, but request specifies GBP
        TransferRequestDto request2 = new TransferRequestDto(
                aliceUsdAccount.getId(),
                bobUsdAccount.getId(),
                new BigDecimal("50.0000"),
                "GBP"
        );

        assertThatThrownBy(() -> transferService.executeTransfer("tx-currency-002", request2))
                .isInstanceOf(CurrencyMismatchException.class);

        // Verify balances unchanged
        assertThat(accountRepository.findById(aliceUsdAccount.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo(new BigDecimal("1000.0000"));
        assertThat(accountRepository.findById(charlieEurAccount.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo(new BigDecimal("300.0000"));
        assertThat(transactionRepository.count()).isZero();
    }

    @Test
    @DisplayName("Idempotent retry: repeated identical request does not transfer twice and returns existing transaction")
    void verifyIdempotentRetry() {
        String idempotencyKey = "tx-retry-001";
        BigDecimal transferAmount = new BigDecimal("100.0000");

        TransferRequestDto request = new TransferRequestDto(
                aliceUsdAccount.getId(),
                bobUsdAccount.getId(),
                transferAmount,
                "USD"
        );

        // First attempt
        TransferResponseDto firstResponse = transferService.executeTransfer(idempotencyKey, request);
        assertThat(firstResponse.status()).isEqualTo(TransactionStatus.COMPLETED);

        // Check balances after first attempt
        assertThat(accountRepository.findById(aliceUsdAccount.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo(new BigDecimal("900.0000"));
        assertThat(accountRepository.findById(bobUsdAccount.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo(new BigDecimal("600.0000"));

        // Second attempt (identical retry)
        TransferResponseDto secondResponse = transferService.executeTransfer(idempotencyKey, request);

        // Must return the exact same transaction ID
        assertThat(secondResponse.transactionId()).isEqualTo(firstResponse.transactionId());
        assertThat(secondResponse.status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(secondResponse.amount()).isEqualByComparingTo(transferAmount);

        // Balances MUST NOT change a second time
        assertThat(accountRepository.findById(aliceUsdAccount.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo(new BigDecimal("900.0000"));
        assertThat(accountRepository.findById(bobUsdAccount.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo(new BigDecimal("600.0000"));

        // Only one transaction in database
        assertThat(transactionRepository.count()).isEqualTo(1);

        // Only two ledger entries in database
        assertThat(ledgerEntryRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("Idempotency conflict: reusing idempotency key with different parameters is rejected")
    void verifyIdempotencyConflictRejection() {
        String idempotencyKey = "tx-conflict-001";

        TransferRequestDto request1 = new TransferRequestDto(
                aliceUsdAccount.getId(),
                bobUsdAccount.getId(),
                new BigDecimal("100.0000"),
                "USD"
        );
        transferService.executeTransfer(idempotencyKey, request1);

        // Reuse same key with different amount
        TransferRequestDto requestDiffAmount = new TransferRequestDto(
                aliceUsdAccount.getId(),
                bobUsdAccount.getId(),
                new BigDecimal("200.0000"),
                "USD"
        );
        assertThatThrownBy(() -> transferService.executeTransfer(idempotencyKey, requestDiffAmount))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("different parameters");

        // Reuse same key with different destination account
        Account danUsdAccount = accountRepository.save(new Account(charlieUser, "USD", new BigDecimal("100.0000")));
        TransferRequestDto requestDiffDest = new TransferRequestDto(
                aliceUsdAccount.getId(),
                danUsdAccount.getId(),
                new BigDecimal("100.0000"),
                "USD"
        );
        assertThatThrownBy(() -> transferService.executeTransfer(idempotencyKey, requestDiffDest))
                .isInstanceOf(IdempotencyConflictException.class);

        // Verify balances reflect ONLY the first transfer
        assertThat(accountRepository.findById(aliceUsdAccount.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo(new BigDecimal("900.0000"));
        assertThat(accountRepository.findById(bobUsdAccount.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo(new BigDecimal("600.0000"));
        assertThat(accountRepository.findById(danUsdAccount.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo(new BigDecimal("100.0000"));

        assertThat(transactionRepository.count()).isEqualTo(1);
        assertThat(ledgerEntryRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("Account not found: rejects transfer when account UUID does not exist")
    void verifyAccountNotFoundRejection() {
        UUID nonExistentId = UUID.randomUUID();
        TransferRequestDto request = new TransferRequestDto(
                nonExistentId,
                bobUsdAccount.getId(),
                new BigDecimal("100.0000"),
                "USD"
        );

        assertThatThrownBy(() -> transferService.executeTransfer("tx-not-found-001", request))
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessageContaining(nonExistentId.toString());
    }

    @Test
    @DisplayName("Invalid amount: rejects zero or negative transfer amount")
    void verifyInvalidAmountRejection() {
        TransferRequestDto zeroRequest = new TransferRequestDto(
                aliceUsdAccount.getId(),
                bobUsdAccount.getId(),
                BigDecimal.ZERO,
                "USD"
        );
        assertThatThrownBy(() -> transferService.executeTransfer("tx-invalid-amt-001", zeroRequest))
                .isInstanceOf(InvalidAmountException.class);

        TransferRequestDto negativeRequest = new TransferRequestDto(
                aliceUsdAccount.getId(),
                bobUsdAccount.getId(),
                new BigDecimal("-50.0000"),
                "USD"
        );
        assertThatThrownBy(() -> transferService.executeTransfer("tx-invalid-amt-002", negativeRequest))
                .isInstanceOf(InvalidAmountException.class);
    }

    @Test
    @DisplayName("Rollback test: mid-transaction failure rolls back account balances and leaves no partial state")
    void verifyRollbackOnMidTransactionFailure() {
        String idempotencyKey = "tx-rollback-001";
        BigDecimal transferAmount = new BigDecimal("100.0000");

        TransferRequestDto request = new TransferRequestDto(
                aliceUsdAccount.getId(),
                bobUsdAccount.getId(),
                transferAmount,
                "USD"
        );

        // Inject failure specifically when saving the CREDIT ledger entry
        // (after balances have been updated and DEBIT entry has been persisted in JPA)
        doThrow(new RuntimeException("Simulated mid-transaction failure"))
                .when(ledgerEntryRepository)
                .save(argThat(entry -> entry != null && entry.getEntryType() == LedgerEntryType.CREDIT));

        assertThatThrownBy(() -> transferService.executeTransfer(idempotencyKey, request))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Simulated mid-transaction failure");

        // Verify source balance remains untouched
        Account checkAlice = accountRepository.findById(aliceUsdAccount.getId()).orElseThrow();
        assertThat(checkAlice.getBalance()).isEqualByComparingTo(new BigDecimal("1000.0000"));

        // Verify destination balance remains untouched
        Account checkBob = accountRepository.findById(bobUsdAccount.getId()).orElseThrow();
        assertThat(checkBob.getBalance()).isEqualByComparingTo(new BigDecimal("500.0000"));

        // Verify no transaction record persisted
        assertThat(transactionRepository.findByIdempotencyKey(idempotencyKey)).isEmpty();

        // Verify no partial ledger entries persisted
        assertThat(ledgerEntryRepository.count()).isZero();
    }
}
