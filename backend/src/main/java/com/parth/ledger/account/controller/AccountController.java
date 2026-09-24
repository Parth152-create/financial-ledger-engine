package com.parth.ledger.account.controller;

import com.parth.ledger.account.AccountService;
import com.parth.ledger.account.dto.AccountResponseDto;
import com.parth.ledger.account.dto.CreateAccountRequestDto;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for authenticated account management operations.
 *
 * Provides endpoints for:
 * - Creating retail USER_CHECKING accounts (POST /api/v1/accounts)
 * - Listing all accounts owned by the authenticated caller (GET /api/v1/accounts)
 * - Retrieving a single owned account by ID (GET /api/v1/accounts/{accountId})
 */
@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    /**
     * Creates a new USER_CHECKING account for the authenticated user.
     *
     * @param request Account creation payload containing currency.
     * @return 201 Created with the created AccountResponseDto.
     */
    @PostMapping
    public ResponseEntity<AccountResponseDto> createAccount(@Valid @RequestBody CreateAccountRequestDto request) {
        AccountResponseDto response = accountService.createAccount(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Lists all accounts owned by the authenticated user.
     *
     * @return 200 OK with List of AccountResponseDto.
     */
    @GetMapping
    public ResponseEntity<List<AccountResponseDto>> listAccounts() {
        List<AccountResponseDto> accounts = accountService.listUserAccounts();
        return ResponseEntity.ok(accounts);
    }

    /**
     * Retrieves an account by ID if owned by the authenticated user.
     *
     * @param accountId Unique identifier of the account.
     * @return 200 OK with AccountResponseDto.
     */
    @GetMapping("/{accountId}")
    public ResponseEntity<AccountResponseDto> getAccount(@PathVariable("accountId") UUID accountId) {
        AccountResponseDto account = accountService.getAccount(accountId);
        return ResponseEntity.ok(account);
    }

    /**
     * Freezes a USER_CHECKING account. Administrative operation (requires ROLE_ADMIN).
     *
     * @param accountId Unique identifier of the account to freeze.
     * @return 200 OK with AccountResponseDto.
     */
    @PostMapping("/{accountId}/freeze")
    public ResponseEntity<AccountResponseDto> freezeAccount(@PathVariable("accountId") UUID accountId) {
        AccountResponseDto response = accountService.freezeAccount(accountId);
        return ResponseEntity.ok(response);
    }

    /**
     * Unfreezes a USER_CHECKING account. Administrative operation (requires ROLE_ADMIN).
     *
     * @param accountId Unique identifier of the account to unfreeze.
     * @return 200 OK with AccountResponseDto.
     */
    @PostMapping("/{accountId}/unfreeze")
    public ResponseEntity<AccountResponseDto> unfreezeAccount(@PathVariable("accountId") UUID accountId) {
        AccountResponseDto response = accountService.unfreezeAccount(accountId);
        return ResponseEntity.ok(response);
    }

    /**
     * Closes an authenticated user's USER_CHECKING account. Owner-level operation.
     *
     * @param accountId Unique identifier of the account to close.
     * @return 200 OK with AccountResponseDto.
     */
    @PostMapping("/{accountId}/close")
    public ResponseEntity<AccountResponseDto> closeAccount(@PathVariable("accountId") UUID accountId) {
        AccountResponseDto response = accountService.closeAccount(accountId);
        return ResponseEntity.ok(response);
    }
}
