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
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * PostgreSQL-authoritative daily usage record for financial transaction limits.
 * Tracks cumulative amount used and transaction count per account, transaction type, and calendar date.
 */
@Entity
@Table(name = "policy_usage_daily")
public class DailyPolicyUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, length = 32)
    private TransactionType transactionType;

    @Column(name = "usage_date", nullable = false)
    private LocalDate usageDate;

    @Column(name = "amount_used", precision = 19, scale = 4, nullable = false)
    private BigDecimal amountUsed = BigDecimal.ZERO.setScale(4);

    @Column(name = "transaction_count", nullable = false)
    private int transactionCount = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DailyPolicyUsage() {
        // Required by JPA
    }

    public DailyPolicyUsage(UUID accountId,
                            TransactionType transactionType,
                            LocalDate usageDate,
                            BigDecimal amountUsed,
                            int transactionCount) {
        this.accountId = Objects.requireNonNull(accountId, "accountId must not be null");
        this.transactionType = Objects.requireNonNull(transactionType, "transactionType must not be null");
        this.usageDate = Objects.requireNonNull(usageDate, "usageDate must not be null");
        this.amountUsed = amountUsed != null ? amountUsed : BigDecimal.ZERO.setScale(4);
        this.transactionCount = transactionCount;
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

    public TransactionType getTransactionType() {
        return transactionType;
    }

    public LocalDate getUsageDate() {
        return usageDate;
    }

    public BigDecimal getAmountUsed() {
        return amountUsed;
    }

    public void setAmountUsed(BigDecimal amountUsed) {
        this.amountUsed = amountUsed;
    }

    public int getTransactionCount() {
        return transactionCount;
    }

    public void setTransactionCount(int transactionCount) {
        this.transactionCount = transactionCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DailyPolicyUsage that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
