package com.parth.ledger.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for OutboxEvent entities.
 * Includes concurrency-safe claim queries utilizing PostgreSQL FOR UPDATE SKIP LOCKED.
 */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID>, JpaSpecificationExecutor<OutboxEvent> {

    /**
     * Atomically selects and locks eligible PENDING outbox events using row-level locking.
     * SKIP LOCKED guarantees concurrent workers skip already-locked rows without blocking or deadlocks.
     *
     * @param limit Maximum number of records to claim.
     * @return List of locked, eligible OutboxEvent records.
     */
    @Query(value = """
        SELECT * FROM outbox_events
        WHERE status = 'PENDING'
          AND available_at <= NOW()
        ORDER BY created_at ASC
        LIMIT :limit
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<OutboxEvent> claimPendingEventsForUpdate(@Param("limit") int limit);

    /**
     * Atomically locks a specific pending event for single-event execution.
     *
     * @param id The outbox event UUID.
     * @return Optional containing the event if eligible and successfully locked.
     */
    @Query(value = """
        SELECT * FROM outbox_events
        WHERE id = :id
          AND status = 'PENDING'
          AND available_at <= NOW()
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    Optional<OutboxEvent> findPendingEventForUpdateById(@Param("id") UUID id);

    Optional<OutboxEvent> findByAggregateIdAndEventType(UUID aggregateId, OutboxEventType eventType);

    List<OutboxEvent> findByAggregateId(UUID aggregateId);

    List<OutboxEvent> findByStatus(OutboxStatus status);

    long countByStatus(OutboxStatus status);
}
