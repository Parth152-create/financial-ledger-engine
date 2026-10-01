package com.parth.ledger.policy;

import com.parth.ledger.account.Account;
import com.parth.ledger.account.AccountNotFoundException;
import com.parth.ledger.account.AccountRepository;
import com.parth.ledger.account.AccountType;
import com.parth.ledger.audit.AuditEntityType;
import com.parth.ledger.audit.AuditEventService;
import com.parth.ledger.audit.AuditEventType;
import com.parth.ledger.policy.dto.AccountLimitSummaryDto;
import com.parth.ledger.policy.dto.CreatePolicyRequestDto;
import com.parth.ledger.policy.dto.FinancialPolicyResponseDto;
import com.parth.ledger.policy.dto.UpdatePolicyRequestDto;
import com.parth.ledger.security.AuthenticatedUserService;
import com.parth.ledger.transaction.TransactionType;
import com.parth.ledger.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Service managing financial policies and coordinating atomic policy evaluations
 * within active financial database transactions.
 *
 * Daily policy quota windows reset authoritatively at 00:00 UTC every day.
 */
@Service
public class PolicyService {

    private static final Logger log = LoggerFactory.getLogger(PolicyService.class);

    private final FinancialPolicyRepository financialPolicyRepository;
    private final DailyPolicyUsageRepository dailyPolicyUsageRepository;
    private final PolicyEvaluator policyEvaluator;
    private final AccountRepository accountRepository;
    private final AuditEventService auditEventService;
    private final AuthenticatedUserService authenticatedUserService;
    private final Clock clock;
    private final com.parth.ledger.observability.metrics.LedgerMetrics ledgerMetrics;

    @Autowired
    public PolicyService(FinancialPolicyRepository financialPolicyRepository,
                         DailyPolicyUsageRepository dailyPolicyUsageRepository,
                         PolicyEvaluator policyEvaluator,
                         AccountRepository accountRepository,
                         AuditEventService auditEventService,
                         AuthenticatedUserService authenticatedUserService,
                         Clock clock,
                         @Autowired(required = false) com.parth.ledger.observability.metrics.LedgerMetrics ledgerMetrics) {
        this.financialPolicyRepository = financialPolicyRepository;
        this.dailyPolicyUsageRepository = dailyPolicyUsageRepository;
        this.policyEvaluator = policyEvaluator;
        this.accountRepository = accountRepository;
        this.auditEventService = auditEventService;
        this.authenticatedUserService = authenticatedUserService;
        this.clock = clock;
        this.ledgerMetrics = ledgerMetrics;
    }

    public PolicyService(FinancialPolicyRepository financialPolicyRepository,
                         DailyPolicyUsageRepository dailyPolicyUsageRepository,
                         PolicyEvaluator policyEvaluator,
                         AccountRepository accountRepository,
                         AuditEventService auditEventService,
                         AuthenticatedUserService authenticatedUserService,
                         Clock clock) {
        this(financialPolicyRepository, dailyPolicyUsageRepository, policyEvaluator, accountRepository, auditEventService, authenticatedUserService, clock, null);
    }

    public PolicyService(FinancialPolicyRepository financialPolicyRepository,
                         DailyPolicyUsageRepository dailyPolicyUsageRepository,
                         PolicyEvaluator policyEvaluator,
                         AccountRepository accountRepository,
                         AuditEventService auditEventService,
                         AuthenticatedUserService authenticatedUserService) {
        this(financialPolicyRepository, dailyPolicyUsageRepository, policyEvaluator, accountRepository, auditEventService, authenticatedUserService, Clock.systemUTC(), null);
    }

