package com.parth.ledger.ledger;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {
    List<LedgerEntry> findByTransactionId(UUID transactionId);
    List<LedgerEntry> findByAccountId(UUID accountId);

    @Query("SELECT SUM(le.amount) FROM LedgerEntry le WHERE le.account.id = :accountId AND le.entryType = :entryType")
    BigDecimal sumAmountByAccountIdAndEntryType(@Param("accountId") UUID accountId, @Param("entryType") LedgerEntryType entryType);
}
