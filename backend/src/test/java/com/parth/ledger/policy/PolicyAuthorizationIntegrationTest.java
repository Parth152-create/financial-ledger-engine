package com.parth.ledger.policy;

import com.parth.ledger.BaseIntegrationTest;
import com.parth.ledger.account.Account;
import com.parth.ledger.account.AccountRepository;
import com.parth.ledger.policy.dto.CreatePolicyRequestDto;
import com.parth.ledger.policy.dto.UpdatePolicyRequestDto;
import com.parth.ledger.system.SystemFundingService;
import com.parth.ledger.transaction.TransactionType;
import com.parth.ledger.user.User;
import com.parth.ledger.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class PolicyAuthorizationIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private FinancialPolicyRepository financialPolicyRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SystemFundingService systemFundingService;

    private User normalUser;
    private User otherUser;
    private User adminUser;
    private Account normalUserAccount;
    private Account otherUserAccount;

    @BeforeEach
    void setUp() {
        clearRedis();
        if (jdbcTemplate != null) {
            jdbcTemplate.execute("TRUNCATE TABLE audit_events CASCADE");
            jdbcTemplate.execute("TRUNCATE TABLE ledger_entries CASCADE");
            jdbcTemplate.execute("TRUNCATE TABLE transactions CASCADE");
            jdbcTemplate.execute("TRUNCATE TABLE policy_usage_daily CASCADE");
            jdbcTemplate.execute("TRUNCATE TABLE financial_policies CASCADE");
            jdbcTemplate.execute("TRUNCATE TABLE accounts CASCADE");
            jdbcTemplate.execute("TRUNCATE TABLE users CASCADE");
        }

        systemFundingService.bootstrapSystemFunding(new BigDecimal("10000000.0000"));

        normalUser = userRepository.save(new User("user@ledger.com", "Normal User"));
        otherUser = userRepository.save(new User("other@ledger.com", "Other User"));
        adminUser = userRepository.save(new User("admin@ledger.com", "Admin User"));

        normalUserAccount = accountRepository.save(new Account(normalUser, "INR", new BigDecimal("10000.0000")));
        otherUserAccount = accountRepository.save(new Account(otherUser, "INR", new BigDecimal("10000.0000")));
    }

    @Test
    @DisplayName("23. Normal user cannot create policies (403 Forbidden)")
    void normalUserCannotCreatePolicy() throws Exception {
        CreatePolicyRequestDto dto = new CreatePolicyRequestDto(
                null,
                PolicyScope.GLOBAL,
                TransactionType.TRANSFER,
                PolicyType.MAX_TRANSACTION_AMOUNT,
                new BigDecimal("50000.0000"),
                null,
                "INR",
                true
        );

        mockMvc.perform(post("/api/v1/admin/policies")
                        .with(user("user@ledger.com").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Normal user cannot update policies (403 Forbidden)")
    void normalUserCannotUpdatePolicy() throws Exception {
        FinancialPolicy policy = financialPolicyRepository.save(new FinancialPolicy(
                null,
                PolicyScope.GLOBAL,
                TransactionType.TRANSFER,
                PolicyType.MAX_TRANSACTION_AMOUNT,
                new BigDecimal("50000.0000"),
                null,
                "INR",
                true
        ));

        UpdatePolicyRequestDto updateDto = new UpdatePolicyRequestDto(new BigDecimal("60000.0000"), null, true);

        mockMvc.perform(put("/api/v1/admin/policies/" + policy.getId())
                        .with(user("user@ledger.com").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateDto)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Normal user cannot delete policies (403 Forbidden)")
    void normalUserCannotDeletePolicy() throws Exception {
        FinancialPolicy policy = financialPolicyRepository.save(new FinancialPolicy(
                null,
                PolicyScope.GLOBAL,
                TransactionType.TRANSFER,
                PolicyType.MAX_TRANSACTION_AMOUNT,
                new BigDecimal("50000.0000"),
                null,
                "INR",
                true
        ));

        mockMvc.perform(delete("/api/v1/admin/policies/" + policy.getId())
                        .with(user("user@ledger.com").roles("USER"))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Normal user cannot list admin policies (403 Forbidden)")
    void normalUserCannotListPolicies() throws Exception {
        mockMvc.perform(get("/api/v1/admin/policies")
                        .with(user("user@ledger.com").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("24. Admin can manage policies (create, get, list, update, delete)")
    void adminCanManagePolicies() throws Exception {
        // 1. Create GLOBAL Policy
        CreatePolicyRequestDto createDto = new CreatePolicyRequestDto(
                null,
                PolicyScope.GLOBAL,
                TransactionType.TRANSFER,
                PolicyType.MAX_TRANSACTION_AMOUNT,
                new BigDecimal("100000.0000"),
                null,
                "INR",
                true
        );

        String createResp = mockMvc.perform(post("/api/v1/admin/policies")
                        .with(user("admin@ledger.com").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createDto)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.policyScope", is("GLOBAL")))
                .andExpect(jsonPath("$.amountLimit", is(100000.0)))
                .andReturn().getResponse().getContentAsString();

        UUID policyId = UUID.fromString(objectMapper.readTree(createResp).get("id").asText());

        // 2. Get Policy by ID
        mockMvc.perform(get("/api/v1/admin/policies/" + policyId)
                        .with(user("admin@ledger.com").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(policyId.toString())))
                .andExpect(jsonPath("$.policyScope", is("GLOBAL")));

        // 3. List Policies
        mockMvc.perform(get("/api/v1/admin/policies")
                        .with(user("admin@ledger.com").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));

        // 4. Update Policy
        UpdatePolicyRequestDto updateDto = new UpdatePolicyRequestDto(new BigDecimal("150000.0000"), null, false);
        mockMvc.perform(put("/api/v1/admin/policies/" + policyId)
                        .with(user("admin@ledger.com").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amountLimit", is(150000.0)))
                .andExpect(jsonPath("$.enabled", is(false)));

        // 5. Delete Policy
        mockMvc.perform(delete("/api/v1/admin/policies/" + policyId)
                        .with(user("admin@ledger.com").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isNoContent());

        // Verify deleted returns 404
        mockMvc.perform(get("/api/v1/admin/policies/" + policyId)
                        .with(user("admin@ledger.com").roles("ADMIN")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Admin cannot create policy on system account")
    void adminCannotCreatePolicyOnSystemAccount() throws Exception {
        CreatePolicyRequestDto dto = new CreatePolicyRequestDto(
                SystemFundingService.SYSTEM_CLEARING_ACCOUNT_ID,
                PolicyScope.ACCOUNT,
                TransactionType.DEPOSIT,
                PolicyType.MAX_TRANSACTION_AMOUNT,
                new BigDecimal("50000.0000"),
                null,
                "INR",
                true
        );

        mockMvc.perform(post("/api/v1/admin/policies")
                        .with(user("admin@ledger.com").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("25. User cannot access another account's limits (404 anti-enumeration)")
    void userCannotAccessOtherAccountLimits() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/" + otherUserAccount.getId() + "/limits")
                        .with(user("user@ledger.com").roles("USER"))
                        .param("transactionType", "TRANSFER"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("User cannot access system clearing account limits (404 anti-enumeration)")
    void userCannotAccessSystemAccountLimits() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/" + SystemFundingService.SYSTEM_CLEARING_ACCOUNT_ID + "/limits")
                        .with(user("user@ledger.com").roles("USER"))
                        .param("transactionType", "TRANSFER"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("User can view limits on their own account")
    void userCanViewOwnAccountLimits() throws Exception {
        financialPolicyRepository.save(new FinancialPolicy(
                normalUserAccount.getId(),
                PolicyScope.ACCOUNT,
                TransactionType.TRANSFER,
                PolicyType.MAX_TRANSACTION_AMOUNT,
                new BigDecimal("75000.0000"),
                null,
                "INR",
                true
        ));

        mockMvc.perform(get("/api/v1/accounts/" + normalUserAccount.getId() + "/limits")
                        .with(user("user@ledger.com").roles("USER"))
                        .param("transactionType", "TRANSFER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId", is(normalUserAccount.getId().toString())))
                .andExpect(jsonPath("$.transactionType", is("TRANSFER")))
                .andExpect(jsonPath("$.maxTransactionAmount", is(75000.0)))
                .andExpect(jsonPath("$.currency", is("INR")));
    }

    @Test
    @DisplayName("User can view all transaction types limits on their own account")
    void userCanViewAllOwnAccountLimits() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/" + normalUserAccount.getId() + "/limits")
                        .with(user("user@ledger.com").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].transactionType", is("TRANSFER")))
                .andExpect(jsonPath("$[1].transactionType", is("DEPOSIT")))
                .andExpect(jsonPath("$[2].transactionType", is("WITHDRAWAL")));
    }
}