    /**
     * Resolves the authoritative applicable policy following deterministic precedence:
     * 1. Account-specific policy (if configured and enabled)
     * 2. Global policy (if configured and enabled)
     * 3. Optional.empty() (No policy = unlimited)
     * If an account-specific policy exists but is disabled, global policy remains applicable.
     */
    public Optional<FinancialPolicy> resolveApplicablePolicy(UUID accountId, PolicyType policyType, TransactionType txType) {
        if (accountId != null) {
            Optional<FinancialPolicy> accountPolicy = (txType != null)
                    ? financialPolicyRepository.findByAccountIdAndPolicyTypeAndTransactionTypeAndEnabledTrue(accountId, policyType, txType)
                    : financialPolicyRepository.findByAccountIdAndPolicyTypeAndTransactionTypeIsNullAndEnabledTrue(accountId, policyType);
            if (accountPolicy.isPresent()) {
                return accountPolicy;
            }
        }

        return (txType != null)
                ? financialPolicyRepository.findByPolicyScopeAndPolicyTypeAndTransactionTypeAndEnabledTrue(PolicyScope.GLOBAL, policyType, txType)
                : financialPolicyRepository.findByPolicyScopeAndPolicyTypeAndTransactionTypeIsNullAndEnabledTrue(PolicyScope.GLOBAL, policyType);
    }

    /**
     * Obtains and locks the daily usage counter row under pessimistic write lock (FOR UPDATE).
     * If absent, inserts an initial row atomically using PostgreSQL ON CONFLICT DO NOTHING.
     */
    public DailyPolicyUsage getOrCreateDailyUsageForUpdate(UUID accountId, TransactionType txType, LocalDate usageDate) {
        dailyPolicyUsageRepository.insertInitialUsageIfAbsent(accountId, txType, usageDate);
        return dailyPolicyUsageRepository.findByAccountIdAndTransactionTypeAndUsageDateForUpdate(accountId, txType, usageDate)
                .orElseThrow(() -> new IllegalStateException("Failed to acquire lock on daily policy usage for account " + accountId));
    }

    private void evaluatePolicy(Optional<FinancialPolicy> policy, String txType, java.util.function.Consumer<FinancialPolicy> evaluation) {
        if (policy.isPresent()) {
            FinancialPolicy p = policy.get();
            if (ledgerMetrics != null) {
                ledgerMetrics.recordPolicyEvaluation(p.getPolicyType().name(), txType);
            }
            try {
                evaluation.accept(p);
            } catch (PolicyViolationException ex) {
                if (ledgerMetrics != null) {
                    ledgerMetrics.recordPolicyRejection(p.getPolicyType().name(), txType);
                }
                throw ex;
            }
        }
    }

    /**
     * Evaluates transfer limits for source (debit) and destination (credit) accounts, and reserves daily quota.
     * Must be called while holding pessimistic row-level locks on both accounts.
     */
    @Transactional
    public void evaluateAndRecordTransferLimits(Account sourceAccount, Account destinationAccount, BigDecimal amount) {
        LocalDate today = LocalDate.now(this.clock);

        // 1. Evaluate Source Max Transaction Amount
        Optional<FinancialPolicy> maxTxPolicy = resolveApplicablePolicy(sourceAccount.getId(), PolicyType.MAX_TRANSACTION_AMOUNT, TransactionType.TRANSFER);
        evaluatePolicy(maxTxPolicy, "TRANSFER", p -> policyEvaluator.evaluateMaxTransactionAmount(p, amount));

        // 2. Lock Source Daily Policy Usage Row
        DailyPolicyUsage sourceUsage = getOrCreateDailyUsageForUpdate(sourceAccount.getId(), TransactionType.TRANSFER, today);

        // 3. Evaluate Source Daily Amount Limit
        Optional<FinancialPolicy> dailyAmountPolicy = resolveApplicablePolicy(sourceAccount.getId(), PolicyType.DAILY_TRANSACTION_AMOUNT, TransactionType.TRANSFER);
        evaluatePolicy(dailyAmountPolicy, "TRANSFER", p -> policyEvaluator.evaluateDailyAmountLimit(p, sourceUsage.getAmountUsed(), amount));

        // 4. Evaluate Source Daily Count Limit
        Optional<FinancialPolicy> dailyCountPolicy = resolveApplicablePolicy(sourceAccount.getId(), PolicyType.DAILY_TRANSACTION_COUNT, TransactionType.TRANSFER);
        evaluatePolicy(dailyCountPolicy, "TRANSFER", p -> policyEvaluator.evaluateDailyCountLimit(p, sourceUsage.getTransactionCount()));

        // 5. Evaluate Destination Account Balance Limit (credit operation)
        Optional<FinancialPolicy> balancePolicy = resolveApplicablePolicy(destinationAccount.getId(), PolicyType.ACCOUNT_BALANCE_LIMIT, null);
        evaluatePolicy(balancePolicy, "TRANSFER", p -> policyEvaluator.evaluateAccountBalanceLimit(p, destinationAccount.getBalance(), amount));

        // 6. Record Quota Consumption for Source Account
        sourceUsage.setAmountUsed(sourceUsage.getAmountUsed().add(amount));
        sourceUsage.setTransactionCount(sourceUsage.getTransactionCount() + 1);
        dailyPolicyUsageRepository.save(sourceUsage);

        log.debug("Consumed transfer policy quota for source account {}: new amountUsed={}, new count={}",
                sourceAccount.getId(), sourceUsage.getAmountUsed(), sourceUsage.getTransactionCount());
    }

