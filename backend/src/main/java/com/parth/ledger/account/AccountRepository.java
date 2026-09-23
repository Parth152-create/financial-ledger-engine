package com.parth.ledger.account;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {
    List<Account> findByUserId(UUID userId);

    Optional<Account> findByAccountNumber(String accountNumber);

    List<Account> findByAccountType(AccountType accountType);

    Optional<Account> findByAccountTypeAndCurrency(AccountType accountType, String currency);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.accountNumber = :accountNumber")
    Optional<Account> findByAccountNumberForUpdate(@Param("accountNumber") String accountNumber);

    @Query("SELECT CASE WHEN COUNT(a) > 0 THEN true ELSE false END FROM Account a WHERE a.id = :id AND a.user.id = :userId")
    boolean existsByIdAndUserId(@Param("id") UUID id, @Param("userId") UUID userId);

    List<Account> findByUserIdAndAccountType(UUID userId, AccountType accountType);

    List<Account> findByUserIdAndAccountTypeOrderByCreatedAtAsc(UUID userId, AccountType accountType);

    Optional<Account> findByIdAndUserIdAndAccountType(UUID id, UUID userId, AccountType accountType);

    Optional<Account> findByIdAndUserId(UUID id, UUID userId);
}
