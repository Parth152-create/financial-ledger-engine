package com.parth.ledger.statement.controller;

import com.parth.ledger.statement.dto.AccountStatementResponseDto;
import com.parth.ledger.statement.service.AccountStatementService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/accounts/{accountId}/statement")
public class AccountStatementController {

    private final AccountStatementService statementService;

    public AccountStatementController(AccountStatementService statementService) {
        this.statementService = statementService;
    }

    @GetMapping
    public ResponseEntity<AccountStatementResponseDto> getStatement(
            @PathVariable("accountId") UUID accountId,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "transactionType", required = false) String transactionType,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size
    ) {
        AccountStatementResponseDto response = statementService.getStatement(
                accountId,
                from,
                to,
                transactionType,
                status,
                page,
                size
        );
        return ResponseEntity.ok(response);
    }
}
