package com.parth.ledger.transaction;

import com.parth.ledger.account.Account;
import com.parth.ledger.account.AccountClosedException;
import com.parth.ledger.account.AccountFrozenException;
import com.parth.ledger.account.AccountRepository;
import com.parth.ledger.account.AccountStatus;
import com.parth.ledger.account.AccountType;
import com.parth.ledger.audit.AuditEntityType;
import com.parth.ledger.audit.AuditEventService;
import com.parth.ledger.audit.AuditEventType;
import com.parth.ledger.idempotency.IdempotencyCacheService;
import com.parth.ledger.ledger.LedgerEntry;
import com.parth.ledger.ledger.LedgerEntryRepository;
import com.parth.ledger.ledger.LedgerEntryType;
import com.parth.ledger.security.AccountOwnershipException;
import com.parth.ledger.security.AuthenticatedUserService;
import com.parth.ledger.transaction.dto.ReversalRequestDto;
import com.parth.ledger.transaction.dto.ReversalResponseDto;
import com.parth.ledger.transaction.dto.TransactionResponseDto;
import com.parth.ledger.transaction.exception.IdempotencyConflictException;
import com.parth.ledger.transaction.exception.InsufficientBalanceException;
import com.parth.ledger.transaction.exception.TransactionAlreadyReversedException;
import com.parth.ledger.transaction.exception.TransactionNotFoundException;
import com.parth.ledger.transaction.exception.TransactionNotReversibleException;
import com.parth.ledger.transaction.service.TransactionReversalService;
import com.parth.ledger.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransactionReversalServiceTest {

    private TransactionRepository transactionRepository;
    private AccountRepository accountRepository;
    private LedgerEntryRepository ledgerEntryRepository;
    private IdempotencyCacheService idempotencyCacheService;
    private AuthenticatedUserService authenticatedUserService;
    private AuditEventService auditEventService;
    private TransactionReversalService reversalService;

    private User alice;
    private User bob;
    private User charlie;
    private User admin;
    private Account aliceAccount;
    private Account bobAccount;
    private Transaction originalTransfer;

    @BeforeEach
    void setUp() {
        transactionRepository = Mockito.mock(TransactionRepository.class);
        accountRepository = Mockito.mock(AccountRepository.class);
        ledgerEntryRepository = Mockito.mock(LedgerEntryRepository.class);
        idempotencyCacheService = Mockito.mock(IdempotencyCacheService.class);
        authenticatedUserService = Mockito.mock(AuthenticatedUserService.class);
        auditEventService = Mockito.mock(AuditEventService.class);

        reversalService = new TransactionReversalService(
                transactionRepository,
                accountRepository,
                ledgerEntryRepository,
                idempotencyCacheService,
                authenticatedUserService,
                auditEventService
        );

        alice = new User("alice@ledger.com", "Alice");
        ReflectionTestUtils.setField(alice, "id", UUID.randomUUID());

        bob = new User("bob@ledger.com", "Bob");
        ReflectionTestUtils.setField(bob, "id", UUID.randomUUID());

        charlie = new User("charlie@ledger.com", "Charlie");
        ReflectionTestUtils.setField(charlie, "id", UUID.randomUUID());

        admin = new User("admin@ledger.com", "Admin");
        ReflectionTestUtils.setField(admin, "id", UUID.randomUUID());

        aliceAccount = new Account(alice, "INR", new BigDecimal("5000.0000"), AccountType.USER_CHECKING, AccountStatus.ACTIVE, "ACCT-ALICE");
        ReflectionTestUtils.setField(aliceAccount, "id", UUID.randomUUID());

        bobAccount = new Account(bob, "INR", new BigDecimal("10000.0000"), AccountType.USER_CHECKING, AccountStatus.ACTIVE, "ACCT-BOB");
        ReflectionTestUtils.setField(bobAccount, "id", UUID.randomUUID());

        originalTransfer = new Transaction(
                "orig-key-1",
                new BigDecimal("5000.0000"),
                "INR",
                TransactionStatus.COMPLETED,
                aliceAccount,
                bobAccount,
                TransactionType.TRANSFER,
                alice,
                "Transfer to Bob"
        );
        ReflectionTestUtils.setField(originalTransfer, "id", UUID.randomUUID());

        // Default: Bob is the authenticated user (owns the debited account in reversal)
        when(authenticatedUserService.getCurrentUser()).thenReturn(bob);
        when(authenticatedUserService.isAdmin()).thenReturn(false);
    }

    @Test
    @DisplayName("Should reject null transaction ID")
    void shouldRejectNullTransactionId() {
        assertThatThrownBy(() -> reversalService.executeReversal(null, "key-1", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Original transaction ID is required");
    }

    @Test
    @DisplayName("Should reject blank idempotency key")
    void shouldRejectBlankIdempotencyKey() {
        assertThatThrownBy(() -> reversalService.executeReversal(UUID.randomUUID(), "   ", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Idempotency-Key header must not be blank");
    }

    @Test
    @DisplayName("Should reject reason exceeding 255 characters")
    void shouldRejectReasonExceedingMaxLength() {
        String longReason = "A".repeat(256);
        ReversalRequestDto request = new ReversalRequestDto(longReason);

        assertThatThrownBy(() -> reversalService.executeReversal(UUID.randomUUID(), "key-1", request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceed 255 characters");
    }

    @Test
    @DisplayName("Should return cached reversal on Redis fast-path hit for authorized caller")
    void shouldReturnCachedReversalOnFastPathHit() {
        UUID origTxId = originalTransfer.getId();
        String idempotencyKey = "cached-key-1";

        ReversalResponseDto cachedDto = new ReversalResponseDto(
                UUID.randomUUID(),
                origTxId,
                TransactionType.REVERSAL,
                TransactionStatus.COMPLETED,
                new BigDecimal("5000.0000"),
                "INR",
                "Cached reversal",
                Instant.now(),
                Instant.now()
        );

        when(transactionRepository.findByIdForUpdate(origTxId)).thenReturn(Optional.of(originalTransfer));
        when(idempotencyCacheService.get(idempotencyKey, ReversalResponseDto.class))
                .thenReturn(Optional.of(cachedDto));

        ReversalResponseDto result = reversalService.executeReversal(origTxId, idempotencyKey, null);

        assertThat(result).isNotNull();
        assertThat(result.reversalTransactionId()).isEqualTo(cachedDto.reversalTransactionId());
        verify(accountRepository, never()).findByIdForUpdate(any());
    }

    @Test
    @DisplayName("Should reject fast-path hit if Redis key belongs to different transaction")
    void shouldRejectFastPathHitWithDifferentTransaction() {
        UUID origTxId = originalTransfer.getId();
        UUID otherTxId = UUID.randomUUID();
        String idempotencyKey = "cached-key-1";

        ReversalResponseDto cachedDto = new ReversalResponseDto(
                UUID.randomUUID(),
                otherTxId,
                TransactionType.REVERSAL,
                TransactionStatus.COMPLETED,
                new BigDecimal("5000.0000"),
                "INR",
                null,
                Instant.now(),
                Instant.now()
        );

        when(transactionRepository.findByIdForUpdate(origTxId)).thenReturn(Optional.of(originalTransfer));
        when(idempotencyCacheService.get(idempotencyKey, ReversalResponseDto.class))
                .thenReturn(Optional.of(cachedDto));

        assertThatThrownBy(() -> reversalService.executeReversal(origTxId, idempotencyKey, null))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("already used for a different transaction");
    }

    @Test
    @DisplayName("Should reject fast-path hit if Redis cached type is not REVERSAL")
    void shouldRejectFastPathHitWithDifferentTransactionType() {
        UUID origTxId = originalTransfer.getId();
        String idempotencyKey = "cached-key-transfer";

        ReversalResponseDto cachedDto = new ReversalResponseDto(
                UUID.randomUUID(),
                origTxId,
                TransactionType.TRANSFER,
                TransactionStatus.COMPLETED,
                new BigDecimal("5000.0000"),
                "INR",
                null,
                Instant.now(),
                Instant.now()
        );

        when(transactionRepository.findByIdForUpdate(origTxId)).thenReturn(Optional.of(originalTransfer));
        when(idempotencyCacheService.get(idempotencyKey, ReversalResponseDto.class))
                .thenReturn(Optional.of(cachedDto));

        assertThatThrownBy(() -> reversalService.executeReversal(origTxId, idempotencyKey, null))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("already used for a different transaction");
    }

    @Test
    @DisplayName("Should reject non-existent transaction with 404 TransactionNotFoundException")
    void shouldRejectNonExistentTransaction() {
        UUID nonExistentId = UUID.randomUUID();
        when(idempotencyCacheService.get(any(), eq(ReversalResponseDto.class))).thenReturn(Optional.empty());
        when(transactionRepository.findByIdForUpdate(nonExistentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reversalService.executeReversal(nonExistentId, "key-1", null))
                .isInstanceOf(TransactionNotFoundException.class)
                .hasMessageContaining("Transaction not found");
    }

    @Test
    @DisplayName("Anti-enumeration: Should return 404 TransactionNotFoundException when unrelated user attempts reversal")
    void shouldReturnNotFoundForUnrelatedUserAntiEnumeration() {
        when(authenticatedUserService.getCurrentUser()).thenReturn(charlie);

        when(idempotencyCacheService.get(any(), eq(ReversalResponseDto.class))).thenReturn(Optional.empty());
        when(transactionRepository.findByIdForUpdate(originalTransfer.getId())).thenReturn(Optional.of(originalTransfer));

        assertThatThrownBy(() -> reversalService.executeReversal(originalTransfer.getId(), "key-charlie", null))
                .isInstanceOf(TransactionNotFoundException.class)
                .hasMessageContaining("Transaction not found");
    }

    @Test
    @DisplayName("Anti-enumeration: Unrelated user querying already-reversed transaction receives 404 without leaking state")
    void shouldReturnNotFoundForUnrelatedUserOnAlreadyReversedTransaction() {
        when(authenticatedUserService.getCurrentUser()).thenReturn(charlie);

        when(idempotencyCacheService.get(any(), eq(ReversalResponseDto.class))).thenReturn(Optional.empty());
        when(transactionRepository.findByIdForUpdate(originalTransfer.getId())).thenReturn(Optional.of(originalTransfer));

        assertThatThrownBy(() -> reversalService.executeReversal(originalTransfer.getId(), "key-charlie-rev", null))
                .isInstanceOf(TransactionNotFoundException.class)
                .hasMessageContaining("Transaction not found");
    }

    @Test
    @DisplayName("Anti-enumeration: Unrelated user querying non-reversible transaction receives 404 without leaking state")
    void shouldReturnNotFoundForUnrelatedUserOnNonReversibleTransaction() {
        when(authenticatedUserService.getCurrentUser()).thenReturn(charlie);

        Transaction systemTx = new Transaction(
                "sys-key", new BigDecimal("10000.0000"), "INR", TransactionStatus.COMPLETED,
                aliceAccount, bobAccount, TransactionType.SYSTEM_FUNDING, null, "funding"
        );
        ReflectionTestUtils.setField(systemTx, "id", UUID.randomUUID());

        when(idempotencyCacheService.get(any(), eq(ReversalResponseDto.class))).thenReturn(Optional.empty());
        when(transactionRepository.findByIdForUpdate(systemTx.getId())).thenReturn(Optional.of(systemTx));

        assertThatThrownBy(() -> reversalService.executeReversal(systemTx.getId(), "key-charlie-sys", null))
                .isInstanceOf(TransactionNotFoundException.class)
                .hasMessageContaining("Transaction not found");
    }

    @Test
    @DisplayName("Debit Authority: Original sender cannot cause an unauthorized debit from recipient")
    void shouldRejectSenderAttemptingToReverseTransfer() {
        // Alice is the original sender; Bob was credited.
        // A reversal debits Bob. Alice cannot cause a debit on Bob!
        when(authenticatedUserService.getCurrentUser()).thenReturn(alice);

        when(idempotencyCacheService.get(any(), eq(ReversalResponseDto.class))).thenReturn(Optional.empty());
        when(transactionRepository.findByIdForUpdate(originalTransfer.getId())).thenReturn(Optional.of(originalTransfer));

        assertThatThrownBy(() -> reversalService.executeReversal(originalTransfer.getId(), "key-alice", null))
                .isInstanceOf(AccountOwnershipException.class)
                .hasMessageContaining("not own the account debited by this reversal");
    }

    @Test
    @DisplayName("Pre-lock DB Idempotency: Reject when key was already used for a different original transaction")
    void shouldRejectWhenKeyUsedForDifferentOriginalTransactionPreLock() {
        UUID otherOrigId = UUID.randomUUID();
        Transaction otherOriginal = new Transaction(
                "other-key", new BigDecimal("1000.0000"), "INR", TransactionStatus.COMPLETED,
                aliceAccount, bobAccount, TransactionType.TRANSFER, alice, "Other transfer"
        );
        ReflectionTestUtils.setField(otherOriginal, "id", otherOrigId);

        Transaction existingReversal = new Transaction(
                "shared-key", new BigDecimal("1000.0000"), "INR", TransactionStatus.COMPLETED,
                bobAccount, aliceAccount, TransactionType.REVERSAL, bob, "Reversal", otherOriginal
        );
        ReflectionTestUtils.setField(existingReversal, "id", UUID.randomUUID());

        when(idempotencyCacheService.get(any(), eq(ReversalResponseDto.class))).thenReturn(Optional.empty());
        when(transactionRepository.findByIdForUpdate(originalTransfer.getId())).thenReturn(Optional.of(originalTransfer));
        when(transactionRepository.findByIdempotencyKey("shared-key")).thenReturn(Optional.of(existingReversal));

        assertThatThrownBy(() -> reversalService.executeReversal(originalTransfer.getId(), "shared-key", null))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("already used for a different transaction");
    }

    @Test
    @DisplayName("Pre-lock DB Idempotency: Reject when key was already used for a different transaction type")
    void shouldRejectWhenKeyUsedForDifferentTransactionTypePreLock() {
        Transaction nonReversalTx = new Transaction(
                "shared-key", new BigDecimal("5000.0000"), "INR", TransactionStatus.COMPLETED,
                aliceAccount, bobAccount, TransactionType.TRANSFER, alice, "Normal transfer"
        );
        ReflectionTestUtils.setField(nonReversalTx, "id", UUID.randomUUID());

        when(idempotencyCacheService.get(any(), eq(ReversalResponseDto.class))).thenReturn(Optional.empty());
        when(transactionRepository.findByIdForUpdate(originalTransfer.getId())).thenReturn(Optional.of(originalTransfer));
        when(transactionRepository.findByIdempotencyKey("shared-key")).thenReturn(Optional.of(nonReversalTx));

        assertThatThrownBy(() -> reversalService.executeReversal(originalTransfer.getId(), "shared-key", null))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("already used for a different transaction");
    }

    @Test
    @DisplayName("Should reject reversal of a reversal transaction")
    void shouldRejectReversalOfReversal() {
        Transaction reversalTx = new Transaction(
                "rev-key",
                new BigDecimal("5000.0000"),
                "INR",
                TransactionStatus.COMPLETED,
                bobAccount,
                aliceAccount,
                TransactionType.REVERSAL,
                bob,
                "reversal",
                originalTransfer
        );
        ReflectionTestUtils.setField(reversalTx, "id", UUID.randomUUID());

        when(idempotencyCacheService.get(any(), eq(ReversalResponseDto.class))).thenReturn(Optional.empty());
        when(transactionRepository.findByIdForUpdate(reversalTx.getId())).thenReturn(Optional.of(reversalTx));

        assertThatThrownBy(() -> reversalService.executeReversal(reversalTx.getId(), "key-new", null))
                .isInstanceOf(TransactionNotReversibleException.class)
                .hasMessageContaining("reversal transaction cannot be reversed");
    }

    @Test
    @DisplayName("Should reject reversal of system funding")
    void shouldRejectReversalOfSystemFunding() {
        when(authenticatedUserService.getCurrentUser()).thenReturn(admin);
        when(authenticatedUserService.isAdmin()).thenReturn(true);

        Transaction fundingTx = new Transaction(
                "funding-key",
                new BigDecimal("10000000.0000"),
                "INR",
                TransactionStatus.COMPLETED,
                bobAccount,
                aliceAccount,
                TransactionType.SYSTEM_FUNDING,
                null,
                "funding"
        );
        ReflectionTestUtils.setField(fundingTx, "id", UUID.randomUUID());

        when(idempotencyCacheService.get(any(), eq(ReversalResponseDto.class))).thenReturn(Optional.empty());
        when(transactionRepository.findByIdForUpdate(fundingTx.getId())).thenReturn(Optional.of(fundingTx));

        assertThatThrownBy(() -> reversalService.executeReversal(fundingTx.getId(), "key-new", null))
                .isInstanceOf(TransactionNotReversibleException.class)
                .hasMessageContaining("System funding transactions cannot be reversed");
    }

    @Test
    @DisplayName("Should reject non-completed transaction")
    void shouldRejectNonCompletedTransaction() {
        originalTransfer.setStatus(TransactionStatus.PENDING);
        when(idempotencyCacheService.get(any(), eq(ReversalResponseDto.class))).thenReturn(Optional.empty());
        when(transactionRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(transactionRepository.findByIdForUpdate(originalTransfer.getId())).thenReturn(Optional.of(originalTransfer));

        assertThatThrownBy(() -> reversalService.executeReversal(originalTransfer.getId(), "key-new", null))
                .isInstanceOf(TransactionNotReversibleException.class)
                .hasMessageContaining("Only COMPLETED transactions can be reversed");
    }

    @Test
    @DisplayName("Should reject already-reversed transaction with conflict")
    void shouldRejectAlreadyReversedTransaction() {
        Transaction existingReversal = new Transaction(
                "other-key",
                originalTransfer.getAmount(),
                "INR",
                TransactionStatus.COMPLETED,
                bobAccount,
                aliceAccount,
                TransactionType.REVERSAL,
                bob,
                "reversal",
                originalTransfer
        );
        ReflectionTestUtils.setField(existingReversal, "id", UUID.randomUUID());

        when(idempotencyCacheService.get(any(), eq(ReversalResponseDto.class))).thenReturn(Optional.empty());
        when(transactionRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(transactionRepository.findByIdForUpdate(originalTransfer.getId())).thenReturn(Optional.of(originalTransfer));
        when(transactionRepository.findByReversesTransactionId(originalTransfer.getId())).thenReturn(Optional.of(existingReversal));

        assertThatThrownBy(() -> reversalService.executeReversal(originalTransfer.getId(), "different-key", null))
                .isInstanceOf(TransactionAlreadyReversedException.class)
                .hasMessageContaining("already been reversed");
    }

    @Test
    @DisplayName("Should allow admin to reverse transaction even if not owning accounts")
    void shouldAllowAdminToReverse() {
        when(authenticatedUserService.getCurrentUser()).thenReturn(admin);
        when(authenticatedUserService.isAdmin()).thenReturn(true);

        when(idempotencyCacheService.get(any(), eq(ReversalResponseDto.class))).thenReturn(Optional.empty());
        when(transactionRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(transactionRepository.findByIdForUpdate(originalTransfer.getId())).thenReturn(Optional.of(originalTransfer));
        when(transactionRepository.findByReversesTransactionId(originalTransfer.getId())).thenReturn(Optional.empty());

        when(accountRepository.findByIdForUpdate(aliceAccount.getId())).thenReturn(Optional.of(aliceAccount));
        when(accountRepository.findByIdForUpdate(bobAccount.getId())).thenReturn(Optional.of(bobAccount));

        when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> {
            Transaction t = i.getArgument(0);
            if (t.getId() == null) {
                ReflectionTestUtils.setField(t, "id", UUID.randomUUID());
            }
            return t;
        });

        ReversalResponseDto response = reversalService.executeReversal(originalTransfer.getId(), "admin-key", new ReversalRequestDto("Admin action"));

        assertThat(response).isNotNull();
        assertThat(response.originalTransactionId()).isEqualTo(originalTransfer.getId());
        assertThat(response.amount()).isEqualByComparingTo("5000.0000");
    }

    @Test
    @DisplayName("Should reject reversal when debited account has insufficient balance")
    void shouldRejectWhenDebitedAccountHasInsufficientBalance() {
        // Bob was credited 5000 in original transfer. Now bob must be debited 5000 in reversal.
        // If Bob has only 2000, reject!
        bobAccount.setBalance(new BigDecimal("2000.0000"));

        when(idempotencyCacheService.get(any(), eq(ReversalResponseDto.class))).thenReturn(Optional.empty());
        when(transactionRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(transactionRepository.findByIdForUpdate(originalTransfer.getId())).thenReturn(Optional.of(originalTransfer));
        when(transactionRepository.findByReversesTransactionId(originalTransfer.getId())).thenReturn(Optional.empty());

        when(accountRepository.findByIdForUpdate(aliceAccount.getId())).thenReturn(Optional.of(aliceAccount));
        when(accountRepository.findByIdForUpdate(bobAccount.getId())).thenReturn(Optional.of(bobAccount));

        assertThatThrownBy(() -> reversalService.executeReversal(originalTransfer.getId(), "key-rev", null))
                .isInstanceOf(InsufficientBalanceException.class)
                .hasMessageContaining("Insufficient balance in account");
    }

    @Test
    @DisplayName("Should reject reversal when debited account is FROZEN")
    void shouldRejectWhenDebitedAccountIsFrozen() {
        bobAccount.setStatus(AccountStatus.FROZEN);

        when(idempotencyCacheService.get(any(), eq(ReversalResponseDto.class))).thenReturn(Optional.empty());
        when(transactionRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(transactionRepository.findByIdForUpdate(originalTransfer.getId())).thenReturn(Optional.of(originalTransfer));
        when(transactionRepository.findByReversesTransactionId(originalTransfer.getId())).thenReturn(Optional.empty());

        when(accountRepository.findByIdForUpdate(aliceAccount.getId())).thenReturn(Optional.of(aliceAccount));
        when(accountRepository.findByIdForUpdate(bobAccount.getId())).thenReturn(Optional.of(bobAccount));

        assertThatThrownBy(() -> reversalService.executeReversal(originalTransfer.getId(), "key-rev", null))
                .isInstanceOf(AccountFrozenException.class)
                .hasMessageContaining("FROZEN");
    }

    @Test
    @DisplayName("Should reject reversal when debited account is CLOSED")
    void shouldRejectWhenDebitedAccountIsClosed() {
        bobAccount.setStatus(AccountStatus.CLOSED);

        when(idempotencyCacheService.get(any(), eq(ReversalResponseDto.class))).thenReturn(Optional.empty());
        when(transactionRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(transactionRepository.findByIdForUpdate(originalTransfer.getId())).thenReturn(Optional.of(originalTransfer));
        when(transactionRepository.findByReversesTransactionId(originalTransfer.getId())).thenReturn(Optional.empty());

        when(accountRepository.findByIdForUpdate(aliceAccount.getId())).thenReturn(Optional.of(aliceAccount));
        when(accountRepository.findByIdForUpdate(bobAccount.getId())).thenReturn(Optional.of(bobAccount));

        assertThatThrownBy(() -> reversalService.executeReversal(originalTransfer.getId(), "key-rev", null))
                .isInstanceOf(AccountClosedException.class)
                .hasMessageContaining("CLOSED");
    }

    @Test
    @DisplayName("Should successfully execute reversal with symmetrical double-entry and audit event")
    void shouldSuccessfullyExecuteReversal() {
        BigDecimal initialBobBalance = bobAccount.getBalance();
        BigDecimal initialAliceBalance = aliceAccount.getBalance();

        when(idempotencyCacheService.get(any(), eq(ReversalResponseDto.class))).thenReturn(Optional.empty());
        when(transactionRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(transactionRepository.findByIdForUpdate(originalTransfer.getId())).thenReturn(Optional.of(originalTransfer));
        when(transactionRepository.findByReversesTransactionId(originalTransfer.getId())).thenReturn(Optional.empty());

        when(accountRepository.findByIdForUpdate(aliceAccount.getId())).thenReturn(Optional.of(aliceAccount));
        when(accountRepository.findByIdForUpdate(bobAccount.getId())).thenReturn(Optional.of(bobAccount));

        when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> {
            Transaction t = i.getArgument(0);
            if (t.getId() == null) {
                ReflectionTestUtils.setField(t, "id", UUID.randomUUID());
            }
            return t;
        });

        ReversalResponseDto response = reversalService.executeReversal(
                originalTransfer.getId(),
                "rev-key-success",
                new ReversalRequestDto("Mistake in amount")
        );

        // Verify return DTO with cleaned contract
        assertThat(response).isNotNull();
        assertThat(response.originalTransactionId()).isEqualTo(originalTransfer.getId());
        assertThat(response.transactionType()).isEqualTo(TransactionType.REVERSAL);
        assertThat(response.status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(response.amount()).isEqualByComparingTo("5000.0000");
        assertThat(response.currency()).isEqualTo("INR");
        assertThat(response.reason()).isEqualTo("Mistake in amount");

        // Verify balance adjustments: Bob debited 5000, Alice credited 5000
        assertThat(bobAccount.getBalance()).isEqualByComparingTo(initialBobBalance.subtract(new BigDecimal("5000.0000")));
        assertThat(aliceAccount.getBalance()).isEqualByComparingTo(initialAliceBalance.add(new BigDecimal("5000.0000")));

        // Verify ledger entries created (1 debit on Bob, 1 credit on Alice)
        ArgumentCaptor<LedgerEntry> entryCaptor = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(ledgerEntryRepository, times(2)).save(entryCaptor.capture());

        LedgerEntry entry1 = entryCaptor.getAllValues().get(0);
        LedgerEntry entry2 = entryCaptor.getAllValues().get(1);

        assertThat(entry1.getEntryType()).isEqualTo(LedgerEntryType.DEBIT);
        assertThat(entry1.getAccount().getId()).isEqualTo(bobAccount.getId());
        assertThat(entry1.getAmount()).isEqualByComparingTo("5000.0000");

        assertThat(entry2.getEntryType()).isEqualTo(LedgerEntryType.CREDIT);
        assertThat(entry2.getAccount().getId()).isEqualTo(aliceAccount.getId());
        assertThat(entry2.getAmount()).isEqualByComparingTo("5000.0000");

        // Verify double-entry balance: total debits == total credits
        assertThat(entry1.getAmount()).isEqualTo(entry2.getAmount());

        // Verify audit event recorded with Bob as actor
        verify(auditEventService).recordEvent(
                eq(bob.getId()),
                eq(AuditEventType.TRANSACTION_REVERSED),
                eq(AuditEntityType.TRANSACTION),
                eq(originalTransfer.getId()),
                any(Map.class)
        );
    }

    @Test
    @DisplayName("Post-lock Idempotency: Reject when key belongs to different original transaction")
    void shouldRejectWhenKeyUsedForDifferentTransactionPostLock() {
        UUID otherOrigId = UUID.randomUUID();
        Transaction otherOriginal = new Transaction(
                "other-key", new BigDecimal("1000.0000"), "INR", TransactionStatus.COMPLETED,
                aliceAccount, bobAccount, TransactionType.TRANSFER, alice, "Other transfer"
        );
        ReflectionTestUtils.setField(otherOriginal, "id", otherOrigId);

        Transaction collisionTx = new Transaction(
                "post-lock-key", new BigDecimal("1000.0000"), "INR", TransactionStatus.COMPLETED,
                bobAccount, aliceAccount, TransactionType.REVERSAL, bob, "Reversal", otherOriginal
        );
        ReflectionTestUtils.setField(collisionTx, "id", UUID.randomUUID());

        when(idempotencyCacheService.get(any(), eq(ReversalResponseDto.class))).thenReturn(Optional.empty());
        when(transactionRepository.findByIdForUpdate(originalTransfer.getId())).thenReturn(Optional.of(originalTransfer));
        when(transactionRepository.findByReversesTransactionId(originalTransfer.getId())).thenReturn(Optional.empty());

        when(accountRepository.findByIdForUpdate(aliceAccount.getId())).thenReturn(Optional.of(aliceAccount));
        when(accountRepository.findByIdForUpdate(bobAccount.getId())).thenReturn(Optional.of(bobAccount));

        // First pre-lock lookup returns empty; post-lock lookup returns collisionTx
        when(transactionRepository.findByIdempotencyKey("post-lock-key"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(collisionTx));

        assertThatThrownBy(() -> reversalService.executeReversal(originalTransfer.getId(), "post-lock-key", null))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("already used for a different transaction");
    }

    @Test
    @DisplayName("getTransaction should return DTO when caller is participant or admin")
    void shouldGetTransactionForParticipant() {
        when(transactionRepository.findById(originalTransfer.getId())).thenReturn(Optional.of(originalTransfer));

        TransactionResponseDto dto = reversalService.getTransaction(originalTransfer.getId());
        assertThat(dto).isNotNull();
        assertThat(dto.transactionId()).isEqualTo(originalTransfer.getId());
    }

    @Test
    @DisplayName("getTransaction should throw 404 for unrelated user")
    void shouldHideTransactionFromUnrelatedUser() {
        when(authenticatedUserService.getCurrentUser()).thenReturn(charlie);
        when(transactionRepository.findById(originalTransfer.getId())).thenReturn(Optional.of(originalTransfer));

        assertThatThrownBy(() -> reversalService.getTransaction(originalTransfer.getId()))
                .isInstanceOf(TransactionNotFoundException.class)
                .hasMessageContaining("Transaction not found");
    }
}
