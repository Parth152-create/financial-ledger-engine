package com.parth.ledger.concurrency;

import com.parth.ledger.BaseIntegrationTest;
import com.parth.ledger.account.Account;
import com.parth.ledger.account.AccountClosedException;
import com.parth.ledger.account.AccountFrozenException;
import com.parth.ledger.account.AccountRepository;
import com.parth.ledger.account.AccountService;
import com.parth.ledger.account.AccountStatus;
import com.parth.ledger.account.AccountStatusException;
import com.parth.ledger.account.AccountType;
import com.parth.ledger.account.dto.CreateAccountRequestDto;
import com.parth.ledger.deposit.dto.DepositRequestDto;
import com.parth.ledger.deposit.service.DepositService;
import com.parth.ledger.idempotency.IdempotencyCacheService;
import com.parth.ledger.ledger.LedgerEntry;
import com.parth.ledger.ledger.LedgerEntryRepository;
import com.parth.ledger.ledger.LedgerEntryType;
import com.parth.ledger.reconciliation.ReconciliationStatus;
import com.parth.ledger.reconciliation.dto.ReconciliationResultDto;
import com.parth.ledger.reconciliation.service.ReconciliationService;
import com.parth.ledger.security.UserAuthService;
import com.parth.ledger.security.dto.SignupRequestDto;
import com.parth.ledger.system.SystemFundingService;
import com.parth.ledger.transaction.Transaction;
import com.parth.ledger.transaction.TransactionRepository;
import com.parth.ledger.transaction.TransactionStatus;
import com.parth.ledger.transaction.dto.TransferRequestDto;
import com.parth.ledger.transaction.dto.TransferResponseDto;
import com.parth.ledger.transaction.exception.IdempotencyConflictException;
import com.parth.ledger.transaction.exception.InsufficientBalanceException;
import com.parth.ledger.transaction.service.TransferService;
import com.parth.ledger.user.User;
import com.parth.ledger.user.UserRepository;
import com.parth.ledger.withdrawal.dto.WithdrawalRequestDto;
import com.parth.ledger.withdrawal.service.WithdrawalService;
import org.flywaydb.core.Flyway;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@DisplayName("Adversarial Concurrency, Race-Condition & Financial-Integrity Testing")
class AdversarialConcurrencyIntegrationTest extends BaseIntegrationTest {

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
    private TransferService transferService;

    @Autowired
    private DepositService depositService;

    @Autowired
    private WithdrawalService withdrawalService;

    @Autowired
    private AccountService accountService;

    @Autowired
    private ReconciliationService reconciliationService;

    @Autowired
    private SystemFundingService systemFundingService;

    @Autowired
    private UserAuthService userAuthService;

    @Autowired
    private IdempotencyCacheService idempotencyCacheService;

    @Autowired
    private DataSource dataSource;

    @MockitoSpyBean
    private LedgerEntryRepository spyLedgerEntryRepository;

    private User alice;
    private User bob;
    private Account aliceAccount;
    private Account bobAccount;

    @BeforeEach
    void setUp() {
        reset(spyLedgerEntryRepository);
        clearRedis();
        if (jdbcTemplate != null) {
            jdbcTemplate.execute("TRUNCATE TABLE ledger_entries CASCADE");
        }
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();

        systemFundingService.bootstrapSystemFunding(SystemFundingService.DEFAULT_BOOTSTRAP_AMOUNT);

        alice = userRepository.save(new User("alice.adversarial@ledger.com", "Alice Adversarial"));
        bob = userRepository.save(new User("bob.adversarial@ledger.com", "Bob Adversarial"));

        aliceAccount = accountRepository.save(new Account(alice, "INR", BigDecimal.ZERO.setScale(4)));
        bobAccount = accountRepository.save(new Account(bob, "INR", BigDecimal.ZERO.setScale(4)));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        reset(spyLedgerEntryRepository);
    }

