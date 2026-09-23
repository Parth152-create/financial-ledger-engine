package com.parth.ledger.transaction;

import com.parth.ledger.account.Account;
import com.parth.ledger.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "transactions")
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, unique = true, updatable = false)
    private String idempotencyKey;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private TransactionStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, length = 32, updatable = false)
    private TransactionType transactionType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "initiated_by_user_id", updatable = false)
    private User initiatedByUser;

    @Column(name = "description", length = 255, updatable = false)
    private String description;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_account_id", nullable = false, updatable = false)
    private Account sourceAccount;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "destination_account_id", nullable = false, updatable = false)
    private Account destinationAccount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected Transaction() {
        // Required by JPA
    }

    public Transaction(String idempotencyKey,
                       BigDecimal amount,
                       String currency,
                       TransactionStatus status,
                       Account sourceAccount,
                       Account destinationAccount) {
        this(idempotencyKey, amount, currency, status, sourceAccount, destinationAccount, TransactionType.TRANSFER, sourceAccount != null ? sourceAccount.getUser() : null, null);
    }

    public Transaction(String idempotencyKey,
                       BigDecimal amount,
                       String currency,
                       TransactionStatus status,
                       Account sourceAccount,
                       Account destinationAccount,
                       TransactionType transactionType,
                       User initiatedByUser,
                       String description) {
        this.idempotencyKey = idempotencyKey;
        this.amount = amount;
        this.currency = currency;
        this.status = status;
        this.sourceAccount = sourceAccount;
        this.destinationAccount = destinationAccount;
        this.transactionType = transactionType != null ? transactionType : TransactionType.TRANSFER;
        this.initiatedByUser = initiatedByUser != null ? initiatedByUser : (sourceAccount != null ? sourceAccount.getUser() : null);
        this.description = description;
    }

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
        if (this.transactionType == null) {
            this.transactionType = TransactionType.TRANSFER;
        }
        if (this.initiatedByUser == null && this.sourceAccount != null) {
            this.initiatedByUser = this.sourceAccount.getUser();
        }
    }

    public UUID getId() {
        return id;
    }

    public TransactionType getTransactionType() {
        return transactionType;
    }

    public User getInitiatedByUser() {
        return initiatedByUser;
    }

    public String getDescription() {
        return description;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public void setStatus(TransactionStatus status) {
        this.status = status;
    }

    public Account getSourceAccount() {
        return sourceAccount;
    }

    public Account getDestinationAccount() {
        return destinationAccount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Transaction that = (Transaction) o;
        return Objects.equals(idempotencyKey, that.idempotencyKey);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(idempotencyKey);
    }

    @Override
    public String toString() {
        return "Transaction{" +
                "id=" + id +
                ", idempotencyKey='" + idempotencyKey + '\'' +
                ", amount=" + amount +
                ", currency='" + currency + '\'' +
                ", status=" + status +
                ", transactionType=" + transactionType +
                ", initiatedByUserId=" + (initiatedByUser != null ? initiatedByUser.getId() : null) +
                ", description='" + description + '\'' +
                ", sourceAccountId=" + (sourceAccount != null ? sourceAccount.getId() : null) +
                ", destinationAccountId=" + (destinationAccount != null ? destinationAccount.getId() : null) +
                ", createdAt=" + createdAt +
                ", completedAt=" + completedAt +
                '}';
    }
}
