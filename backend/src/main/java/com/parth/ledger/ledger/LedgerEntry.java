package com.parth.ledger.ledger;

import com.parth.ledger.account.Account;
import com.parth.ledger.transaction.Transaction;
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
@Table(name = "ledger_entries")
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", nullable = false, updatable = false)
    private Transaction transaction;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false, updatable = false)
    private Account account;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 16, updatable = false)
    private LedgerEntryType entryType;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LedgerEntry() {
        // Required by JPA
    }

    public LedgerEntry(Transaction transaction,
                       Account account,
                       LedgerEntryType entryType,
                       BigDecimal amount) {
        this(transaction, account, entryType, amount, transaction != null ? transaction.getCurrency() : (account != null ? account.getCurrency() : null));
    }

    public LedgerEntry(Transaction transaction,
                       Account account,
                       LedgerEntryType entryType,
                       BigDecimal amount,
                       String currency) {
        this.transaction = transaction;
        this.account = account;
        this.entryType = entryType;
        this.amount = amount;
        this.currency = currency != null ? currency.trim().toUpperCase(java.util.Locale.ROOT) : null;
    }

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
        if (this.currency == null) {
            if (this.transaction != null) {
                this.currency = this.transaction.getCurrency();
            } else if (this.account != null) {
                this.currency = this.account.getCurrency();
            }
        }
    }

    public UUID getId() {
        return id;
    }

    public Transaction getTransaction() {
        return transaction;
    }

    public Account getAccount() {
        return account;
    }

    public LedgerEntryType getEntryType() {
        return entryType;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LedgerEntry that = (LedgerEntry) o;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return "LedgerEntry{" +
                "id=" + id +
                ", transactionId=" + (transaction != null ? transaction.getId() : null) +
                ", accountId=" + (account != null ? account.getId() : null) +
                ", entryType=" + entryType +
                ", amount=" + amount +
                ", currency='" + currency + '\'' +
                ", createdAt=" + createdAt +
                '}';
    }
}
