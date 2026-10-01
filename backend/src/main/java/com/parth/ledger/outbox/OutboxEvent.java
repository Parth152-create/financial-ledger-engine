package com.parth.ledger.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Transactional Outbox Event entity.
 * Persisted atomically within the same PostgreSQL transaction as financial and account mutations.
 * Serves as an authoritative, reliable event publication mechanism for downstream integration.
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "aggregate_type", nullable = false, length = 32, updatable = false)
    private OutboxAggregateType aggregateType;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 64, updatable = false)
    private OutboxEventType eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb", nullable = false, updatable = false)
    private Map<String, Object> payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private OutboxStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "available_at", nullable = false)
    private Instant availableAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "last_error")
    private String lastError;

    protected OutboxEvent() {
        // Required by JPA
    }

    public OutboxEvent(UUID id,
                       OutboxAggregateType aggregateType,
                       UUID aggregateId,
                       OutboxEventType eventType,
                       Map<String, Object> payload,
                       OutboxStatus status,
                       int attemptCount,
                       Instant availableAt,
                       Instant processedAt,
                       String lastError) {
        this.id = id != null ? id : UUID.randomUUID();
        this.aggregateType = Objects.requireNonNull(aggregateType, "aggregateType must not be null");
        this.aggregateId = Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        this.eventType = Objects.requireNonNull(eventType, "eventType must not be null");
        this.payload = payload != null ? Map.copyOf(payload) : Collections.emptyMap();
        this.status = status != null ? status : OutboxStatus.PENDING;
        this.attemptCount = Math.max(0, attemptCount);
        this.availableAt = availableAt != null ? availableAt : Instant.now();
        this.processedAt = processedAt;
        this.lastError = lastError;
    }

    public OutboxEvent(OutboxAggregateType aggregateType,
                       UUID aggregateId,
                       OutboxEventType eventType,
                       Map<String, Object> payload) {
        this(UUID.randomUUID(), aggregateType, aggregateId, eventType, payload, OutboxStatus.PENDING, 0, Instant.now(), null, null);
    }

    @PrePersist
    protected void onCreate() {
        if (this.id == null) {
            this.id = UUID.randomUUID();
        }
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
        if (this.availableAt == null) {
            this.availableAt = this.createdAt;
        }
        if (this.status == null) {
            this.status = OutboxStatus.PENDING;
        }
    }

    public UUID getId() {
        return id;
    }

    public OutboxAggregateType getAggregateType() {
        return aggregateType;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public OutboxEventType getEventType() {
        return eventType;
    }

    public Map<String, Object> getPayload() {
        return payload != null ? Collections.unmodifiableMap(payload) : Collections.emptyMap();
    }

    public OutboxStatus getStatus() {
        return status;
    }

    public void setStatus(OutboxStatus status) {
        this.status = Objects.requireNonNull(status, "status must not be null");
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(int attemptCount) {
        if (attemptCount < 0) {
            throw new IllegalArgumentException("attemptCount cannot be negative");
        }
        this.attemptCount = attemptCount;
    }

    public Instant getAvailableAt() {
        return availableAt;
    }

    public void setAvailableAt(Instant availableAt) {
        this.availableAt = Objects.requireNonNull(availableAt, "availableAt must not be null");
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    public void setProcessedAt(Instant processedAt) {
        this.processedAt = processedAt;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OutboxEvent that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "OutboxEvent{" +
                "id=" + id +
                ", aggregateType=" + aggregateType +
                ", aggregateId=" + aggregateId +
                ", eventType=" + eventType +
                ", status=" + status +
                ", attemptCount=" + attemptCount +
                ", availableAt=" + availableAt +
                ", createdAt=" + createdAt +
                ", processedAt=" + processedAt +
                '}';
    }
}
