package com.parth.ledger.reconciliation.service;

import com.parth.ledger.account.Account;
import com.parth.ledger.account.AccountNotFoundException;
import com.parth.ledger.account.AccountRepository;
import com.parth.ledger.ledger.LedgerEntryRepository;
import com.parth.ledger.ledger.LedgerEntryType;
import com.parth.ledger.reconciliation.ReconciliationStatus;
import com.parth.ledger.reconciliation.dto.OverallReconciliationDto;
import com.parth.ledger.reconciliation.dto.ReconciliationResultDto;
import com.parth.ledger.security.AccountOwnershipException;
import com.parth.ledger.security.AuthenticatedUserService;
import com.parth.ledger.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Service performing read-only financial integrity verification and reconciliation.
 *
 * Compares the stored account snapshot balance against the cumulative net balance derived
 * strictly from immutable ledger entries (credits - debits). Never mutates or automatically
 * "repairs" account balances.
 */
@Service
@Transactional(readOnly = true)
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final AuthenticatedUserService authenticatedUserService;
    private final com.parth.ledger.observability.metrics.LedgerMetrics ledgerMetrics;

    @org.springframework.beans.factory.annotation.Autowired
    public ReconciliationService(AccountRepository accountRepository,
                                 LedgerEntryRepository ledgerEntryRepository,
                                 AuthenticatedUserService authenticatedUserService,
                                 @org.springframework.beans.factory.annotation.Autowired(required = false) com.parth.ledger.observability.metrics.LedgerMetrics ledgerMetrics) {
        this.accountRepository = accountRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.authenticatedUserService = authenticatedUserService;
        this.ledgerMetrics = ledgerMetrics;
    }

    public ReconciliationService(AccountRepository accountRepository,
                                 LedgerEntryRepository ledgerEntryRepository,
                                 AuthenticatedUserService authenticatedUserService) {
        this(accountRepository, ledgerEntryRepository, authenticatedUserService, null);
    }

    /**
     * Reconciles an individual account for the currently authenticated user.
     *
     * @param accountId Unique identifier of the account to reconcile.
     * @return ReconciliationResultDto with snapshot, ledger balance, difference, and consistency status.
     * @throws AccountNotFoundException if the account does not exist.
     * @throws AccountOwnershipException if the authenticated user does not own the account.
     */
    public ReconciliationResultDto reconcileAccount(UUID accountId) {
        if (accountId == null) {
            throw new IllegalArgumentException("Account ID must not be null");
        }

        if (ledgerMetrics != null) {
            ledgerMetrics.recordReconciliationRun("SINGLE_ACCOUNT");
        }

        try {
            User currentUser = authenticatedUserService.getCurrentUser();

            Account account = accountRepository.findById(accountId)
                    .orElseThrow(() -> new AccountNotFoundException("Account not found: " + accountId));

            if (account.getUser() == null || !account.getUser().getId().equals(currentUser.getId())) {
                log.warn("Unauthorized reconciliation attempt: user {} does not own account {}",
                        com.parth.ledger.observability.logging.MaskingUtils.maskAccountId(currentUser.getId()),
                        com.parth.ledger.observability.logging.MaskingUtils.maskAccountId(accountId));
                throw new AccountOwnershipException("Authenticated user does not own account: " + accountId);
            }

            if (ledgerMetrics != null) {
                ledgerMetrics.recordReconciliationAccountsChecked("SINGLE_ACCOUNT", 1);
            }

            ReconciliationResultDto result = calculateReconciliation(account);
            if (ledgerMetrics != null) {
                if (result.status() == ReconciliationStatus.CONSISTENT) {
                    ledgerMetrics.recordReconciliationConsistent("SINGLE_ACCOUNT", 1);
                } else {
                    ledgerMetrics.recordReconciliationDiscrepancy("SINGLE_ACCOUNT", 1);
                }
            }
            return result;
        } catch (AccountNotFoundException | AccountOwnershipException | IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException | Error e) {
            if (ledgerMetrics != null) {
                ledgerMetrics.recordReconciliationError("SINGLE_ACCOUNT");
            }
            throw e;
        }
    }

    /**
     * Reconciles an individual account directly without ownership validation.
     * Used for internal platform integrity checks (e.g. SYSTEM_CLEARING).
     *
     * @param accountId Unique identifier of the account to reconcile.
     * @return ReconciliationResultDto with snapshot, ledger balance, difference, and consistency status.
     * @throws AccountNotFoundException if the account does not exist.
     */
    public ReconciliationResultDto reconcileAccountDirectly(UUID accountId) {
        if (accountId == null) {
            throw new IllegalArgumentException("Account ID must not be null");
        }

        if (ledgerMetrics != null) {
            ledgerMetrics.recordReconciliationRun("SYSTEM");
        }

        try {
            Account account = accountRepository.findById(accountId)
                    .orElseThrow(() -> new AccountNotFoundException("Account not found: " + accountId));

            if (ledgerMetrics != null) {
                ledgerMetrics.recordReconciliationAccountsChecked("SYSTEM", 1);
            }

            ReconciliationResultDto result = calculateReconciliation(account);
            if (ledgerMetrics != null) {
                if (result.status() == ReconciliationStatus.CONSISTENT) {
                    ledgerMetrics.recordReconciliationConsistent("SYSTEM", 1);
                } else {
                    ledgerMetrics.recordReconciliationDiscrepancy("SYSTEM", 1);
                }
            }
            return result;
        } catch (AccountNotFoundException | IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException | Error e) {
            if (ledgerMetrics != null) {
                ledgerMetrics.recordReconciliationError("SYSTEM");
            }
            throw e;
        }
    }

    /**
     * Reconciles all accounts owned by the currently authenticated user.
     *
     * @return OverallReconciliationDto summarizing total, consistent, and discrepancy counts.
     */
    public OverallReconciliationDto reconcileUserAccounts() {
        if (ledgerMetrics != null) {
            ledgerMetrics.recordReconciliationRun("USER_ACCOUNTS");
        }

        try {
            User currentUser = authenticatedUserService.getCurrentUser();
            List<Account> userAccounts = accountRepository.findByUserId(currentUser.getId());

            if (ledgerMetrics != null) {
                ledgerMetrics.recordReconciliationAccountsChecked("USER_ACCOUNTS", userAccounts.size());
            }

            List<ReconciliationResultDto> results = new ArrayList<>();
            int consistentCount = 0;
            int discrepancyCount = 0;

            for (Account account : userAccounts) {
                ReconciliationResultDto result = calculateReconciliation(account);
                results.add(result);
                if (result.status() == ReconciliationStatus.CONSISTENT) {
                    consistentCount++;
                } else {
                    discrepancyCount++;
                }
            }

            if (ledgerMetrics != null) {
                ledgerMetrics.recordReconciliationConsistent("USER_ACCOUNTS", consistentCount);
                if (discrepancyCount > 0) {
                    ledgerMetrics.recordReconciliationDiscrepancy("USER_ACCOUNTS", discrepancyCount);
                }
            }

            return new OverallReconciliationDto(
                    userAccounts.size(),
                    consistentCount,
                    discrepancyCount,
                    results,
                    Instant.now()
            );
        } catch (RuntimeException | Error e) {
            if (ledgerMetrics != null) {
                ledgerMetrics.recordReconciliationError("USER_ACCOUNTS");
            }
            throw e;
        }
    }

    /**
     * Computes the reconciliation result for a given account.
     * Purely analytical and read-only: does not modify entity or database state.
     */
    private ReconciliationResultDto calculateReconciliation(Account account) {
        BigDecimal snapshotBalance = account.getBalance() != null
                ? account.getBalance().setScale(4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);

        BigDecimal totalCredits = getSumByEntryType(account.getId(), LedgerEntryType.CREDIT);
        BigDecimal totalDebits = getSumByEntryType(account.getId(), LedgerEntryType.DEBIT);

        // Under current data model without initial_balance column:
        // ledger-derived balance = initial balance (0.0000) + credits - debits
        BigDecimal ledgerBalance = totalCredits.subtract(totalDebits).setScale(4, RoundingMode.HALF_UP);
        BigDecimal difference = snapshotBalance.subtract(ledgerBalance).setScale(4, RoundingMode.HALF_UP);

        ReconciliationStatus status = difference.compareTo(BigDecimal.ZERO) == 0
                ? ReconciliationStatus.CONSISTENT
                : ReconciliationStatus.DISCREPANCY;

        if (status == ReconciliationStatus.DISCREPANCY) {
            log.warn("Financial discrepancy detected for account {}: snapshotBalance={}, ledgerBalance={}, difference={}, status=DISCREPANCY",
                    com.parth.ledger.observability.logging.MaskingUtils.maskAccountId(account.getId()), snapshotBalance, ledgerBalance, difference);
        } else {
            log.debug("Account {} is consistent: snapshotBalance={}, ledgerBalance={}",
                    com.parth.ledger.observability.logging.MaskingUtils.maskAccountId(account.getId()), snapshotBalance, ledgerBalance);
        }

        return new ReconciliationResultDto(
                account.getId(),
                snapshotBalance,
                ledgerBalance,
                difference,
                status,
                totalCredits,
                totalDebits,
                Instant.now()
        );
    }

    private BigDecimal getSumByEntryType(UUID accountId, LedgerEntryType entryType) {
        BigDecimal sum = ledgerEntryRepository.sumAmountByAccountIdAndEntryType(accountId, entryType);
        return sum != null
                ? sum.setScale(4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
    }
}
