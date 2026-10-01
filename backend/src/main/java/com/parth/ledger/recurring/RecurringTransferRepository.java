package com.parth.ledger.recurring;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for RecurringTransfer entities.
 * Includes concurrency-safe claim queries utilizing PostgreSQL FOR UPDATE SKIP LOCKED.
 */
public interface RecurringTransferRepository extends JpaRepository<RecurringTransfer, UUID> {

    /**
     * Atomically claims eligible ACTIVE schedules due on or before asOf using row-level locking.
     * SKIP LOCKED guarantees concurrent workers skip already-locked rows without blocking or deadlocks.
     *
     * @param asOf  Threshold timestamp for eligibility.
     * @param limit Maximum number of records to claim.
     * @return List of locked, eligible RecurringTransfer records.
     */
    @Query(value = """
        SELECT * FROM recurring_transfers
        WHERE status = 'ACTIVE'
          AND next_execution_at <= :asOf
        ORDER BY next_execution_at ASC
        LIMIT :limit
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<RecurringTransfer> claimEligibleSchedulesForUpdate(@Param("asOf") Instant asOf, @Param("limit") int limit);

    /**
     * Atomically locks a specific schedule by ID if active and due for single-schedule execution.
     *
     * @param id   The schedule UUID.
     * @param asOf Threshold timestamp for eligibility.
     * @return Optional containing the locked schedule if eligible.
     */
    @Query(value = """
        SELECT * FROM recurring_transfers
        WHERE id = :id
          AND status = 'ACTIVE'
          AND next_execution_at <= :asOf
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    Optional<RecurringTransfer> findEligibleScheduleForUpdateById(@Param("id") UUID id, @Param("asOf") Instant asOf);

    /**
     * Locks a schedule by ID for state mutations (e.g. pause, resume, cancel).
     *
     * @param id The schedule UUID.
     * @return Optional containing the locked schedule if found.
     */
    @Query(value = """
        SELECT * FROM recurring_transfers
        WHERE id = :id
        FOR UPDATE
        """, nativeQuery = true)
    Optional<RecurringTransfer> findByIdForUpdate(@Param("id") UUID id);

    Page<RecurringTransfer> findByUserId(UUID userId, Pageable pageable);

    Page<RecurringTransfer> findByUserIdAndStatus(UUID userId, RecurringTransferStatus status, Pageable pageable);

    Optional<RecurringTransfer> findByIdAndUserId(UUID id, UUID userId);

    long countByUserIdAndStatus(UUID userId, RecurringTransferStatus status);
}
