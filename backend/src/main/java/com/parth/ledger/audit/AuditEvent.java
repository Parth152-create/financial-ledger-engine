package com.parth.ledger.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
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
 * Operational audit event entity.
 * Records meaningful business, security, account, and financial operational events.
 * Stored in PostgreSQL with database-level immutability triggers.
 */
@Entity
@Table(name = "audit_events")
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "actor_user_id", updatable = false)
    private UUID actorUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 64, updatable = false)
    private AuditEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 32, updatable = false)
    private AuditEntityType entityType;

    @Column(name = "entity_id", updatable = false)
    private UUID entityId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb", updatable = false)
    private Map<String, Object> metadata;

    @Column(name = "ip_address", length = 45, updatable = false)
    private String ipAddress;

    @Column(name = "user_agent", length = 255, updatable = false)
    private String userAgent;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AuditEvent() {
        // Required by JPA
    }

    public AuditEvent(UUID actorUserId,
                      AuditEventType eventType,
                      AuditEntityType entityType,
                      UUID entityId,
                      Map<String, Object> metadata,
                      String ipAddress,
                      String userAgent) {
        this.actorUserId = actorUserId;
        this.eventType = Objects.requireNonNull(eventType, "eventType must not be null");
        this.entityType = Objects.requireNonNull(entityType, "entityType must not be null");
        this.entityId = entityId;
        this.metadata = metadata != null ? Map.copyOf(metadata) : Collections.emptyMap();
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
    }

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getActorUserId() {
        return actorUserId;
    }

    public AuditEventType getEventType() {
        return eventType;
    }

    public AuditEntityType getEntityType() {
        return entityType;
    }

    public UUID getEntityId() {
        return entityId;
    }

    public Map<String, Object> getMetadata() {
        return metadata != null ? Collections.unmodifiableMap(metadata) : Collections.emptyMap();
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AuditEvent that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "AuditEvent{" +
                "id=" + id +
                ", actorUserId=" + actorUserId +
                ", eventType=" + eventType +
                ", entityType=" + entityType +
                ", entityId=" + entityId +
                ", createdAt=" + createdAt +
                '}';
    }
}
