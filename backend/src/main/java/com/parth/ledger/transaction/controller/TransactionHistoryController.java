package com.parth.ledger.transaction.controller;

import com.parth.ledger.transaction.dto.TransactionHistoryPageResponseDto;
import com.parth.ledger.transaction.service.TransactionHistoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST controller for retrieving account transaction history.
 *
 * Provides a read-only, paginated, and filtered view of transactions
 * associated with a user-owned checking account.
 */
@RestController
@RequestMapping("/api/v1/accounts/{accountId}/transactions")
public class TransactionHistoryController {

    private final TransactionHistoryService transactionHistoryService;

    public TransactionHistoryController(TransactionHistoryService transactionHistoryService) {
        this.transactionHistoryService = transactionHistoryService;
    }

    /**
     * Retrieves paginated transaction history for the specified user checking account.
     *
     * @param accountId       ID of the account (must belong to authenticated caller).
     * @param transactionType Optional transaction type filter (TRANSFER, DEPOSIT, WITHDRAWAL).
     * @param status          Optional transaction status filter (PENDING, COMPLETED, FAILED).
     * @param from            Optional ISO-8601 start timestamp (inclusive).
     * @param to              Optional ISO-8601 end timestamp (exclusive).
     * @param page            Zero-based page index (default: 0).
     * @param size            Page size (default: 20, max: 100).
     * @return 200 OK with paginated transaction history.
     */
    @GetMapping
    public ResponseEntity<TransactionHistoryPageResponseDto> getTransactionHistory(
            @PathVariable("accountId") UUID accountId,
            @RequestParam(name = "transactionType", required = false) String transactionType,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size
    ) {
        TransactionHistoryPageResponseDto response = transactionHistoryService.getTransactionHistory(
                accountId,
                transactionType,
                status,
                from,
                to,
                page,
                size
        );
        return ResponseEntity.ok(response);
    }
}
