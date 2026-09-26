package com.parth.ledger.account;

import com.parth.ledger.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal balance;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 32)
    private AccountType accountType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private AccountStatus status;

    @Column(name = "account_number", nullable = false, unique = true, length = 32)
    private String accountNumber;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static final String PLATFORM_CURRENCY = "INR";

    protected Account() {
        // Required by JPA
    }

    public Account(User user) {
        this(user, PLATFORM_CURRENCY, BigDecimal.ZERO.setScale(4), AccountType.USER_CHECKING, AccountStatus.ACTIVE, null);
    }

    public Account(User user, BigDecimal balance) {
        this(user, PLATFORM_CURRENCY, balance, AccountType.USER_CHECKING, AccountStatus.ACTIVE, null);
    }

    public Account(User user, String currency) {
        this(user, currency != null ? currency : PLATFORM_CURRENCY, BigDecimal.ZERO.setScale(4), AccountType.USER_CHECKING, AccountStatus.ACTIVE, null);
    }

    public Account(User user, String currency, BigDecimal balance) {
        this(user, currency != null ? currency : PLATFORM_CURRENCY, balance, AccountType.USER_CHECKING, AccountStatus.ACTIVE, null);
    }

    public Account(User user, String currency, BigDecimal balance, AccountType accountType, AccountStatus status) {
        this(user, currency != null ? currency : PLATFORM_CURRENCY, balance, accountType, status, null);
    }

    public Account(User user, String currency, BigDecimal balance, AccountType accountType, AccountStatus status, String accountNumber) {
        this.user = user;
        this.currency = currency != null ? currency.trim().toUpperCase(Locale.ROOT) : PLATFORM_CURRENCY;
        this.balance = balance != null ? balance.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(4);
        this.accountType = accountType != null ? accountType : AccountType.USER_CHECKING;
        this.status = status != null ? status : AccountStatus.ACTIVE;
        this.accountNumber = accountNumber != null ? accountNumber : generateAccountNumber();
    }

    public static Account createSystemClearingAccount(String currency, String accountNumber) {
        Account account = new Account();
        account.accountType = AccountType.SYSTEM_CLEARING;
        account.status = AccountStatus.ACTIVE;
        account.currency = currency != null ? currency.trim().toUpperCase(Locale.ROOT) : PLATFORM_CURRENCY;
        account.balance = BigDecimal.ZERO.setScale(4);
        account.accountNumber = accountNumber != null ? accountNumber : generateAccountNumber();
        return account;
    }

    public static Account createSystemTreasuryAccount(String currency, String accountNumber) {
        Account account = new Account();
        account.accountType = AccountType.SYSTEM_TREASURY;
        account.status = AccountStatus.ACTIVE;
        account.currency = currency != null ? currency.trim().toUpperCase(Locale.ROOT) : PLATFORM_CURRENCY;
        account.balance = BigDecimal.ZERO.setScale(4);
        account.accountNumber = accountNumber != null ? accountNumber : generateAccountNumber();
        return account;
    }

    public static String generateAccountNumber() {
        return "ACCT-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
    }

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        if (this.updatedAt == null) {
            this.updatedAt = now;
        }
        if (this.balance == null) {
            this.balance = BigDecimal.ZERO.setScale(4);
        }
        if (this.accountType == null) {
            this.accountType = AccountType.USER_CHECKING;
        }
        if (this.status == null) {
            this.status = AccountStatus.ACTIVE;
        }
        if (this.accountNumber == null || this.accountNumber.isBlank()) {
            this.accountNumber = generateAccountNumber();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public void setBalance(BigDecimal balance) {
        this.balance = balance;
    }

    public AccountType getAccountType() {
        return accountType;
    }

    public void setAccountType(AccountType accountType) {
        this.accountType = accountType;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public void setStatus(AccountStatus status) {
        this.status = status;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public void setAccountNumber(String accountNumber) {
        this.accountNumber = accountNumber;
    }

    public boolean isActive() {
        return this.status == AccountStatus.ACTIVE;
    }

    public boolean isFrozen() {
        return this.status == AccountStatus.FROZEN;
    }

    public boolean isClosed() {
        return this.status == AccountStatus.CLOSED;
    }

    public boolean isUserChecking() {
        return this.accountType == AccountType.USER_CHECKING;
    }

    public boolean isSystemClearing() {
        return this.accountType == AccountType.SYSTEM_CLEARING;
    }

    public boolean isSystemTreasury() {
        return this.accountType == AccountType.SYSTEM_TREASURY;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Account account = (Account) o;
        return id != null && Objects.equals(id, account.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return "Account{" +
                "id=" + id +
                ", userId=" + (user != null ? user.getId() : null) +
                ", accountNumber='" + accountNumber + '\'' +
                ", accountType=" + accountType +
                ", status=" + status +
                ", currency='" + currency + '\'' +
                ", balance=" + balance +
                ", version=" + version +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                '}';
    }
}
