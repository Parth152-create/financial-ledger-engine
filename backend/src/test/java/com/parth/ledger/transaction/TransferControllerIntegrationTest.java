package com.parth.ledger.transaction;

import com.parth.ledger.BaseIntegrationTest;
import com.parth.ledger.account.Account;
import com.parth.ledger.account.AccountRepository;
import com.parth.ledger.ledger.LedgerEntryRepository;
import com.parth.ledger.transaction.dto.TransferRequestDto;
import com.parth.ledger.transaction.dto.TransferResponseDto;
import com.parth.ledger.user.User;
import com.parth.ledger.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@WithMockUser(username = "alice.api@ledger.com")
class TransferControllerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    private Account aliceUsdAccount;
    private Account bobUsdAccount;
    private Account charlieEurAccount;

    @BeforeEach
    void setUp() {
        clearRedis();
        ledgerEntryRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();

        User alice = userRepository.save(new User("alice.api@ledger.com", "Alice"));
        User bob = userRepository.save(new User("bob.api@ledger.com", "Bob"));
        User charlie = userRepository.save(new User("charlie.api@ledger.com", "Charlie"));

        aliceUsdAccount = accountRepository.save(new Account(alice, "INR", new BigDecimal("1000.0000")));
        bobUsdAccount = accountRepository.save(new Account(bob, "INR", new BigDecimal("500.0000")));
        charlieEurAccount = accountRepository.save(new Account(charlie, "INR", new BigDecimal("300.0000")));
    }

    @Test
    @DisplayName("POST /api/v1/transfers: successful transfer returns 200 OK and response DTO")
    void verifySuccessfulTransferEndpoint() throws Exception {
        TransferRequestDto request = new TransferRequestDto(
                aliceUsdAccount.getId(),
                bobUsdAccount.getId(),
                new BigDecimal("100.0000"),
                "INR"
        );

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "api-tx-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId", notNullValue()))
                .andExpect(jsonPath("$.status", is("COMPLETED")))
                .andExpect(jsonPath("$.sourceAccountId", is(aliceUsdAccount.getId().toString())))
                .andExpect(jsonPath("$.destinationAccountId", is(bobUsdAccount.getId().toString())))
                .andExpect(jsonPath("$.amount", is(100.0)))
                .andExpect(jsonPath("$.currency", is("INR")))
                .andExpect(jsonPath("$.createdAt", notNullValue()))
                .andExpect(jsonPath("$.completedAt", notNullValue()));
    }

    @Test
    @DisplayName("POST /api/v1/transfers: missing Idempotency-Key header returns 400 Bad Request")
    void verifyMissingIdempotencyKeyHeader() throws Exception {
        TransferRequestDto request = new TransferRequestDto(
                aliceUsdAccount.getId(),
                bobUsdAccount.getId(),
                new BigDecimal("100.0000"),
                "INR"
        );

        mockMvc.perform(post("/api/v1/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.message", containsString("Idempotency-Key")));
    }

    @Test
    @DisplayName("POST /api/v1/transfers: insufficient balance returns 422 Unprocessable Content")
    void verifyInsufficientBalanceEndpoint() throws Exception {
        TransferRequestDto request = new TransferRequestDto(
                aliceUsdAccount.getId(),
                bobUsdAccount.getId(),
                new BigDecimal("5000.0000"),
                "INR"
        );

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "api-insufficient-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status", is(422)))
                .andExpect(jsonPath("$.message", containsString("Insufficient balance")));
    }

    @Test
    @DisplayName("POST /api/v1/transfers: same source and destination accounts returns 400 Bad Request")
    void verifySameAccountTransferEndpoint() throws Exception {
        TransferRequestDto request = new TransferRequestDto(
                aliceUsdAccount.getId(),
                aliceUsdAccount.getId(),
                new BigDecimal("50.0000"),
                "INR"
        );

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "api-same-acc-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.message", containsString("must be different")));
    }

    @Test
    @DisplayName("POST /api/v1/transfers: non-INR currency returns 400 Bad Request")
    void verifyCurrencyMismatchEndpoint() throws Exception {
        TransferRequestDto request = new TransferRequestDto(
                aliceUsdAccount.getId(),
                charlieEurAccount.getId(),
                new BigDecimal("50.0000"),
                "USD"
        );

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "api-mismatch-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.message", containsString("Only INR currency is supported: USD")));
    }

    @Test
    @DisplayName("POST /api/v1/transfers: idempotency conflict returns 409 Conflict")
    void verifyIdempotencyConflictEndpoint() throws Exception {
        TransferRequestDto request1 = new TransferRequestDto(
                aliceUsdAccount.getId(),
                bobUsdAccount.getId(),
                new BigDecimal("50.0000"),
                "INR"
        );

        // First transfer succeeds
        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "api-conflict-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request1)))
                .andExpect(status().isOk());

        // Second transfer with different amount returns 409 Conflict
        TransferRequestDto request2 = new TransferRequestDto(
                aliceUsdAccount.getId(),
                bobUsdAccount.getId(),
                new BigDecimal("75.0000"),
                "INR"
        );

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "api-conflict-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status", is(409)))
                .andExpect(jsonPath("$.message", containsString("different parameters")));
    }

    @Test
    @DisplayName("POST /api/v1/transfers: repeated identical idempotency key returns 200 OK with same transaction")
    void verifyIdempotentRetryEndpoint() throws Exception {
        TransferRequestDto request = new TransferRequestDto(
                aliceUsdAccount.getId(),
                bobUsdAccount.getId(),
                new BigDecimal("50.0000"),
                "INR"
        );

        String firstResponse = mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "api-retry-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String secondResponse = mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "api-retry-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        TransferResponseDto dto1 = objectMapper.readValue(firstResponse, TransferResponseDto.class);
        TransferResponseDto dto2 = objectMapper.readValue(secondResponse, TransferResponseDto.class);

        org.assertj.core.api.Assertions.assertThat(dto1.transactionId()).isEqualTo(dto2.transactionId());
        org.assertj.core.api.Assertions.assertThat(dto1.amount()).isEqualByComparingTo(dto2.amount());
    }

    @Test
    @DisplayName("POST /api/v1/transfers: non-existent account returns 404 Not Found")
    void verifyAccountNotFoundEndpoint() throws Exception {
        UUID nonExistentId = UUID.randomUUID();
        TransferRequestDto request = new TransferRequestDto(
                nonExistentId,
                bobUsdAccount.getId(),
                new BigDecimal("50.0000"),
                "INR"
        );

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "api-notfound-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status", is(404)))
                .andExpect(jsonPath("$.message", containsString(nonExistentId.toString())));
    }

    @Test
    @DisplayName("POST /api/v1/transfers: validation failure on negative amount returns 400 Bad Request")
    void verifyValidationFailureEndpoint() throws Exception {
        String invalidJson = """
                {
                    "sourceAccountId": "%s",
                    "destinationAccountId": "%s",
                    "amount": -50.00,
                    "currency": "INR"
                }
                """.formatted(aliceUsdAccount.getId(), bobUsdAccount.getId());

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "api-invalid-amount")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.details", notNullValue()));
    }
}