    /**
     * Evaluates deposit limits for user destination account and reserves daily quota.
     * Must be called while holding pessimistic write lock on the destination account.
     */
    @Transactional
    public void evaluateAndRecordDepositLimits(Account userAccount, BigDecimal amount) {
        LocalDate today = LocalDate.now(this.clock);

        // 1. Evaluate Max Deposit Amount
        Optional<FinancialPolicy> maxTxPolicy = resolveApplicablePolicy(userAccount.getId(), PolicyType.MAX_TRANSACTION_AMOUNT, TransactionType.DEPOSIT);
        evaluatePolicy(maxTxPolicy, "DEPOSIT", p -> policyEvaluator.evaluateMaxTransactionAmount(p, amount));

        // 2. Lock User Deposit Daily Usage Row
        DailyPolicyUsage userUsage = getOrCreateDailyUsageForUpdate(userAccount.getId(), TransactionType.DEPOSIT, today);

        // 3. Evaluate User Daily Deposit Amount Limit
        Optional<FinancialPolicy> dailyAmountPolicy = resolveApplicablePolicy(userAccount.getId(), PolicyType.DAILY_TRANSACTION_AMOUNT, TransactionType.DEPOSIT);
        evaluatePolicy(dailyAmountPolicy, "DEPOSIT", p -> policyEvaluator.evaluateDailyAmountLimit(p, userUsage.getAmountUsed(), amount));

        // 4. Evaluate User Daily Deposit Count Limit
        Optional<FinancialPolicy> dailyCountPolicy = resolveApplicablePolicy(userAccount.getId(), PolicyType.DAILY_TRANSACTION_COUNT, TransactionType.DEPOSIT);
        evaluatePolicy(dailyCountPolicy, "DEPOSIT", p -> policyEvaluator.evaluateDailyCountLimit(p, userUsage.getTransactionCount()));

        // 5. Evaluate User Account Balance Limit (credit operation)
        Optional<FinancialPolicy> balancePolicy = resolveApplicablePolicy(userAccount.getId(), PolicyType.ACCOUNT_BALANCE_LIMIT, null);
        evaluatePolicy(balancePolicy, "DEPOSIT", p -> policyEvaluator.evaluateAccountBalanceLimit(p, userAccount.getBalance(), amount));

        // 6. Record Quota Consumption for User Account
        userUsage.setAmountUsed(userUsage.getAmountUsed().add(amount));
        userUsage.setTransactionCount(userUsage.getTransactionCount() + 1);
        dailyPolicyUsageRepository.save(userUsage);

        log.debug("Consumed deposit policy quota for account {}: new amountUsed={}, new count={}",
                userAccount.getId(), userUsage.getAmountUsed(), userUsage.getTransactionCount());
    }

