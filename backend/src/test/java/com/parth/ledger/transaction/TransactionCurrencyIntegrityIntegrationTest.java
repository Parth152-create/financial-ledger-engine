package com.parth.ledger.transaction;

import com.parth.ledger.BaseIntegrationTest;
import com.parth.ledger.account.Account;
import com.parth.ledger.account.AccountRepository;
import com.parth.ledger.account.AccountStatus;
import com.parth.ledger.account.AccountType;
import com.parth.ledger.transaction.dto.TransferRequestDto;
import com.parth.ledger.user.User;
import com.parth.ledger.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@DisplayName("V11 Transaction Currency Defense-in-Depth Integration Tests")
class TransactionCurrencyIntegrityIntegrationTest extends BaseIntegrationTest {

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
    private JdbcTemplate jdbcTemplate;

    private User aliceUser;
    private User bobUser;
    private Account aliceAccount;
    private Account bobAccount;

    @BeforeEach
    void setUp() {
        if (jdbcTemplate != null) {
            jdbcTemplate.execute("TRUNCATE TABLE ledger_entries CASCADE");
        }
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();

        aliceUser = userRepository.save(new User("alice.v11@ledger.com", "Alice V11"));
        bobUser = userRepository.save(new User("bob.v11@ledger.com", "Bob V11"));

        aliceAccount = accountRepository.save(new Account(aliceUser, "INR", new BigDecimal("100.0000"),
                AccountType.USER_CHECKING, AccountStatus.ACTIVE, "ACCT-V11-ALICE-01"));
        bobAccount = accountRepository.save(new Account(bobUser, "INR", BigDecimal.ZERO,
                AccountType.USER_CHECKING, AccountStatus.ACTIVE, "ACCT-V11-BOB-01"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        if (jdbcTemplate != null) {
            jdbcTemplate.execute("TRUNCATE TABLE ledger_entries CASCADE");
        }
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("1. Valid INR currency succeeds at database layer")
    void validUppercaseCurrencySucceeds() {
        UUID txId = UUID.randomUUID();
        int rows = jdbcTemplate.update(
                "INSERT INTO transactions (id, idempotency_key, amount, currency, status, transaction_type, source_account_id, destination_account_id, initiated_by_user_id, created_at) " +
                        "VALUES (?, ?, 10.0000, 'INR', 'COMPLETED', 'TRANSFER', ?, ?, ?, NOW())",
                txId, "key-v6-valid-01", aliceAccount.getId(), bobAccount.getId(), aliceUser.getId()
        );
        assertThat(rows).isEqualTo(1);
    }

    @Test
    @DisplayName("2. Non-INR currency (e.g. 'USD') is rejected by chk_transactions_currency_inr")
    void nonInrCurrencyRejectedByDatabaseConstraint() {
        UUID txId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO transactions (id, idempotency_key, amount, currency, status, transaction_type, source_account_id, destination_account_id, initiated_by_user_id, created_at) " +
                        "VALUES (?, ?, 10.0000, 'USD', 'COMPLETED', 'TRANSFER', ?, ?, ?, NOW())",
                txId, "key-v6-usd-01", aliceAccount.getId(), bobAccount.getId(), aliceUser.getId()
        )).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_transactions_currency_inr");
    }

    @Test
    @DisplayName("3. Lowercase currency (e.g. 'inr') is rejected by chk_transactions_currency_inr")
    void lowercaseCurrencyRejectedByDatabaseConstraint() {
        UUID txId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO transactions (id, idempotency_key, amount, currency, status, transaction_type, source_account_id, destination_account_id, initiated_by_user_id, created_at) " +
                        "VALUES (?, ?, 10.0000, 'inr', 'COMPLETED', 'TRANSFER', ?, ?, ?, NOW())",
                txId, "key-v6-lowercase-01", aliceAccount.getId(), bobAccount.getId(), aliceUser.getId()
        )).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_transactions_currency_inr");
    }

    @Test
    @DisplayName("4. Non-alphabetic / short currency is rejected by chk_transactions_currency_inr")
    void numericCurrencyRejectedByDatabaseConstraint() {
        UUID txId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO transactions (id, idempotency_key, amount, currency, status, transaction_type, source_account_id, destination_account_id, initiated_by_user_id, created_at) " +
                        "VALUES (?, ?, 10.0000, '123', 'COMPLETED', 'TRANSFER', ?, ?, ?, NOW())",
                txId, "key-v6-numeric-01", aliceAccount.getId(), bobAccount.getId(), aliceUser.getId()
        )).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_transactions_currency_inr");
    }

    @Test
    @DisplayName("5. Existing application layer rejects invalid currency with 400 Bad Request")
    void applicationLayerRejectsInvalidCurrency() throws Exception {
        TransferRequestDto request = new TransferRequestDto(
                aliceAccount.getId(), bobAccount.getId(), new BigDecimal("10.0000"), "US"
        );

        mockMvc.perform(post("/api/v1/transfers")
                        .with(user("alice.v11@ledger.com"))
                        .header("Idempotency-Key", "tx-v11-app-currency-01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Validation failed for request parameters")));
    }
}