    private void authenticate(String email) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(email, null, Collections.emptyList())
        );
    }

    private void authenticateAdmin() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin.adversarial@ledger.com", null,
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))
        );
    }

    private void fundUserAccount(User user, Account account, BigDecimal amount) {
        authenticate(user.getEmail());
        depositService.executeDeposit(
                "fund-" + UUID.randomUUID(),
                new DepositRequestDto(account.getId(), amount, "INR", "Initial funding")
        );
    }

    private void assertFinancialInvariants() {
        List<Account> negativeChecking = accountRepository.findAll().stream()
                .filter(a -> a.getAccountType() == AccountType.USER_CHECKING)
                .filter(a -> a.getBalance().compareTo(BigDecimal.ZERO) < 0)
                .toList();
        assertThat(negativeChecking).as("No USER_CHECKING account must have negative balance").isEmpty();

        accountRepository.findById(SystemFundingService.SYSTEM_CLEARING_ACCOUNT_ID).ifPresent(clearing ->
                assertThat(clearing.getBalance()).as("SYSTEM_CLEARING balance must be >= 0").isGreaterThanOrEqualTo(BigDecimal.ZERO)
        );

        List<Account> nonInrAccounts = accountRepository.findAll().stream()
                .filter(a -> !"INR".equals(a.getCurrency()))
                .toList();
        assertThat(nonInrAccounts).as("All accounts must have INR currency").isEmpty();

        List<Transaction> nonInrTx = transactionRepository.findAll().stream()
                .filter(t -> !"INR".equals(t.getCurrency()))
                .toList();
        assertThat(nonInrTx).as("All transactions must have INR currency").isEmpty();

        List<LedgerEntry> nonInrEntries = ledgerEntryRepository.findAll().stream()
                .filter(le -> !"INR".equals(le.getCurrency()))
                .toList();
        assertThat(nonInrEntries).as("All ledger entries must have INR currency").isEmpty();

        for (Transaction tx : transactionRepository.findAll()) {
            if (tx.getStatus() == TransactionStatus.COMPLETED) {
                List<LedgerEntry> entries = ledgerEntryRepository.findAll().stream()
                        .filter(le -> le.getTransaction().getId().equals(tx.getId()))
                        .toList();
                assertThat(entries).as("Completed transaction %s must have matching entries", tx.getId()).hasSizeGreaterThanOrEqualTo(2);
                BigDecimal debits = entries.stream()
                        .filter(le -> le.getEntryType() == LedgerEntryType.DEBIT)
                        .map(LedgerEntry::getAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal credits = entries.stream()
                        .filter(le -> le.getEntryType() == LedgerEntryType.CREDIT)
                        .map(LedgerEntry::getAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                assertThat(debits).as("Debits must equal credits for transaction %s", tx.getId()).isEqualByComparingTo(credits);
                assertThat(debits).as("Debits must equal tx amount for transaction %s", tx.getId()).isEqualByComparingTo(tx.getAmount());
            }
        }

        for (Account account : accountRepository.findAll()) {
            List<LedgerEntry> entries = ledgerEntryRepository.findAll().stream()
                    .filter(le -> le.getAccount().getId().equals(account.getId()))
                    .toList();
            BigDecimal credits = entries.stream()
                    .filter(le -> le.getEntryType() == LedgerEntryType.CREDIT)
                    .map(LedgerEntry::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal debits = entries.stream()
                    .filter(le -> le.getEntryType() == LedgerEntryType.DEBIT)
                    .map(LedgerEntry::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal ledgerNet = credits.subtract(debits);
            assertThat(account.getBalance())
                    .as("Account %s snapshot must match ledger net", account.getId())
                    .isEqualByComparingTo(ledgerNet);
        }

        BigDecimal systemTotal = accountRepository.findAll().stream()
                .map(Account::getBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(systemTotal).as("Overall platform balance must sum to zero").isEqualByComparingTo(BigDecimal.ZERO);

        Integer orphanTxCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries le LEFT JOIN transactions t ON le.transaction_id = t.id WHERE t.id IS NULL",
                Integer.class
        );
        assertThat(orphanTxCount).as("Orphan ledger entries without valid transaction must be 0").isEqualTo(0);

        Integer orphanAccountCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries le LEFT JOIN accounts a ON le.account_id = a.id WHERE a.id IS NULL",
                Integer.class
        );
        assertThat(orphanAccountCount).as("Orphan ledger entries without valid account must be 0").isEqualTo(0);

        List<Account> closedWithBalance = accountRepository.findAll().stream()
                .filter(a -> a.getStatus() == AccountStatus.CLOSED)
                .filter(a -> a.getBalance().compareTo(BigDecimal.ZERO) != 0)
                .toList();
        assertThat(closedWithBalance).as("Closed accounts must have zero balance").isEmpty();
    }

    @Test
    @DisplayName("1A. Concurrent transfers: one account sending money concurrently to many accounts")
    void concurrentTransfers_oneToMany_conservesBalancesAndLedgerEntries() throws InterruptedException {
        fundUserAccount(alice, aliceAccount, new BigDecimal("10000.0000"));

        int recipientCount = 20;
        BigDecimal transferAmount = new BigDecimal("250.0000");
        List<Account> recipients = new ArrayList<>();
        for (int i = 0; i < recipientCount; i++) {
            User recipientUser = userRepository.save(new User("recipient" + i + "@ledger.com", "Recipient " + i));
            Account recipientAccount = accountRepository.save(new Account(recipientUser, "INR", BigDecimal.ZERO.setScale(4)));
            recipients.add(recipientAccount);
        }

        ExecutorService executor = Executors.newFixedThreadPool(recipientCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(recipientCount);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < recipientCount; i++) {
            final Account dest = recipients.get(i);
            final String key = "tx-1tomany-" + i;
            executor.submit(() -> {
                try {
                    authenticate(alice.getEmail());
                    startLatch.await();
                    transferService.executeTransfer(key, new TransferRequestDto(
                            aliceAccount.getId(), dest.getId(), transferAmount, "INR"
                    ));
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    SecurityContextHolder.clearContext();
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = endLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(errors).isEmpty();

        Account finalAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        assertThat(finalAlice.getBalance()).isEqualByComparingTo(new BigDecimal("5000.0000"));

        for (Account dest : recipients) {
            Account finalDest = accountRepository.findById(dest.getId()).orElseThrow();
            assertThat(finalDest.getBalance()).isEqualByComparingTo(transferAmount);
            ReconciliationResultDto rec = reconciliationService.reconcileAccountDirectly(finalDest.getId());
            assertThat(rec.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
            assertThat(rec.difference()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        ReconciliationResultDto aliceRec = reconciliationService.reconcileAccountDirectly(finalAlice.getId());
        assertThat(aliceRec.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
        assertThat(aliceRec.difference()).isEqualByComparingTo(BigDecimal.ZERO);

        assertFinancialInvariants();
    }

    @Test
    @DisplayName("1B. Concurrent transfers: many accounts sending money concurrently into one account")
    void concurrentTransfers_manyToOne_conservesBalancesAndLedgerEntries() throws InterruptedException {
        int senderCount = 20;
        BigDecimal initialFunding = new BigDecimal("500.0000");
        BigDecimal transferAmount = new BigDecimal("200.0000");
        List<User> senders = new ArrayList<>();
        List<Account> senderAccounts = new ArrayList<>();

        for (int i = 0; i < senderCount; i++) {
            User senderUser = userRepository.save(new User("sender" + i + "@ledger.com", "Sender " + i));
            Account senderAccount = accountRepository.save(new Account(senderUser, "INR", BigDecimal.ZERO.setScale(4)));
            fundUserAccount(senderUser, senderAccount, initialFunding);
            senders.add(senderUser);
            senderAccounts.add(senderAccount);
        }

        ExecutorService executor = Executors.newFixedThreadPool(senderCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(senderCount);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < senderCount; i++) {
            final User senderUser = senders.get(i);
            final Account senderAcc = senderAccounts.get(i);
            final String key = "tx-manyto1-" + i;
            executor.submit(() -> {
                try {
                    authenticate(senderUser.getEmail());
                    startLatch.await();
                    transferService.executeTransfer(key, new TransferRequestDto(
                            senderAcc.getId(), aliceAccount.getId(), transferAmount, "INR"
                    ));
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    SecurityContextHolder.clearContext();
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = endLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(errors).isEmpty();

        Account finalAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        BigDecimal expectedAlice = transferAmount.multiply(BigDecimal.valueOf(senderCount));
        assertThat(finalAlice.getBalance()).isEqualByComparingTo(expectedAlice);

        for (Account src : senderAccounts) {
            Account finalSrc = accountRepository.findById(src.getId()).orElseThrow();
            assertThat(finalSrc.getBalance()).isEqualByComparingTo(initialFunding.subtract(transferAmount));
            ReconciliationResultDto rec = reconciliationService.reconcileAccountDirectly(finalSrc.getId());
            assertThat(rec.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
            assertThat(rec.difference()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        ReconciliationResultDto aliceRec = reconciliationService.reconcileAccountDirectly(finalAlice.getId());
        assertThat(aliceRec.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
        assertThat(aliceRec.difference()).isEqualByComparingTo(BigDecimal.ZERO);

        assertFinancialInvariants();
    }

    @Test
    @DisplayName("1C & 11. Concurrent transfers: bidirectional simultaneous transfers avoid deadlock and conserve balances")
    void concurrentTransfers_bidirectionalOppositeDirection_avoidsDeadlockAndConservesBalances() throws InterruptedException {
        fundUserAccount(alice, aliceAccount, new BigDecimal("1000.0000"));
        fundUserAccount(bob, bobAccount, new BigDecimal("1000.0000"));

        int transfersPerDirection = 30;
        int totalTransfers = transfersPerDirection * 2;
        BigDecimal transferAmount = new BigDecimal("15.0000");

        ExecutorService executor = Executors.newFixedThreadPool(totalTransfers);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(totalTransfers);
        AtomicInteger successCount = new AtomicInteger(0);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < transfersPerDirection; i++) {
            final String key = "tx-bidi-ab-" + i;
            executor.submit(() -> {
                try {
                    authenticate(alice.getEmail());
                    startLatch.await();
                    transferService.executeTransfer(key, new TransferRequestDto(
                            aliceAccount.getId(), bobAccount.getId(), transferAmount, "INR"
                    ));
                    successCount.incrementAndGet();
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    SecurityContextHolder.clearContext();
                    endLatch.countDown();
                }
            });
        }

        for (int i = 0; i < transfersPerDirection; i++) {
            final String key = "tx-bidi-ba-" + i;
            executor.submit(() -> {
                try {
                    authenticate(bob.getEmail());
                    startLatch.await();
                    transferService.executeTransfer(key, new TransferRequestDto(
                            bobAccount.getId(), aliceAccount.getId(), transferAmount, "INR"
                    ));
                    successCount.incrementAndGet();
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    SecurityContextHolder.clearContext();
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = endLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(errors).isEmpty();
        assertThat(successCount.get()).isEqualTo(totalTransfers);

        Account finalAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        Account finalBob = accountRepository.findById(bobAccount.getId()).orElseThrow();
        assertThat(finalAlice.getBalance()).isEqualByComparingTo(new BigDecimal("1000.0000"));
        assertThat(finalBob.getBalance()).isEqualByComparingTo(new BigDecimal("1000.0000"));
        assertThat(finalAlice.getBalance().add(finalBob.getBalance())).isEqualByComparingTo(new BigDecimal("2000.0000"));

        assertFinancialInvariants();
    }

    @Test
    @DisplayName("1D. Concurrent transfers: balance exhaustion contention prevents overdraft and maintains integrity")
    void concurrentTransfers_insufficientBalanceRace_preventsOverdraftAndMaintainsIntegrity() throws InterruptedException {
        fundUserAccount(alice, aliceAccount, new BigDecimal("100.0000"));

        int totalAttempts = 20;
        BigDecimal transferAmount = new BigDecimal("30.0000");

        ExecutorService executor = Executors.newFixedThreadPool(totalAttempts);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(totalAttempts);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger insufficientCount = new AtomicInteger(0);
        List<Throwable> unexpectedFailures = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < totalAttempts; i++) {
            final String key = "tx-insufficient-" + i;
            executor.submit(() -> {
                try {
                    authenticate(alice.getEmail());
                    startLatch.await();
                    transferService.executeTransfer(key, new TransferRequestDto(
                            aliceAccount.getId(), bobAccount.getId(), transferAmount, "INR"
                    ));
                    successCount.incrementAndGet();
                } catch (InsufficientBalanceException e) {
                    insufficientCount.incrementAndGet();
                } catch (Throwable t) {
                    unexpectedFailures.add(t);
                } finally {
                    SecurityContextHolder.clearContext();
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = endLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(unexpectedFailures).isEmpty();
        assertThat(successCount.get()).isEqualTo(3);
        assertThat(insufficientCount.get()).isEqualTo(17);

        Account finalAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        Account finalBob = accountRepository.findById(bobAccount.getId()).orElseThrow();
        assertThat(finalAlice.getBalance()).isEqualByComparingTo(new BigDecimal("10.0000"));
        assertThat(finalBob.getBalance()).isEqualByComparingTo(new BigDecimal("90.0000"));
        assertThat(finalAlice.getBalance()).isGreaterThanOrEqualTo(BigDecimal.ZERO);

        assertFinancialInvariants();
    }

    @Test
    @DisplayName("2. Mixed concurrent operations: deposit + withdrawal + transfers verify exact balance formula")
    void concurrentMixedOperations_depositWithdrawalAndTransfers_exactBalanceReconciliation() throws InterruptedException {
        fundUserAccount(alice, aliceAccount, new BigDecimal("10000.0000"));
        fundUserAccount(bob, bobAccount, new BigDecimal("5000.0000"));

        int opsPerType = 10;
        int totalOps = opsPerType * 4;

        BigDecimal depositAmount = new BigDecimal("200.0000");
        BigDecimal withdrawalAmount = new BigDecimal("100.0000");
        BigDecimal outTransferAmount = new BigDecimal("150.0000");
        BigDecimal inTransferAmount = new BigDecimal("50.0000");

        ExecutorService executor = Executors.newFixedThreadPool(totalOps);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(totalOps);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < opsPerType; i++) {
            final String key = "mix-dep-" + i;
            executor.submit(() -> {
                try {
                    authenticate(alice.getEmail());
                    startLatch.await();
                    depositService.executeDeposit(key, new DepositRequestDto(
                            aliceAccount.getId(), depositAmount, "INR", "Mixed deposit"
                    ));
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    SecurityContextHolder.clearContext();
                    endLatch.countDown();
                }
            });
        }

        for (int i = 0; i < opsPerType; i++) {
            final String key = "mix-wdr-" + i;
            executor.submit(() -> {
                try {
                    authenticate(alice.getEmail());
                    startLatch.await();
                    withdrawalService.executeWithdrawal(key, new WithdrawalRequestDto(
                            aliceAccount.getId(), withdrawalAmount, "INR", "Mixed withdrawal"
                    ));
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    SecurityContextHolder.clearContext();
                    endLatch.countDown();
                }
            });
        }

        for (int i = 0; i < opsPerType; i++) {
            final String key = "mix-out-tx-" + i;
            executor.submit(() -> {
                try {
                    authenticate(alice.getEmail());
                    startLatch.await();
                    transferService.executeTransfer(key, new TransferRequestDto(
                            aliceAccount.getId(), bobAccount.getId(), outTransferAmount, "INR"
                    ));
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    SecurityContextHolder.clearContext();
                    endLatch.countDown();
                }
            });
        }

        for (int i = 0; i < opsPerType; i++) {
            final String key = "mix-in-tx-" + i;
            executor.submit(() -> {
                try {
                    authenticate(bob.getEmail());
                    startLatch.await();
                    transferService.executeTransfer(key, new TransferRequestDto(
                            bobAccount.getId(), aliceAccount.getId(), inTransferAmount, "INR"
                    ));
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    SecurityContextHolder.clearContext();
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = endLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(errors).isEmpty();

        BigDecimal expectedAlice = new BigDecimal("10000.0000")
                .add(depositAmount.multiply(BigDecimal.valueOf(opsPerType)))
                .subtract(withdrawalAmount.multiply(BigDecimal.valueOf(opsPerType)))
                .subtract(outTransferAmount.multiply(BigDecimal.valueOf(opsPerType)))
                .add(inTransferAmount.multiply(BigDecimal.valueOf(opsPerType)));

        BigDecimal expectedBob = new BigDecimal("5000.0000")
                .add(outTransferAmount.multiply(BigDecimal.valueOf(opsPerType)))
                .subtract(inTransferAmount.multiply(BigDecimal.valueOf(opsPerType)));

        Account finalAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        Account finalBob = accountRepository.findById(bobAccount.getId()).orElseThrow();

        assertThat(finalAlice.getBalance()).isEqualByComparingTo(expectedAlice);
        assertThat(finalBob.getBalance()).isEqualByComparingTo(expectedBob);

        ReconciliationResultDto aliceRec = reconciliationService.reconcileAccountDirectly(finalAlice.getId());
        assertThat(aliceRec.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
        assertThat(aliceRec.difference()).isEqualByComparingTo(BigDecimal.ZERO);

        ReconciliationResultDto bobRec = reconciliationService.reconcileAccountDirectly(finalBob.getId());
        assertThat(bobRec.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
        assertThat(bobRec.difference()).isEqualByComparingTo(BigDecimal.ZERO);

        assertFinancialInvariants();
    }

    @Test
    @DisplayName("3A. Idempotency race: 10 identical concurrent requests execute single financial effect")
    void idempotencyRace_tenConcurrentIdenticalRequests_executesSingleEffect() throws InterruptedException {
        fundUserAccount(alice, aliceAccount, new BigDecimal("1000.0000"));

        int concurrentRetries = 10;
        String sharedKey = "tx-idem-10-race";
        BigDecimal transferAmount = new BigDecimal("100.0000");

        TransferRequestDto request = new TransferRequestDto(
                aliceAccount.getId(), bobAccount.getId(), transferAmount, "INR"
        );

        ExecutorService executor = Executors.newFixedThreadPool(concurrentRetries);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(concurrentRetries);

        Set<UUID> transactionIds = ConcurrentHashMap.newKeySet();
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < concurrentRetries; i++) {
            executor.submit(() -> {
                try {
                    authenticate(alice.getEmail());
                    startLatch.await();
                    TransferResponseDto response = transferService.executeTransfer(sharedKey, request);
                    transactionIds.add(response.transactionId());
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    SecurityContextHolder.clearContext();
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = endLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(errors).isEmpty();
        assertThat(transactionIds).hasSize(1);

        Account finalAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        Account finalBob = accountRepository.findById(bobAccount.getId()).orElseThrow();
        assertThat(finalAlice.getBalance()).isEqualByComparingTo(new BigDecimal("900.0000"));
        assertThat(finalBob.getBalance()).isEqualByComparingTo(new BigDecimal("100.0000"));

        long txCount = transactionRepository.findAll().stream()
                .filter(t -> sharedKey.equals(t.getIdempotencyKey()))
                .count();
        assertThat(txCount).isEqualTo(1);

        assertFinancialInvariants();
    }

    @Test
    @DisplayName("3B. Idempotency race: 50 identical concurrent requests execute single financial effect")
    void idempotencyRace_fiftyConcurrentIdenticalRequests_executesSingleEffect() throws InterruptedException {
        fundUserAccount(alice, aliceAccount, new BigDecimal("1000.0000"));

        int concurrentRetries = 50;
        String sharedKey = "tx-idem-50-race";
        BigDecimal transferAmount = new BigDecimal("100.0000");

        TransferRequestDto request = new TransferRequestDto(
                aliceAccount.getId(), bobAccount.getId(), transferAmount, "INR"
        );

        ExecutorService executor = Executors.newFixedThreadPool(concurrentRetries);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(concurrentRetries);

        Set<UUID> transactionIds = ConcurrentHashMap.newKeySet();
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < concurrentRetries; i++) {
            executor.submit(() -> {
                try {
                    authenticate(alice.getEmail());
                    startLatch.await();
                    TransferResponseDto response = transferService.executeTransfer(sharedKey, request);
                    transactionIds.add(response.transactionId());
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    SecurityContextHolder.clearContext();
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = endLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(errors).isEmpty();
        assertThat(transactionIds).hasSize(1);

        Account finalAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        Account finalBob = accountRepository.findById(bobAccount.getId()).orElseThrow();
        assertThat(finalAlice.getBalance()).isEqualByComparingTo(new BigDecimal("900.0000"));
        assertThat(finalBob.getBalance()).isEqualByComparingTo(new BigDecimal("100.0000"));

        long txCount = transactionRepository.findAll().stream()
                .filter(t -> sharedKey.equals(t.getIdempotencyKey()))
                .count();
        assertThat(txCount).isEqualTo(1);

        assertFinancialInvariants();
    }

    @Test
    @DisplayName("3D. Idempotency race: conflicting payloads under concurrency are rejected")
    void idempotencyRace_conflictingPayloadConcurrently_rejectsConflictingRequests() throws InterruptedException {
        fundUserAccount(alice, aliceAccount, new BigDecimal("1000.0000"));

        String sharedKey = "tx-idem-conflict-race";
        TransferRequestDto payloadA = new TransferRequestDto(aliceAccount.getId(), bobAccount.getId(), new BigDecimal("100.0000"), "INR");
        TransferRequestDto payloadB = new TransferRequestDto(aliceAccount.getId(), bobAccount.getId(), new BigDecimal("250.0000"), "INR");

        int threadsPerPayload = 10;
        int totalThreads = threadsPerPayload * 2;

        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(totalThreads);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);
        List<Throwable> unexpectedErrors = Collections.synchronizedList(new ArrayList<>());
        Set<BigDecimal> executedAmounts = ConcurrentHashMap.newKeySet();

        for (int i = 0; i < threadsPerPayload; i++) {
            executor.submit(() -> {
                try {
                    authenticate(alice.getEmail());
                    startLatch.await();
                    TransferResponseDto res = transferService.executeTransfer(sharedKey, payloadA);
                    successCount.incrementAndGet();
                    executedAmounts.add(res.amount());
                } catch (IdempotencyConflictException e) {
                    conflictCount.incrementAndGet();
                } catch (Throwable t) {
                    unexpectedErrors.add(t);
                } finally {
                    SecurityContextHolder.clearContext();
                    endLatch.countDown();
                }
            });
        }

        for (int i = 0; i < threadsPerPayload; i++) {
            executor.submit(() -> {
                try {
                    authenticate(alice.getEmail());
                    startLatch.await();
                    TransferResponseDto res = transferService.executeTransfer(sharedKey, payloadB);
                    successCount.incrementAndGet();
                    executedAmounts.add(res.amount());
                } catch (IdempotencyConflictException e) {
                    conflictCount.incrementAndGet();
                } catch (Throwable t) {
                    unexpectedErrors.add(t);
                } finally {
                    SecurityContextHolder.clearContext();
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = endLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(unexpectedErrors).isEmpty();
        assertThat(successCount.get()).isGreaterThanOrEqualTo(1);
        assertThat(conflictCount.get()).isGreaterThanOrEqualTo(1);
        assertThat(successCount.get() + conflictCount.get()).isEqualTo(totalThreads);
        assertThat(executedAmounts).hasSize(1);

        BigDecimal winningAmount = executedAmounts.iterator().next();
        Account finalAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        Account finalBob = accountRepository.findById(bobAccount.getId()).orElseThrow();
        assertThat(finalAlice.getBalance()).isEqualByComparingTo(new BigDecimal("1000.0000").subtract(winningAmount));
        assertThat(finalBob.getBalance()).isEqualByComparingTo(winningAmount);

        long txCount = transactionRepository.findAll().stream()
                .filter(t -> sharedKey.equals(t.getIdempotencyKey()))
                .count();
        assertThat(txCount).isEqualTo(1);

        assertFinancialInvariants();
    }

    @Test
    @DisplayName("4A. Lifecycle race: concurrent freeze and transfer serializes safely")
    void accountLifecycleRace_concurrentFreezeAndTransfer_serializesSafely() throws InterruptedException {
        fundUserAccount(alice, aliceAccount, new BigDecimal("500.0000"));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(2);

        AtomicBoolean freezeSucceeded = new AtomicBoolean(false);
        AtomicBoolean transferSucceeded = new AtomicBoolean(false);

        executor.submit(() -> {
            try {
                authenticateAdmin();
                startLatch.await();
                accountService.freezeAccount(aliceAccount.getId());
                freezeSucceeded.set(true);
            } catch (Exception ignored) {
            } finally {
                SecurityContextHolder.clearContext();
                endLatch.countDown();
            }
        });

        executor.submit(() -> {
            try {
                authenticate(alice.getEmail());
                startLatch.await();
                transferService.executeTransfer("tx-frz-race-01",
                        new TransferRequestDto(aliceAccount.getId(), bobAccount.getId(), new BigDecimal("100.0000"), "INR"));
                transferSucceeded.set(true);
            } catch (Exception ignored) {
            } finally {
                SecurityContextHolder.clearContext();
                endLatch.countDown();
            }
        });

        startLatch.countDown();
        assertThat(endLatch.await(10, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        Account reloaded = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        assertThat(freezeSucceeded.get()).isTrue();
        assertThat(reloaded.getStatus()).isEqualTo(AccountStatus.FROZEN);

        if (transferSucceeded.get()) {
            assertThat(reloaded.getBalance()).isEqualByComparingTo(new BigDecimal("400.0000"));
        } else {
            assertThat(reloaded.getBalance()).isEqualByComparingTo(new BigDecimal("500.0000"));
        }

        assertFinancialInvariants();
    }

    @Test
    @DisplayName("4B. Lifecycle race: concurrent close and destination transfer serializes safely")
    void accountLifecycleRace_concurrentCloseAndTransferDestination_serializesSafely() throws InterruptedException {
        fundUserAccount(bob, bobAccount, new BigDecimal("200.0000"));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(2);

        AtomicBoolean closeSucceeded = new AtomicBoolean(false);
        AtomicBoolean transferSucceeded = new AtomicBoolean(false);

        executor.submit(() -> {
            try {
                authenticate(alice.getEmail());
                startLatch.await();
                accountService.closeAccount(aliceAccount.getId());
                closeSucceeded.set(true);
            } catch (Exception ignored) {
            } finally {
                SecurityContextHolder.clearContext();
                endLatch.countDown();
            }
        });

        executor.submit(() -> {
            try {
                authenticate(bob.getEmail());
                startLatch.await();
                transferService.executeTransfer("tx-cls-race-01",
                        new TransferRequestDto(bobAccount.getId(), aliceAccount.getId(), new BigDecimal("50.0000"), "INR"));
                transferSucceeded.set(true);
            } catch (Exception ignored) {
            } finally {
                SecurityContextHolder.clearContext();
                endLatch.countDown();
            }
        });

        startLatch.countDown();
        assertThat(endLatch.await(10, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        Account reloaded = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        assertThat(closeSucceeded.get() ^ transferSucceeded.get()).isTrue();
        if (closeSucceeded.get()) {
            assertThat(reloaded.getStatus()).isEqualTo(AccountStatus.CLOSED);
            assertThat(reloaded.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        } else {
            assertThat(reloaded.getStatus()).isEqualTo(AccountStatus.ACTIVE);
            assertThat(reloaded.getBalance()).isEqualByComparingTo(new BigDecimal("50.0000"));
        }

        assertFinancialInvariants();
    }

    @Test
    @DisplayName("4C. Lifecycle race: concurrent freeze and deposit serializes safely")
    void accountLifecycleRace_concurrentFreezeAndDeposit_serializesSafely() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(2);

        AtomicBoolean freezeSucceeded = new AtomicBoolean(false);
        AtomicBoolean depositSucceeded = new AtomicBoolean(false);

        executor.submit(() -> {
            try {
                authenticateAdmin();
                startLatch.await();
                accountService.freezeAccount(aliceAccount.getId());
                freezeSucceeded.set(true);
            } catch (Exception ignored) {
            } finally {
                SecurityContextHolder.clearContext();
                endLatch.countDown();
            }
        });

        executor.submit(() -> {
            try {
                authenticate(alice.getEmail());
                startLatch.await();
                depositService.executeDeposit("dep-frz-race-01",
                        new DepositRequestDto(aliceAccount.getId(), new BigDecimal("100.0000"), "INR", "Deposit"));
                depositSucceeded.set(true);
            } catch (Exception ignored) {
            } finally {
                SecurityContextHolder.clearContext();
                endLatch.countDown();
            }
        });

        startLatch.countDown();
        assertThat(endLatch.await(10, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        Account reloaded = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        assertThat(freezeSucceeded.get()).isTrue();
        assertThat(reloaded.getStatus()).isEqualTo(AccountStatus.FROZEN);

        if (depositSucceeded.get()) {
            assertThat(reloaded.getBalance()).isEqualByComparingTo(new BigDecimal("100.0000"));
        } else {
            assertThat(reloaded.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        assertFinancialInvariants();
    }

    @Test
    @DisplayName("4D. Lifecycle race: concurrent freeze and withdrawal serializes safely")
    void accountLifecycleRace_concurrentFreezeAndWithdrawal_serializesSafely() throws InterruptedException {
        fundUserAccount(alice, aliceAccount, new BigDecimal("300.0000"));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(2);

        AtomicBoolean freezeSucceeded = new AtomicBoolean(false);
        AtomicBoolean withdrawalSucceeded = new AtomicBoolean(false);

        executor.submit(() -> {
            try {
                authenticateAdmin();
                startLatch.await();
                accountService.freezeAccount(aliceAccount.getId());
                freezeSucceeded.set(true);
            } catch (Exception ignored) {
            } finally {
                SecurityContextHolder.clearContext();
                endLatch.countDown();
            }
        });

        executor.submit(() -> {
            try {
                authenticate(alice.getEmail());
                startLatch.await();
                withdrawalService.executeWithdrawal("wdr-frz-race-01",
                        new WithdrawalRequestDto(aliceAccount.getId(), new BigDecimal("100.0000"), "INR", "Withdrawal"));
                withdrawalSucceeded.set(true);
            } catch (Exception ignored) {
            } finally {
                SecurityContextHolder.clearContext();
                endLatch.countDown();
            }
        });

        startLatch.countDown();
        assertThat(endLatch.await(10, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        Account reloaded = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        assertThat(freezeSucceeded.get()).isTrue();
        assertThat(reloaded.getStatus()).isEqualTo(AccountStatus.FROZEN);

        if (withdrawalSucceeded.get()) {
            assertThat(reloaded.getBalance()).isEqualByComparingTo(new BigDecimal("200.0000"));
        } else {
            assertThat(reloaded.getBalance()).isEqualByComparingTo(new BigDecimal("300.0000"));
        }

        assertFinancialInvariants();
    }

    @Test
    @DisplayName("4E. Lifecycle race: concurrent close and withdrawal of exact balance serializes safely")
    void accountLifecycleRace_concurrentCloseAndWithdrawalExactBalance_serializesSafely() throws InterruptedException {
        fundUserAccount(alice, aliceAccount, new BigDecimal("100.0000"));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(2);

        AtomicBoolean closeSucceeded = new AtomicBoolean(false);
        AtomicBoolean withdrawalSucceeded = new AtomicBoolean(false);

        executor.submit(() -> {
            try {
                authenticate(alice.getEmail());
                startLatch.await();
                accountService.closeAccount(aliceAccount.getId());
                closeSucceeded.set(true);
            } catch (Exception ignored) {
            } finally {
                SecurityContextHolder.clearContext();
                endLatch.countDown();
            }
        });

        executor.submit(() -> {
            try {
                authenticate(alice.getEmail());
                startLatch.await();
                withdrawalService.executeWithdrawal("wdr-exact-race-01",
                        new WithdrawalRequestDto(aliceAccount.getId(), new BigDecimal("100.0000"), "INR", "Empty balance"));
                withdrawalSucceeded.set(true);
            } catch (Exception ignored) {
            } finally {
                SecurityContextHolder.clearContext();
                endLatch.countDown();
            }
        });

        startLatch.countDown();
        assertThat(endLatch.await(10, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        Account reloaded = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        assertThat(withdrawalSucceeded.get()).isTrue();
        assertThat(reloaded.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);

        if (closeSucceeded.get()) {
            assertThat(reloaded.getStatus()).isEqualTo(AccountStatus.CLOSED);
        } else {
            assertThat(reloaded.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        }

        assertFinancialInvariants();
    }

    @Test
    @DisplayName("5. Clean database bootstrap: Flyway migrations run cleanly and initialize system accounts with zero manual SQL")
    void cleanDatabaseBootstrap_flywayMigrationsAndInitialFunding_zeroManualSql() {
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS clean_bootstrap CASCADE");
        jdbcTemplate.execute("CREATE SCHEMA clean_bootstrap");

        Flyway cleanFlyway = Flyway.configure()
                .dataSource(dataSource)
                .schemas("clean_bootstrap")
                .load();

        var migrationResult = cleanFlyway.migrate();
        assertThat(migrationResult.success).isTrue();
        assertThat(migrationResult.migrationsExecuted).isGreaterThanOrEqualTo(9);

        Integer accountCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM clean_bootstrap.accounts", Integer.class
        );
        assertThat(accountCount).isEqualTo(2);

        BigDecimal treasuryBalance = jdbcTemplate.queryForObject(
                "SELECT balance FROM clean_bootstrap.accounts WHERE id = '00000000-0000-0000-0000-000000000002'",
                BigDecimal.class
        );
        assertThat(treasuryBalance).isEqualByComparingTo(new BigDecimal("-10000000.0000"));

        BigDecimal clearingBalance = jdbcTemplate.queryForObject(
                "SELECT balance FROM clean_bootstrap.accounts WHERE id = '00000000-0000-0000-0000-000000000001'",
                BigDecimal.class
        );
        assertThat(clearingBalance).isEqualByComparingTo(new BigDecimal("10000000.0000"));

        Integer txCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM clean_bootstrap.transactions", Integer.class
        );
        assertThat(txCount).isEqualTo(1);

        Integer entryCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM clean_bootstrap.ledger_entries", Integer.class
        );
        assertThat(entryCount).isEqualTo(2);

        BigDecimal debits = jdbcTemplate.queryForObject(
                "SELECT SUM(amount) FROM clean_bootstrap.ledger_entries WHERE entry_type = 'DEBIT'", BigDecimal.class
        );
        BigDecimal credits = jdbcTemplate.queryForObject(
                "SELECT SUM(amount) FROM clean_bootstrap.ledger_entries WHERE entry_type = 'CREDIT'", BigDecimal.class
        );
        assertThat(debits).isEqualByComparingTo(new BigDecimal("10000000.0000"));
        assertThat(credits).isEqualByComparingTo(new BigDecimal("10000000.0000"));

        User bootstrapUser = userAuthService.signup(new SignupRequestDto("bootstrap.user@ledger.com", "Bootstrap User", "ValidPass123!"));
        authenticate(bootstrapUser.getEmail());
        var createdAcc = accountService.createAccount(new CreateAccountRequestDto("INR"));

        depositService.executeDeposit("clean-dep-01",
                new DepositRequestDto(createdAcc.accountId(), new BigDecimal("500.0000"), "INR", "Bootstrap deposit"));

        withdrawalService.executeWithdrawal("clean-wdr-01",
                new WithdrawalRequestDto(createdAcc.accountId(), new BigDecimal("150.0000"), "INR", "Bootstrap withdrawal"));

        Account userAccount = accountRepository.findById(createdAcc.accountId()).orElseThrow();
        assertThat(userAccount.getBalance()).isEqualByComparingTo(new BigDecimal("350.0000"));

        ReconciliationResultDto userRec = reconciliationService.reconcileAccountDirectly(createdAcc.accountId());
        assertThat(userRec.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
        assertThat(userRec.difference()).isEqualByComparingTo(BigDecimal.ZERO);

        ReconciliationResultDto clearingRec = reconciliationService.reconcileAccountDirectly(SystemFundingService.SYSTEM_CLEARING_ACCOUNT_ID);
        assertThat(clearingRec.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
        assertThat(clearingRec.difference()).isEqualByComparingTo(BigDecimal.ZERO);

        assertFinancialInvariants();
    }

    @Test
    @DisplayName("6. Cross-currency adversarial tests: HTTP, Service, and DB constraints reject non-INR currencies")
    void crossCurrencyAdversarial_multiLayerDefense_rejectsNonInrAcrossAllLayers() throws Exception {
        authenticate(alice.getEmail());

        mockMvc.perform(post("/api/v1/accounts")
                        .with(user(alice.getEmail()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\":\"USD\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/transfers")
                        .with(user(alice.getEmail()))
                        .with(csrf())
                        .header("Idempotency-Key", "tx-cross-api-01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransferRequestDto(
                                aliceAccount.getId(), bobAccount.getId(), new BigDecimal("50.0000"), "EUR"))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/deposits")
                        .with(user(alice.getEmail()))
                        .with(csrf())
                        .header("Idempotency-Key", "dep-cross-api-01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DepositRequestDto(
                                aliceAccount.getId(), new BigDecimal("50.0000"), "GBP", "Non-INR"))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/withdrawals")
                        .with(user(alice.getEmail()))
                        .with(csrf())
                        .header("Idempotency-Key", "wdr-cross-api-01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new WithdrawalRequestDto(
                                aliceAccount.getId(), new BigDecimal("50.0000"), "JPY", "Non-INR"))))
                .andExpect(status().isBadRequest());

        assertThatThrownBy(() -> accountService.createAccount(new CreateAccountRequestDto("USD")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Only INR currency is supported");

        assertThatThrownBy(() -> transferService.executeTransfer("tx-cross-svc-01",
                new TransferRequestDto(aliceAccount.getId(), bobAccount.getId(), new BigDecimal("10.0000"), "EUR")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Only INR currency is supported");

        assertThatThrownBy(() -> depositService.executeDeposit("dep-cross-svc-01",
                new DepositRequestDto(aliceAccount.getId(), new BigDecimal("10.0000"), "GBP", "Non-INR")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Only INR currency is supported");

        assertThatThrownBy(() -> withdrawalService.executeWithdrawal("wdr-cross-svc-01",
                new WithdrawalRequestDto(aliceAccount.getId(), new BigDecimal("10.0000"), "JPY", "Non-INR")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Only INR currency is supported");

        UUID fakeId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbcTemplate.update("""
            INSERT INTO accounts (id, user_id, currency, balance, version, created_at, updated_at, account_type, status, account_number)
            VALUES (?, ?, 'USD', 100.0000, 0, NOW(), NOW(), 'USER_CHECKING', 'ACTIVE', 'ACCT-FAKE-USD')
        """, fakeId, alice.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbcTemplate.update("""
            INSERT INTO transactions (id, idempotency_key, amount, currency, status, source_account_id, destination_account_id, created_at, completed_at, transaction_type, initiated_by_user_id)
            VALUES (?, 'tx-raw-eur', 10.0000, 'EUR', 'COMPLETED', ?, ?, NOW(), NOW(), 'TRANSFER', ?)
        """, UUID.randomUUID(), aliceAccount.getId(), bobAccount.getId(), alice.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbcTemplate.update("""
            INSERT INTO ledger_entries (id, transaction_id, account_id, entry_type, amount, currency, created_at)
            VALUES (?, '00000000-0000-0000-0000-000000000010', ?, 'DEBIT', 10.0000, 'GBP', NOW())
        """, UUID.randomUUID(), aliceAccount.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertFinancialInvariants();
    }

    @Test
    @DisplayName("7. Ledger immutability: concurrent update and delete attempts are blocked by trigger without ledger corruption")
    void ledgerImmutability_concurrentUpdateAndDeleteAttempts_blockedByTriggerWithoutCorruption() throws InterruptedException {
        fundUserAccount(alice, aliceAccount, new BigDecimal("1000.0000"));

        transferService.executeTransfer("tx-imm-seed-01",
                new TransferRequestDto(aliceAccount.getId(), bobAccount.getId(), new BigDecimal("100.0000"), "INR"));

        List<LedgerEntry> entries = ledgerEntryRepository.findAll();
        assertThat(entries).isNotEmpty();
        UUID targetEntryId = entries.get(entries.size() - 1).getId();

        int mutationThreads = 10;
        int legitimateThreads = 10;
        int totalThreads = mutationThreads + legitimateThreads;

        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(totalThreads);

        AtomicInteger blockedMutations = new AtomicInteger(0);
        AtomicInteger successfulTransfers = new AtomicInteger(0);
        List<Throwable> unexpectedErrors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < mutationThreads; i++) {
            final boolean isDelete = (i % 2 == 0);
            executor.submit(() -> {
                try {
                    startLatch.await();
                    if (isDelete) {
                        jdbcTemplate.update("DELETE FROM ledger_entries WHERE id = ?", targetEntryId);
                    } else {
                        jdbcTemplate.update("UPDATE ledger_entries SET amount = 999999.0000 WHERE id = ?", targetEntryId);
                    }
                } catch (DataAccessException e) {
                    if (e.getMessage() != null && e.getMessage().contains("immutable")) {
                        blockedMutations.incrementAndGet();
                    } else {
                        unexpectedErrors.add(e);
                    }
                } catch (Throwable t) {
                    unexpectedErrors.add(t);
                } finally {
                    endLatch.countDown();
                }
            });
        }

        for (int i = 0; i < legitimateThreads; i++) {
            final String key = "tx-imm-legit-" + i;
            executor.submit(() -> {
                try {
                    authenticate(alice.getEmail());
                    startLatch.await();
                    transferService.executeTransfer(key,
                            new TransferRequestDto(aliceAccount.getId(), bobAccount.getId(), new BigDecimal("10.0000"), "INR"));
                    successfulTransfers.incrementAndGet();
                } catch (Throwable t) {
                    unexpectedErrors.add(t);
                } finally {
                    SecurityContextHolder.clearContext();
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = endLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(unexpectedErrors).isEmpty();
        assertThat(blockedMutations.get()).isEqualTo(mutationThreads);
        assertThat(successfulTransfers.get()).isEqualTo(legitimateThreads);

        assertFinancialInvariants();
    }

    @Test
    @DisplayName("8. Reconciliation under load: parallel reconciliation queries report zero discrepancy upon settlement")
    void reconciliationUnderLoad_concurrentTransactionsWithParallelReconciliation_reportsZeroDiscrepancyUponSettlement() throws InterruptedException {
        fundUserAccount(alice, aliceAccount, new BigDecimal("2000.0000"));
        fundUserAccount(bob, bobAccount, new BigDecimal("2000.0000"));

        int txThreads = 20;
        int recThreads = 5;
        int totalThreads = txThreads + recThreads;

        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch txEndLatch = new CountDownLatch(txThreads);
        CountDownLatch allEndLatch = new CountDownLatch(totalThreads);

        AtomicBoolean keepReconciling = new AtomicBoolean(true);
        List<Throwable> recErrors = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> txErrors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < txThreads; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    if (idx % 2 == 0) {
                        authenticate(alice.getEmail());
                        transferService.executeTransfer("tx-load-ab-" + idx,
                                new TransferRequestDto(aliceAccount.getId(), bobAccount.getId(), new BigDecimal("10.0000"), "INR"));
                    } else {
                        authenticate(bob.getEmail());
                        transferService.executeTransfer("tx-load-ba-" + idx,
                                new TransferRequestDto(bobAccount.getId(), aliceAccount.getId(), new BigDecimal("10.0000"), "INR"));
                    }
                } catch (Throwable t) {
                    txErrors.add(t);
                } finally {
                    SecurityContextHolder.clearContext();
                    txEndLatch.countDown();
                    allEndLatch.countDown();
                }
            });
        }

        for (int i = 0; i < recThreads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    while (keepReconciling.get()) {
                        reconciliationService.reconcileAccountDirectly(aliceAccount.getId());
                        reconciliationService.reconcileAccountDirectly(bobAccount.getId());
                    }
                } catch (Throwable t) {
                    recErrors.add(t);
                } finally {
                    allEndLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean txCompleted = txEndLatch.await(30, TimeUnit.SECONDS);
        keepReconciling.set(false);
        boolean allCompleted = allEndLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(txCompleted).isTrue();
        assertThat(allCompleted).isTrue();
        assertThat(txErrors).isEmpty();
        assertThat(recErrors).isEmpty();

        ReconciliationResultDto aliceRec = reconciliationService.reconcileAccountDirectly(aliceAccount.getId());
        assertThat(aliceRec.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
        assertThat(aliceRec.difference()).isEqualByComparingTo(BigDecimal.ZERO);

        ReconciliationResultDto bobRec = reconciliationService.reconcileAccountDirectly(bobAccount.getId());
        assertThat(bobRec.status()).isEqualTo(ReconciliationStatus.CONSISTENT);
        assertThat(bobRec.difference()).isEqualByComparingTo(BigDecimal.ZERO);

        assertFinancialInvariants();
    }

    @Test
    @DisplayName("9A. Rollback & failure injection: simulated failure during transfer rolls back all partial effects")
    void rollbackFailureInjection_simulatedExceptionDuringTransfer_rollsBackAllStateCleanly() {
        fundUserAccount(alice, aliceAccount, new BigDecimal("500.0000"));

        BigDecimal initialAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow().getBalance();
        BigDecimal initialBob = accountRepository.findById(bobAccount.getId()).orElseThrow().getBalance();
        long initialTxCount = transactionRepository.count();
        long initialEntryCount = ledgerEntryRepository.count();

        String idempotencyKey = "tx-rollback-injection-01";
        TransferRequestDto request = new TransferRequestDto(
                aliceAccount.getId(), bobAccount.getId(), new BigDecimal("100.0000"), "INR"
        );

        doThrow(new RuntimeException("Simulated failure during ledger entry persistence"))
                .when(spyLedgerEntryRepository).save(any(LedgerEntry.class));

        authenticate(alice.getEmail());
        assertThatThrownBy(() -> transferService.executeTransfer(idempotencyKey, request))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Simulated failure");

        Account reloadedAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        Account reloadedBob = accountRepository.findById(bobAccount.getId()).orElseThrow();
        assertThat(reloadedAlice.getBalance()).isEqualByComparingTo(initialAlice);
        assertThat(reloadedBob.getBalance()).isEqualByComparingTo(initialBob);

        assertThat(transactionRepository.count()).isEqualTo(initialTxCount);
        assertThat(ledgerEntryRepository.count()).isEqualTo(initialEntryCount);
        assertThat(idempotencyCacheService.get(idempotencyKey)).isEmpty();

        reset(spyLedgerEntryRepository);

        TransferResponseDto successfulRetry = transferService.executeTransfer(idempotencyKey, request);
        assertThat(successfulRetry).isNotNull();

        Account settledAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        Account settledBob = accountRepository.findById(bobAccount.getId()).orElseThrow();
        assertThat(settledAlice.getBalance()).isEqualByComparingTo(new BigDecimal("400.0000"));
        assertThat(settledBob.getBalance()).isEqualByComparingTo(new BigDecimal("100.0000"));

        assertFinancialInvariants();
    }

    @Test
    @DisplayName("9B. Rollback & failure injection: simulated failure during deposit and withdrawal rolls back cleanly")
    void rollbackFailureInjection_simulatedExceptionDuringDepositAndWithdrawal_rollsBackCleanly() {
        fundUserAccount(alice, aliceAccount, new BigDecimal("300.0000"));

        BigDecimal initialAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow().getBalance();
        BigDecimal initialClearing = accountRepository.findById(SystemFundingService.SYSTEM_CLEARING_ACCOUNT_ID).orElseThrow().getBalance();
        long initialTxCount = transactionRepository.count();
        long initialEntryCount = ledgerEntryRepository.count();

        doThrow(new RuntimeException("Simulated deposit ledger failure"))
                .when(spyLedgerEntryRepository).save(any(LedgerEntry.class));

        authenticate(alice.getEmail());
        assertThatThrownBy(() -> depositService.executeDeposit("dep-rollback-01",
                new DepositRequestDto(aliceAccount.getId(), new BigDecimal("100.0000"), "INR", "Deposit")))
                .isInstanceOf(RuntimeException.class);

        assertThat(accountRepository.findById(aliceAccount.getId()).orElseThrow().getBalance()).isEqualByComparingTo(initialAlice);
        assertThat(accountRepository.findById(SystemFundingService.SYSTEM_CLEARING_ACCOUNT_ID).orElseThrow().getBalance()).isEqualByComparingTo(initialClearing);
        assertThat(transactionRepository.count()).isEqualTo(initialTxCount);
        assertThat(ledgerEntryRepository.count()).isEqualTo(initialEntryCount);

        reset(spyLedgerEntryRepository);

        doThrow(new RuntimeException("Simulated withdrawal ledger failure"))
                .when(spyLedgerEntryRepository).save(any(LedgerEntry.class));

        assertThatThrownBy(() -> withdrawalService.executeWithdrawal("wdr-rollback-01",
                new WithdrawalRequestDto(aliceAccount.getId(), new BigDecimal("100.0000"), "INR", "Withdrawal")))
                .isInstanceOf(RuntimeException.class);

        assertThat(accountRepository.findById(aliceAccount.getId()).orElseThrow().getBalance()).isEqualByComparingTo(initialAlice);
        assertThat(accountRepository.findById(SystemFundingService.SYSTEM_CLEARING_ACCOUNT_ID).orElseThrow().getBalance()).isEqualByComparingTo(initialClearing);
        assertThat(transactionRepository.count()).isEqualTo(initialTxCount);
        assertThat(ledgerEntryRepository.count()).isEqualTo(initialEntryCount);

        reset(spyLedgerEntryRepository);
        assertFinancialInvariants();
    }

    @Test
    @DisplayName("10. Connection pool pressure: high concurrency exercises Hikari pool without deadlocks or leaks")
    void connectionPoolPressure_highConcurrencyExercisesHikariPoolWithoutDeadlocksOrLeaks() throws InterruptedException {
        int userCount = 50;
        BigDecimal initialAmount = new BigDecimal("500.0000");
        BigDecimal transferAmount = new BigDecimal("50.0000");

        List<User> users = new ArrayList<>();
        List<Account> accounts = new ArrayList<>();

        for (int i = 0; i < userCount; i++) {
            User u = userRepository.save(new User("pooluser" + i + "@ledger.com", "Pool User " + i));
            Account a = accountRepository.save(new Account(u, "INR", BigDecimal.ZERO.setScale(4)));
            fundUserAccount(u, a, initialAmount);
            users.add(u);
            accounts.add(a);
        }

        ExecutorService executor = Executors.newFixedThreadPool(userCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(userCount);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < userCount; i++) {
            final int srcIdx = i;
            final int dstIdx = (i + 1) % userCount;
            final User srcUser = users.get(srcIdx);
            final Account srcAcc = accounts.get(srcIdx);
            final Account dstAcc = accounts.get(dstIdx);
            final String key = "tx-pool-" + i;

            executor.submit(() -> {
                try {
                    authenticate(srcUser.getEmail());
                    startLatch.await();
                    transferService.executeTransfer(key, new TransferRequestDto(
                            srcAcc.getId(), dstAcc.getId(), transferAmount, "INR"
                    ));
                    successCount.incrementAndGet();
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    SecurityContextHolder.clearContext();
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = endLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(errors).isEmpty();
        assertThat(successCount.get()).isEqualTo(userCount);

        for (Account a : accounts) {
            Account finalA = accountRepository.findById(a.getId()).orElseThrow();
            assertThat(finalA.getBalance()).isEqualByComparingTo(initialAmount);
        }

        assertFinancialInvariants();
    }
}