    /**
     * Evaluates withdrawal limits for user source account and reserves daily quota.
     * Must be called while holding pessimistic write lock on the source account.
     */
    @Transactional
    public void evaluateAndRecordWithdrawalLimits(Account userAccount, BigDecimal amount) {
        LocalDate today = LocalDate.now(this.clock);

        // 1. Evaluate Max Withdrawal Amount
        Optional<FinancialPolicy> maxTxPolicy = resolveApplicablePolicy(userAccount.getId(), PolicyType.MAX_TRANSACTION_AMOUNT, TransactionType.WITHDRAWAL);
        evaluatePolicy(maxTxPolicy, "WITHDRAWAL", p -> policyEvaluator.evaluateMaxTransactionAmount(p, amount));

        // 2. Lock User Withdrawal Daily Usage Row
        DailyPolicyUsage userUsage = getOrCreateDailyUsageForUpdate(userAccount.getId(), TransactionType.WITHDRAWAL, today);

        // 3. Evaluate User Daily Withdrawal Amount Limit
        Optional<FinancialPolicy> dailyAmountPolicy = resolveApplicablePolicy(userAccount.getId(), PolicyType.DAILY_TRANSACTION_AMOUNT, TransactionType.WITHDRAWAL);
        evaluatePolicy(dailyAmountPolicy, "WITHDRAWAL", p -> policyEvaluator.evaluateDailyAmountLimit(p, userUsage.getAmountUsed(), amount));

        // 4. Evaluate User Daily Withdrawal Count Limit
        Optional<FinancialPolicy> dailyCountPolicy = resolveApplicablePolicy(userAccount.getId(), PolicyType.DAILY_TRANSACTION_COUNT, TransactionType.WITHDRAWAL);
        evaluatePolicy(dailyCountPolicy, "WITHDRAWAL", p -> policyEvaluator.evaluateDailyCountLimit(p, userUsage.getTransactionCount()));

        // Note: Withdrawals decrease balance, so ACCOUNT_BALANCE_LIMIT does not block withdrawals.

        // 5. Record Quota Consumption for User Account
        userUsage.setAmountUsed(userUsage.getAmountUsed().add(amount));
        userUsage.setTransactionCount(userUsage.getTransactionCount() + 1);
        dailyPolicyUsageRepository.save(userUsage);

        log.debug("Consumed withdrawal policy quota for account {}: new amountUsed={}, new count={}",
                userAccount.getId(), userUsage.getAmountUsed(), userUsage.getTransactionCount());
    }

