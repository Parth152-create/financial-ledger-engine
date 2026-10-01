package com.parth.ledger.policy;

import com.parth.ledger.transaction.TransactionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FinancialPolicyRepository extends JpaRepository<FinancialPolicy, UUID> {

    Optional<FinancialPolicy> findByAccountIdAndPolicyTypeAndTransactionTypeAndEnabledTrue(
            UUID accountId,
            PolicyType policyType,
            TransactionType transactionType
    );

    Optional<FinancialPolicy> findByAccountIdAndPolicyTypeAndTransactionTypeIsNullAndEnabledTrue(
            UUID accountId,
            PolicyType policyType
    );

    Optional<FinancialPolicy> findByPolicyScopeAndPolicyTypeAndTransactionTypeAndEnabledTrue(
            PolicyScope policyScope,
            PolicyType policyType,
            TransactionType transactionType
    );

    Optional<FinancialPolicy> findByPolicyScopeAndPolicyTypeAndTransactionTypeIsNullAndEnabledTrue(
            PolicyScope policyScope,
            PolicyType policyType
    );

    boolean existsByAccountIdAndPolicyTypeAndTransactionType(
            UUID accountId,
            PolicyType policyType,
            TransactionType transactionType
    );

    boolean existsByAccountIdAndPolicyTypeAndTransactionTypeIsNull(
            UUID accountId,
            PolicyType policyType
    );

    boolean existsByPolicyScopeAndPolicyTypeAndTransactionType(
            PolicyScope policyScope,
            PolicyType policyType,
            TransactionType transactionType
    );

    boolean existsByPolicyScopeAndPolicyTypeAndTransactionTypeIsNull(
            PolicyScope policyScope,
            PolicyType policyType
    );

    List<FinancialPolicy> findAllByOrderByCreatedAtDesc();

    List<FinancialPolicy> findByAccountIdOrderByCreatedAtDesc(UUID accountId);

    List<FinancialPolicy> findByPolicyScopeOrderByCreatedAtDesc(PolicyScope policyScope);
}
