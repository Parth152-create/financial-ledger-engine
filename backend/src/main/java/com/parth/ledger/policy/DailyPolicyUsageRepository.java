package com.parth.ledger.policy;

import com.parth.ledger.transaction.TransactionType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DailyPolicyUsageRepository extends JpaRepository<DailyPolicyUsage, UUID> {

    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO policy_usage_daily (id, account_id, transaction_type, usage_date, amount_used, transaction_count, created_at, updated_at)
            VALUES (gen_random_uuid(), :accountId, :#{#transactionType.name()}, :usageDate, 0, 0, NOW(), NOW())
            ON CONFLICT (account_id, transaction_type, usage_date) DO NOTHING
            """, nativeQuery = true)
    void insertInitialUsageIfAbsent(
            @Param("accountId") UUID accountId,
            @Param("transactionType") TransactionType transactionType,
            @Param("usageDate") LocalDate usageDate
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM DailyPolicyUsage u WHERE u.accountId = :accountId AND u.transactionType = :transactionType AND u.usageDate = :usageDate")
    Optional<DailyPolicyUsage> findByAccountIdAndTransactionTypeAndUsageDateForUpdate(
            @Param("accountId") UUID accountId,
            @Param("transactionType") TransactionType transactionType,
            @Param("usageDate") LocalDate usageDate
    );

    Optional<DailyPolicyUsage> findByAccountIdAndTransactionTypeAndUsageDate(
            UUID accountId,
            TransactionType transactionType,
            LocalDate usageDate
    );
}
