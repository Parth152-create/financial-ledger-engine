package com.parth.ledger.account;

import com.parth.ledger.account.dto.AccountResponseDto;
import com.parth.ledger.account.dto.CreateAccountRequestDto;
import com.parth.ledger.security.AuthenticatedUserService;
import com.parth.ledger.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Service managing user checking accounts.
 *
 * Enforces ownership boundaries, strict account type invariants (users can only manage USER_CHECKING accounts),
 * and automatic server-side account number generation. System clearing accounts (SYSTEM_CLEARING) are strictly
 * excluded from user visibility and creation.
 */
@Service
@Transactional(readOnly = true)
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final AccountRepository accountRepository;
    private final AuthenticatedUserService authenticatedUserService;

    public AccountService(AccountRepository accountRepository,
                          AuthenticatedUserService authenticatedUserService) {
        this.accountRepository = accountRepository;
        this.authenticatedUserService = authenticatedUserService;
    }

    /**
     * Creates a new USER_CHECKING account for the currently authenticated user.
     *
     * @param request Account creation parameters containing currency.
     * @return AccountResponseDto of the newly created account.
     * @throws IllegalArgumentException if the request or currency is invalid.
     */
    @Transactional
    public AccountResponseDto createAccount(CreateAccountRequestDto request) {
        if (request == null) {
            throw new IllegalArgumentException("Account creation request body must not be null");
        }

        String currency = request.currency();
        if (currency == null || !currency.matches("^[A-Z]{3}$")) {
            throw new IllegalArgumentException("Currency must be exactly 3 uppercase alphabetic characters (ISO 4217)");
        }

        User currentUser = authenticatedUserService.getCurrentUser();

        // Enforce strict V5 creation invariants:
        // 1. Account type must always be USER_CHECKING (never SYSTEM_CLEARING)
        // 2. Status must always be ACTIVE
        // 3. Balance must always start at 0.0000
        // 4. User ownership is bound to the authenticated principal
        // 5. Account number is generated server-side
        Account account = new Account(
                currentUser,
                currency,
                BigDecimal.ZERO.setScale(4),
                AccountType.USER_CHECKING,
                AccountStatus.ACTIVE,
                null // triggers server-side account number generation
        );

        Account saved = accountRepository.save(account);
        log.info("Created USER_CHECKING account: id={}, accountNumber={}, currency={}, userId={}",
                saved.getId(), saved.getAccountNumber(), saved.getCurrency(), currentUser.getId());

        return AccountResponseDto.from(saved);
    }

    /**
     * Lists all USER_CHECKING accounts owned by the currently authenticated user.
     * Guaranteed to exclude another user's accounts and platform SYSTEM_CLEARING accounts.
     *
     * @return List of AccountResponseDto for the authenticated user.
     */
    public List<AccountResponseDto> listUserAccounts() {
        User currentUser = authenticatedUserService.getCurrentUser();
        List<Account> accounts = accountRepository.findByUserIdAndAccountTypeOrderByCreatedAtAsc(
                currentUser.getId(),
                AccountType.USER_CHECKING
        );

        return accounts.stream()
                .map(AccountResponseDto::from)
                .toList();
    }

    /**
     * Retrieves a single USER_CHECKING account owned by the authenticated user.
     * If the account does not exist, belongs to another user, or is a SYSTEM_CLEARING account,
     * throws AccountNotFoundException (HTTP 404) to prevent account enumeration.
     *
     * @param accountId Unique identifier of the account.
     * @return AccountResponseDto of the requested account.
     * @throws AccountNotFoundException if the account is not found or not accessible to the user.
     */
    public AccountResponseDto getAccount(UUID accountId) {
        if (accountId == null) {
            throw new IllegalArgumentException("Account ID must not be null");
        }

        User currentUser = authenticatedUserService.getCurrentUser();

        Account account = accountRepository.findByIdAndUserIdAndAccountType(
                accountId,
                currentUser.getId(),
                AccountType.USER_CHECKING
        ).orElseThrow(() -> {
            log.warn("Account {} not found or unauthorized for user {}", accountId, currentUser.getId());
            return new AccountNotFoundException("Account not found: " + accountId);
        });

        return AccountResponseDto.from(account);
    }
}
