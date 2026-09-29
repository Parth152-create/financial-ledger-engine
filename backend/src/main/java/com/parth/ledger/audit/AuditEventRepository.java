package com.parth.ledger.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for AuditEvent entities.
 * Supports specification-based filtered queries and pagination.
 */
@Repository
public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID>, JpaSpecificationExecutor<AuditEvent> {

    /**
     * Checks if a completion audit event already exists for a given transaction and event type.
     */
    boolean existsByEntityTypeAndEntityIdAndEventType(
            AuditEntityType entityType,
            UUID entityId,
            AuditEventType eventType
    );

    /**
     * Finds an audit event by entity type, entity id, and event type.
     */
    Optional<AuditEvent> findByEntityTypeAndEntityIdAndEventType(
            AuditEntityType entityType,
            UUID entityId,
            AuditEventType eventType
    );

    /**
     * Finds all audit events for a given entity type and entity id.
     */
    java.util.List<AuditEvent> findByEntityTypeAndEntityId(
            AuditEntityType entityType,
            UUID entityId
    );

    /**
     * Finds all audit events by event type.
     */
    java.util.List<AuditEvent> findByEventType(
            AuditEventType eventType
    );
}