    /**
     * Resolves the user-facing limit and usage summary for an owned USER_CHECKING account.
     * Enforces anti-enumeration and account ownership boundaries.
     */
    @Transactional(readOnly = true)
    public AccountLimitSummaryDto getAccountLimitSummary(UUID accountId, TransactionType transactionType) {
        validateAccountAccess(accountId);
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + accountId));

        TransactionType txType = transactionType != null ? transactionType : TransactionType.TRANSFER;
        LocalDate today = LocalDate.now(this.clock);

        Optional<FinancialPolicy> maxTxPolicy = resolveApplicablePolicy(accountId, PolicyType.MAX_TRANSACTION_AMOUNT, txType);
        Optional<FinancialPolicy> dailyAmountPolicy = resolveApplicablePolicy(accountId, PolicyType.DAILY_TRANSACTION_AMOUNT, txType);
        Optional<FinancialPolicy> dailyCountPolicy = resolveApplicablePolicy(accountId, PolicyType.DAILY_TRANSACTION_COUNT, txType);
        Optional<FinancialPolicy> balancePolicy = resolveApplicablePolicy(accountId, PolicyType.ACCOUNT_BALANCE_LIMIT, null);

        Optional<DailyPolicyUsage> usageOpt = dailyPolicyUsageRepository.findByAccountIdAndTransactionTypeAndUsageDate(
                accountId, txType, today
        );

        BigDecimal dailyAmountUsed = usageOpt.map(DailyPolicyUsage::getAmountUsed).orElse(BigDecimal.ZERO.setScale(4));
        int dailyCountUsed = usageOpt.map(DailyPolicyUsage::getTransactionCount).orElse(0);

        BigDecimal maxTxAmount = maxTxPolicy.map(FinancialPolicy::getAmountLimit).orElse(null);
        BigDecimal dailyAmountLimit = dailyAmountPolicy.map(FinancialPolicy::getAmountLimit).orElse(null);
        Integer dailyCountLimit = dailyCountPolicy.map(FinancialPolicy::getCountLimit).orElse(null);
        BigDecimal balanceLimit = balancePolicy.map(FinancialPolicy::getAmountLimit).orElse(null);

        BigDecimal dailyAmountRemaining = null;
        if (dailyAmountLimit != null) {
            dailyAmountRemaining = dailyAmountLimit.subtract(dailyAmountUsed).max(BigDecimal.ZERO).setScale(4, RoundingMode.HALF_UP);
        }

        Integer dailyCountRemaining = null;
        if (dailyCountLimit != null) {
            dailyCountRemaining = Math.max(0, dailyCountLimit - dailyCountUsed);
        }

        BigDecimal currentBalance = account.getBalance();
        BigDecimal balanceRemaining = null;
        if (balanceLimit != null) {
            balanceRemaining = balanceLimit.subtract(currentBalance).max(BigDecimal.ZERO).setScale(4, RoundingMode.HALF_UP);
        }

        return new AccountLimitSummaryDto(
                accountId,
                txType,
                maxTxAmount,
                dailyAmountLimit,
                dailyAmountUsed,
                dailyAmountRemaining,
                dailyCountLimit,
                dailyCountUsed,
                dailyCountRemaining,
                currentBalance,
                balanceLimit,
                balanceLimit,
                balanceRemaining,
                "INR"
        );
    }

    /**
     * Returns limit summaries for all supported transaction types for an owned account.
     */
    @Transactional(readOnly = true)
    public List<AccountLimitSummaryDto> getAllAccountLimits(UUID accountId) {
        validateAccountAccess(accountId);
        List<AccountLimitSummaryDto> result = new ArrayList<>();
        for (TransactionType txType : List.of(TransactionType.TRANSFER, TransactionType.DEPOSIT, TransactionType.WITHDRAWAL)) {
            result.add(getAccountLimitSummary(accountId, txType));
        }
        return result;
    }

    /**
     * Administrative Policy Creation (ROLE_ADMIN only).
     */
    @Transactional
    public FinancialPolicyResponseDto createPolicy(CreatePolicyRequestDto request) {
        validateAdmin();
        if (request == null) {
            throw new IllegalArgumentException("Policy request body must not be null");
        }
        if (request.policyScope() == null) {
            throw new IllegalArgumentException("Policy scope is required");
        }
        if (request.policyType() == null) {
            throw new IllegalArgumentException("Policy type is required");
        }

        // Scope validation
        if (request.policyScope() == PolicyScope.GLOBAL && request.accountId() != null) {
            throw new IllegalArgumentException("Global policy cannot specify an account ID");
        }
        if (request.policyScope() == PolicyScope.ACCOUNT && request.accountId() == null) {
            throw new IllegalArgumentException("Account-scoped policy must specify an account ID");
        }

        // Validate account if specified
        if (request.accountId() != null) {
            Account account = accountRepository.findById(request.accountId())
                    .orElseThrow(() -> new AccountNotFoundException("Account not found: " + request.accountId()));
            if (account.getAccountType() != AccountType.USER_CHECKING) {
                throw new IllegalArgumentException("Policies can only be configured on USER_CHECKING accounts: " + request.accountId());
            }
        }

        // Transaction type validation
        if (request.policyType() == PolicyType.ACCOUNT_BALANCE_LIMIT) {
            if (request.transactionType() != null) {
                throw new IllegalArgumentException("Account balance limit policy cannot specify a transaction type");
            }
        } else {
            if (request.transactionType() == null) {
                throw new IllegalArgumentException("Transaction type is required for policy type: " + request.policyType());
            }
            if (request.transactionType() != TransactionType.TRANSFER
                    && request.transactionType() != TransactionType.DEPOSIT
                    && request.transactionType() != TransactionType.WITHDRAWAL) {
                throw new IllegalArgumentException("Unsupported transaction type for policy: " + request.transactionType());
            }
        }

        // Limit validation
        BigDecimal scaledAmount = null;
        Integer countLimit = null;
        if (request.policyType() == PolicyType.DAILY_TRANSACTION_COUNT) {
            if (request.countLimit() == null || request.countLimit() <= 0) {
                throw new IllegalArgumentException("Count limit must be a positive integer");
            }
            if (request.amountLimit() != null) {
                throw new IllegalArgumentException("Count limit policy cannot specify an amount limit");
            }
            countLimit = request.countLimit();
        } else {
            if (request.amountLimit() == null || request.amountLimit().compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Amount limit must be greater than zero");
            }
            if (request.amountLimit().stripTrailingZeros().scale() > 4) {
                throw new IllegalArgumentException("Amount limit precision cannot exceed 4 decimal places");
            }
            if (request.countLimit() != null) {
                throw new IllegalArgumentException("Amount limit policy cannot specify a count limit");
            }
            scaledAmount = request.amountLimit().setScale(4, RoundingMode.HALF_UP);
        }

        // Currency validation (INR only)
        String currency = "INR";
        if (request.currency() != null && !request.currency().isBlank()) {
            String norm = request.currency().trim().toUpperCase();
            if (!"INR".equals(norm)) {
                throw new IllegalArgumentException("Only INR currency is supported: " + request.currency());
            }
        }

        // Duplicate policy prevention
        boolean exists;
        if (request.policyScope() == PolicyScope.ACCOUNT) {
            exists = (request.transactionType() != null)
                    ? financialPolicyRepository.existsByAccountIdAndPolicyTypeAndTransactionType(request.accountId(), request.policyType(), request.transactionType())
                    : financialPolicyRepository.existsByAccountIdAndPolicyTypeAndTransactionTypeIsNull(request.accountId(), request.policyType());
        } else {
            exists = (request.transactionType() != null)
                    ? financialPolicyRepository.existsByPolicyScopeAndPolicyTypeAndTransactionType(PolicyScope.GLOBAL, request.policyType(), request.transactionType())
                    : financialPolicyRepository.existsByPolicyScopeAndPolicyTypeAndTransactionTypeIsNull(PolicyScope.GLOBAL, request.policyType());
        }

        if (exists) {
            throw new DuplicatePolicyException("A policy already exists for the specified scope, type, and transaction combination");
        }

        boolean enabled = request.enabled() == null || request.enabled();

        FinancialPolicy policy = new FinancialPolicy(
                request.accountId(),
                request.policyScope(),
                request.transactionType(),
                request.policyType(),
                scaledAmount,
                countLimit,
                currency,
                enabled
        );

        FinancialPolicy saved = financialPolicyRepository.save(policy);
        log.info("Admin created financial policy: id={}, scope={}, type={}, txType={}",
                saved.getId(), saved.getPolicyScope(), saved.getPolicyType(), saved.getTransactionType());

        // Record POLICY_CREATED operational audit event
        recordPolicyAuditEvent(saved, AuditEventType.POLICY_CREATED);

        return FinancialPolicyResponseDto.from(saved);
    }

    /**
     * Administrative Policy Update (ROLE_ADMIN only).
     */
    @Transactional
    public FinancialPolicyResponseDto updatePolicy(UUID policyId, UpdatePolicyRequestDto request) {
        validateAdmin();
        if (policyId == null) {
            throw new IllegalArgumentException("Policy ID must not be null");
        }
        if (request == null) {
            throw new IllegalArgumentException("Update payload must not be null");
        }

        FinancialPolicy policy = financialPolicyRepository.findById(policyId)
                .orElseThrow(() -> new PolicyNotFoundException("Policy not found: " + policyId));

        if (request.enabled() != null) {
            policy.setEnabled(request.enabled());
        }

        if (policy.getPolicyType() == PolicyType.DAILY_TRANSACTION_COUNT) {
            if (request.amountLimit() != null) {
                throw new IllegalArgumentException("Count limit policy cannot specify an amount limit");
            }
            if (request.countLimit() != null) {
                if (request.countLimit() <= 0) {
                    throw new IllegalArgumentException("Count limit must be a positive integer");
                }
                policy.setCountLimit(request.countLimit());
            }
        } else {
            if (request.countLimit() != null) {
                throw new IllegalArgumentException("Amount limit policy cannot specify a count limit");
            }
            if (request.amountLimit() != null) {
                if (request.amountLimit().compareTo(BigDecimal.ZERO) <= 0) {
                    throw new IllegalArgumentException("Amount limit must be greater than zero");
                }
                if (request.amountLimit().stripTrailingZeros().scale() > 4) {
                    throw new IllegalArgumentException("Amount limit precision cannot exceed 4 decimal places");
                }
                policy.setAmountLimit(request.amountLimit().setScale(4, RoundingMode.HALF_UP));
            }
        }

        FinancialPolicy saved = financialPolicyRepository.save(policy);
        log.info("Admin updated financial policy: id={}, enabled={}", saved.getId(), saved.isEnabled());

        recordPolicyAuditEvent(saved, AuditEventType.POLICY_UPDATED);

        return FinancialPolicyResponseDto.from(saved);
    }

    /**
     * Administrative Policy Deletion (ROLE_ADMIN only).
     */
    @Transactional
    public void deletePolicy(UUID policyId) {
        validateAdmin();
        if (policyId == null) {
            throw new IllegalArgumentException("Policy ID must not be null");
        }

        FinancialPolicy policy = financialPolicyRepository.findById(policyId)
                .orElseThrow(() -> new PolicyNotFoundException("Policy not found: " + policyId));

        recordPolicyAuditEvent(policy, AuditEventType.POLICY_DELETED);

        financialPolicyRepository.delete(policy);
        log.info("Admin deleted financial policy: id={}", policyId);
    }

    /**
     * Administrative Policy Listing (ROLE_ADMIN only).
     */
    @Transactional(readOnly = true)
    public List<FinancialPolicyResponseDto> listPolicies(UUID accountId, PolicyScope scope) {
        validateAdmin();
        List<FinancialPolicy> policies;
        if (accountId != null) {
            policies = financialPolicyRepository.findByAccountIdOrderByCreatedAtDesc(accountId);
        } else if (scope != null) {
            policies = financialPolicyRepository.findByPolicyScopeOrderByCreatedAtDesc(scope);
        } else {
            policies = financialPolicyRepository.findAllByOrderByCreatedAtDesc();
        }
        return policies.stream().map(FinancialPolicyResponseDto::from).toList();
    }

    /**
     * Administrative Policy Retrieval (ROLE_ADMIN only).
     */
    @Transactional(readOnly = true)
    public FinancialPolicyResponseDto getPolicy(UUID policyId) {
        validateAdmin();
        FinancialPolicy policy = financialPolicyRepository.findById(policyId)
                .orElseThrow(() -> new PolicyNotFoundException("Policy not found: " + policyId));
        return FinancialPolicyResponseDto.from(policy);
    }

    private void validateAdmin() {
        if (!authenticatedUserService.isAdmin()) {
            throw new org.springframework.security.access.AccessDeniedException("Administrative privileges required for policy management");
        }
    }

    private void validateAccountAccess(UUID accountId) {
        if (accountId == null) {
            throw new IllegalArgumentException("Account ID must not be null");
        }
        User currentUser = authenticatedUserService.getCurrentUser();
        boolean isAdmin = authenticatedUserService.isAdmin();

        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + accountId));

        if (account.getAccountType() != AccountType.USER_CHECKING) {
            throw new AccountNotFoundException("Account not found: " + accountId);
        }

        if (!isAdmin) {
            if (account.getUser() == null || !account.getUser().getId().equals(currentUser.getId())) {
                log.warn("Unauthorized policy limit query for account {} by user {}", accountId, currentUser.getId());
                throw new AccountNotFoundException("Account not found: " + accountId);
            }
        }
    }

    private void recordPolicyAuditEvent(FinancialPolicy policy, AuditEventType eventType) {
        UUID actorId = null;
        try {
            actorId = authenticatedUserService.getCurrentUser().getId();
        } catch (Exception ignored) {
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("policyId", policy.getId());
        metadata.put("policyScope", policy.getPolicyScope().name());
        metadata.put("policyType", policy.getPolicyType().name());
        if (policy.getAccountId() != null) {
            metadata.put("accountId", policy.getAccountId());
        }
        if (policy.getTransactionType() != null) {
            metadata.put("transactionType", policy.getTransactionType().name());
        }
        if (policy.getAmountLimit() != null) {
            metadata.put("amountLimit", policy.getAmountLimit());
        }
        if (policy.getCountLimit() != null) {
            metadata.put("countLimit", policy.getCountLimit());
        }
        metadata.put("currency", policy.getCurrency());
        metadata.put("enabled", policy.isEnabled());

        auditEventService.recordEvent(
                actorId,
                eventType,
                AuditEntityType.POLICY,
                policy.getId(),
                metadata
        );
    }
}
