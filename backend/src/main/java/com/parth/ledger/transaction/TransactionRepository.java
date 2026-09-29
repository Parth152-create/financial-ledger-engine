package com.parth.ledger.transaction;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, UUID>, JpaSpecificationExecutor<Transaction> {
    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);

    List<Transaction> findByTransactionType(TransactionType transactionType);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Transaction t WHERE t.id = :id")
    Optional<Transaction> findByIdForUpdate(@Param("id") UUID id);

    @Query("SELECT t FROM Transaction t WHERE t.reversesTransaction.id = :originalTxId")
    Optional<Transaction> findByReversesTransactionId(@Param("originalTxId") UUID originalTxId);

    @Query("SELECT CASE WHEN COUNT(t) > 0 THEN true ELSE false END FROM Transaction t WHERE t.reversesTransaction.id = :originalTxId")
    boolean existsByReversesTransactionId(@Param("originalTxId") UUID originalTxId);

    @Query("SELECT t FROM Transaction t WHERE t.reversesTransaction.id IN :originalTxIds")
    List<Transaction> findByReversesTransactionIdIn(@Param("originalTxIds") Collection<UUID> originalTxIds);
}
