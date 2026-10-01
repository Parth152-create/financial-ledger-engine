package com.parth.ledger.recurring;

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

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Entity representing an immutable execution attempt for a specific scheduled slot of a recurring transfer.
 * Preserves audit history and prevents duplicate execution per slot.
 */
@Entity
@Table(name = "recurring_transfer_executions")
public class RecurringTransferExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recurring_transfer_id", nullable = false, updatable = false)
    private RecurringTransfer recurringTransfer;

    @Column(name = "execution_key", nullable = false, length = 64, updatable = false)
    private String executionKey;

    @Column(name = "scheduled_for", nullable = false, updatable = false)
    private Instant scheduledFor;

    @Column(name = "transaction_id", updatable = false)
    private UUID transactionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32, updatable = false)
    private RecurringExecutionStatus status;

    @Column(name = "failure_reason", columnDefinition = "text", updatable = false)
    private String failureReason;

    @Column(name = "executed_at", nullable = false, updatable = false)
    private Instant executedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected RecurringTransferExecution() {
        // Required by JPA
    }

    public RecurringTransferExecution(RecurringTransfer recurringTransfer,
                                      String executionKey,
                                      Instant scheduledFor,
                                      UUID transactionId,
                                      RecurringExecutionStatus status,
                                      String failureReason,
                                      Instant executedAt) {
        this.recurringTransfer = Objects.requireNonNull(recurringTransfer, "recurringTransfer must not be null");
        this.executionKey = Objects.requireNonNull(executionKey, "executionKey must not be null");
        this.scheduledFor = Objects.requireNonNull(scheduledFor, "scheduledFor must not be null");
        this.transactionId = transactionId;
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.failureReason = failureReason;
        this.executedAt = executedAt != null ? executedAt : Instant.now();
    }

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        if (this.executedAt == null) {
            this.executedAt = now;
        }
    }

    public UUID getId() {
        return id;
    }

    public RecurringTransfer getRecurringTransfer() {
        return recurringTransfer;
    }

    public String getExecutionKey() {
        return executionKey;
    }

    public Instant getScheduledFor() {
        return scheduledFor;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public RecurringExecutionStatus getStatus() {
        return status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getExecutedAt() {
        return executedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RecurringTransferExecution that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
