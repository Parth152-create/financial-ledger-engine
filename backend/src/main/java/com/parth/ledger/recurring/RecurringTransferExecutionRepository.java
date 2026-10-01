package com.parth.ledger.recurring;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for RecurringTransferExecution entities.
 */
public interface RecurringTransferExecutionRepository extends JpaRepository<RecurringTransferExecution, UUID> {

    Page<RecurringTransferExecution> findByRecurringTransferIdOrderByScheduledForDesc(UUID recurringTransferId, Pageable pageable);

    Optional<RecurringTransferExecution> findByRecurringTransferIdAndExecutionKey(UUID recurringTransferId, String executionKey);

    boolean existsByRecurringTransferIdAndExecutionKey(UUID recurringTransferId, String executionKey);

    List<RecurringTransferExecution> findByRecurringTransferId(UUID recurringTransferId);

    Optional<RecurringTransferExecution> findByTransactionId(UUID transactionId);

    long countByRecurringTransferIdAndStatus(UUID recurringTransferId, RecurringExecutionStatus status);

    @Query("""
        SELECT e.recurringTransfer.id, COUNT(e)
        FROM RecurringTransferExecution e
        WHERE e.recurringTransfer.id IN :transferIds
          AND e.status = :status
        GROUP BY e.recurringTransfer.id
        """)
    List<Object[]> countByRecurringTransferIdInAndStatus(
            @Param("transferIds") Collection<UUID> transferIds,
            @Param("status") RecurringExecutionStatus status
    );
}
