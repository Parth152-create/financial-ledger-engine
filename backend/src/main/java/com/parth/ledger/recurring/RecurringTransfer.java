package com.parth.ledger.recurring;

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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Entity representing a recurring transfer schedule definition.
 *
 * A recurring transfer is SCHEDULING METADATA, not a separate financial execution engine.
 * Financial executions reuse TransferService atomically and idempotently.
 */
@Entity
@Table(name = "recurring_transfers")
public class RecurringTransfer {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_account_id", nullable = false, updatable = false)
    private Account sourceAccount;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "destination_account_id", nullable = false, updatable = false)
    private Account destinationAccount;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "frequency", nullable = false, length = 32, updatable = false)
    private RecurringFrequency frequency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private RecurringTransferStatus status;

    @Column(name = "next_execution_at", nullable = false)
    private Instant nextExecutionAt;

    @Column(name = "start_date", nullable = false, updatable = false)
    private LocalDate startDate;

    @Column(name = "end_date", updatable = false)
    private LocalDate endDate;

    @Column(name = "execution_count", nullable = false)
    private int executionCount = 0;

    @Column(name = "last_executed_at")
    private Instant lastExecutedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected RecurringTransfer() {
        // Required by JPA
    }

    public RecurringTransfer(User user,
                             Account sourceAccount,
                             Account destinationAccount,
                             BigDecimal amount,
                             String currency,
                             RecurringFrequency frequency,
                             LocalDate startDate,
                             LocalDate endDate,
                             Instant nextExecutionAt) {
        this.user = Objects.requireNonNull(user, "user must not be null");
        this.sourceAccount = Objects.requireNonNull(sourceAccount, "sourceAccount must not be null");
        this.destinationAccount = Objects.requireNonNull(destinationAccount, "destinationAccount must not be null");
        this.amount = Objects.requireNonNull(amount, "amount must not be null");
        this.currency = Objects.requireNonNull(currency, "currency must not be null");
        this.frequency = Objects.requireNonNull(frequency, "frequency must not be null");
        this.status = RecurringTransferStatus.ACTIVE;
        this.startDate = Objects.requireNonNull(startDate, "startDate must not be null");
        this.endDate = endDate;
        this.nextExecutionAt = Objects.requireNonNull(nextExecutionAt, "nextExecutionAt must not be null");
        this.executionCount = 0;
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

    public User getUser() {
        return user;
    }

    public Account getSourceAccount() {
        return sourceAccount;
    }

    public Account getDestinationAccount() {
        return destinationAccount;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public RecurringFrequency getFrequency() {
        return frequency;
    }

    public RecurringTransferStatus getStatus() {
        return status;
    }

    public void setStatus(RecurringTransferStatus status) {
        this.status = Objects.requireNonNull(status, "status must not be null");
    }

    public Instant getNextExecutionAt() {
        return nextExecutionAt;
    }

    public void setNextExecutionAt(Instant nextExecutionAt) {
        this.nextExecutionAt = Objects.requireNonNull(nextExecutionAt, "nextExecutionAt must not be null");
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public int getExecutionCount() {
        return executionCount;
    }

    public void incrementExecutionCount() {
        this.executionCount++;
    }

    public Instant getLastExecutedAt() {
        return lastExecutedAt;
    }

    public void setLastExecutedAt(Instant lastExecutedAt) {
        this.lastExecutedAt = lastExecutedAt;
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
        if (!(o instanceof RecurringTransfer that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
