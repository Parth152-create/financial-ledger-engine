package com.parth.ledger.policy;

import com.parth.ledger.transaction.TransactionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Authoritative financial policy configuration entity.
 * Defines maximum transaction limits, daily amount/count limits, and account balance limits.
 */
@Entity
@Table(name = "financial_policies")
public class FinancialPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "account_id")
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "policy_scope", nullable = false, length = 16)
    private PolicyScope policyScope;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", length = 32)
    private TransactionType transactionType;

    @Enumerated(EnumType.STRING)
    @Column(name = "policy_type", nullable = false, length = 64)
    private PolicyType policyType;

    @Column(name = "amount_limit", precision = 19, scale = 4)
    private BigDecimal amountLimit;

    @Column(name = "count_limit")
    private Integer countLimit;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "INR";

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected FinancialPolicy() {
        // Required by JPA
    }

    public FinancialPolicy(UUID accountId,
                           PolicyScope policyScope,
                           TransactionType transactionType,
                           PolicyType policyType,
                           BigDecimal amountLimit,
                           Integer countLimit,
                           String currency,
                           boolean enabled) {
        this.accountId = accountId;
        this.policyScope = Objects.requireNonNull(policyScope, "policyScope must not be null");
        this.transactionType = transactionType;
        this.policyType = Objects.requireNonNull(policyType, "policyType must not be null");
        this.amountLimit = amountLimit;
        this.countLimit = countLimit;
        this.currency = (currency != null && !currency.isBlank()) ? currency.trim().toUpperCase() : "INR";
        this.enabled = enabled;
    }

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        if (this.updatedAt == null) {
            this.updatedAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public void setAccountId(UUID accountId) {
        this.accountId = accountId;
    }

    public PolicyScope getPolicyScope() {
        return policyScope;
    }

    public void setPolicyScope(PolicyScope policyScope) {
        this.policyScope = policyScope;
    }

    public TransactionType getTransactionType() {
        return transactionType;
    }

    public void setTransactionType(TransactionType transactionType) {
        this.transactionType = transactionType;
    }

    public PolicyType getPolicyType() {
        return policyType;
    }

    public void setPolicyType(PolicyType policyType) {
        this.policyType = policyType;
    }

    public BigDecimal getAmountLimit() {
        return amountLimit;
    }

    public void setAmountLimit(BigDecimal amountLimit) {
        this.amountLimit = amountLimit;
    }

    public Integer getCountLimit() {
        return countLimit;
    }

    public void setCountLimit(Integer countLimit) {
        this.countLimit = countLimit;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FinancialPolicy that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
