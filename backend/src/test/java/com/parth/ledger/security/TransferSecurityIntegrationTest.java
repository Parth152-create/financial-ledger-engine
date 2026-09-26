package com.parth.ledger.security;

import com.parth.ledger.BaseIntegrationTest;
import com.parth.ledger.account.Account;
import com.parth.ledger.account.AccountRepository;
import com.parth.ledger.ledger.LedgerEntryRepository;
import com.parth.ledger.transaction.TransactionRepository;
import com.parth.ledger.transaction.TransactionStatus;
import com.parth.ledger.transaction.dto.TransferRequestDto;
import com.parth.ledger.transaction.dto.TransferResponseDto;
import com.parth.ledger.transaction.service.TransferService;
import com.parth.ledger.user.User;
import com.parth.ledger.user.UserRepository;
import com.parth.ledger.user.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class TransferSecurityIntegrationTest extends BaseIntegrationTest {

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

    @Autowired
    private TransferService transferService;

    @Autowired
    private UserService userService;

    private User aliceUser;
    private User bobUser;
    private User charlieUser;

    private Account aliceAccount;
    private Account bobAccount;
    private Account charlieAccount;

    @BeforeEach
    void setUp() {
        clearRedis();
        ledgerEntryRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();

        aliceUser = userRepository.save(new User("alice.sec@ledger.com", "Alice Sec"));
        bobUser = userRepository.save(new User("bob.sec@ledger.com", "Bob Sec"));
        charlieUser = userRepository.save(new User("charlie.sec@ledger.com", "Charlie Sec"));

        aliceAccount = accountRepository.save(new Account(aliceUser, "INR", new BigDecimal("1000.0000")));
        bobAccount = accountRepository.save(new Account(bobUser, "INR", new BigDecimal("500.0000")));
        charlieAccount = accountRepository.save(new Account(charlieUser, "INR", new BigDecimal("300.0000")));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("1. Unauthenticated transfer request returns 401 Unauthorized")
    void verifyUnauthenticatedTransferReturns401() throws Exception {
        TransferRequestDto request = new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("50.0000"),
                "INR"
        );

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "sec-test-unauth-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("2. Authenticated user with owned source account: transfer succeeds (200 OK)")
    void verifyAuthenticatedOwnerTransferSucceeds() throws Exception {
        TransferRequestDto request = new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("100.0000"),
                "INR"
        );

        mockMvc.perform(post("/api/v1/transfers")
                        .with(user("alice.sec@ledger.com"))
                        .header("Idempotency-Key", "sec-test-owner-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("COMPLETED")))
                .andExpect(jsonPath("$.sourceAccountId", is(aliceAccount.getId().toString())))
                .andExpect(jsonPath("$.destinationAccountId", is(bobAccount.getId().toString())))
                .andExpect(jsonPath("$.amount", is(100.0)));

        // Verify balances in database
        Account updatedAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        Account updatedBob = accountRepository.findById(bobAccount.getId()).orElseThrow();
        assertThat(updatedAlice.getBalance()).isEqualByComparingTo(new BigDecimal("900.0000"));
        assertThat(updatedBob.getBalance()).isEqualByComparingTo(new BigDecimal("600.0000"));
    }

    @Test
    @DisplayName("3. Authenticated user attempting to debit another user's source account returns 403 Forbidden")
    void verifyNonOwnerCannotDebitSourceAccount() throws Exception {
        // Bob attempts to debit Alice's account (Alice owns source account)
        TransferRequestDto request = new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("100.0000"),
                "INR"
        );

        mockMvc.perform(post("/api/v1/transfers")
                        .with(user("bob.sec@ledger.com"))
                        .header("Idempotency-Key", "sec-test-forbidden-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status", is(403)))
                .andExpect(jsonPath("$.message", containsString("not authorized")));

        // Verify balances remain untouched
        Account checkAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        Account checkBob = accountRepository.findById(bobAccount.getId()).orElseThrow();
        assertThat(checkAlice.getBalance()).isEqualByComparingTo(new BigDecimal("1000.0000"));
        assertThat(checkBob.getBalance()).isEqualByComparingTo(new BigDecimal("500.0000"));
        assertThat(transactionRepository.count()).isZero();
    }

    @Test
    @DisplayName("4. Destination account belonging to another user is allowed when source is owned by caller")
    void verifyDestinationAccountBelongingToAnotherUserIsAllowed() throws Exception {
        // Alice transfers to Charlie (destination account belongs to another user)
        TransferRequestDto request = new TransferRequestDto(
                aliceAccount.getId(),
                charlieAccount.getId(),
                new BigDecimal("75.0000"),
                "INR"
        );

        mockMvc.perform(post("/api/v1/transfers")
                        .with(user("alice.sec@ledger.com"))
                        .header("Idempotency-Key", "sec-test-dest-other-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("COMPLETED")))
                .andExpect(jsonPath("$.sourceAccountId", is(aliceAccount.getId().toString())))
                .andExpect(jsonPath("$.destinationAccountId", is(charlieAccount.getId().toString())))
                .andExpect(jsonPath("$.amount", is(75.0)));

        Account updatedAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        Account updatedCharlie = accountRepository.findById(charlieAccount.getId()).orElseThrow();
        assertThat(updatedAlice.getBalance()).isEqualByComparingTo(new BigDecimal("925.0000"));
        assertThat(updatedCharlie.getBalance()).isEqualByComparingTo(new BigDecimal("375.0000"));
    }

    @Test
    @DisplayName("5. Unknown/nonexistent authenticated user is handled safely (403 Forbidden)")
    void verifyUnknownAuthenticatedUserHandledSafely() throws Exception {
        TransferRequestDto request = new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("50.0000"),
                "INR"
        );

        mockMvc.perform(post("/api/v1/transfers")
                        .with(user("ghost.user@ledger.com"))
                        .header("Idempotency-Key", "sec-test-unknown-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status", is(403)))
                .andExpect(jsonPath("$.message", containsString("user not found")));
    }

    @Test
    @DisplayName("6. Service-level authorization enforcement throws AccountOwnershipException")
    void verifyServiceLevelOwnershipEnforcement() {
        TransferRequestDto request = new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("50.0000"),
                "INR"
        );

        // Authenticate as Bob and attempt to debit Alice's account directly via TransferService
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("bob.sec@ledger.com", null, Collections.emptyList())
        );

        assertThatThrownBy(() -> transferService.executeTransfer("sec-service-auth-001", request))
                .isInstanceOf(AccountOwnershipException.class)
                .hasMessageContaining("not own source account");
    }

    @Test
    @DisplayName("7. Service-level call without authentication throws AuthenticationCredentialsNotFoundException")
    void verifyServiceLevelUnauthenticatedThrowsException() {
        TransferRequestDto request = new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("50.0000"),
                "INR"
        );

        // Explicitly ensure SecurityContext is empty
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> transferService.executeTransfer("sec-service-unauth-001", request))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }

    @Test
    @DisplayName("8. User provisioning: syncOAuth2User creates new user if absent, returns existing if present")
    void verifyOAuth2UserProvisioning() {
        // 1. Create new user
        String newEmail = "new.google.user@example.com";
        String newName = "Google Newbie";

        assertThat(userRepository.findByEmail(newEmail)).isEmpty();

        User provisioned = userService.syncOAuth2User(newEmail, newName);
        assertThat(provisioned.getId()).isNotNull();
        assertThat(provisioned.getEmail()).isEqualTo(newEmail);
        assertThat(provisioned.getName()).isEqualTo(newName);

        // 2. Existing user lookup returns the same user without creating a duplicate
        User syncedAgain = userService.syncOAuth2User(newEmail, "Updated Name");
        assertThat(syncedAgain.getId()).isEqualTo(provisioned.getId());
        assertThat(syncedAgain.getEmail()).isEqualTo(newEmail);
        assertThat(userRepository.findAll().stream().filter(u -> u.getEmail().equals(newEmail)).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("9. Public endpoints: /actuator/health is accessible without authentication")
    void verifyActuatorHealthIsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("10. Redis fast-path authorization: non-owner cannot access cached transfer")
    void verifyRedisFastPathAuthorization() throws Exception {
        String idempotencyKey = "sec-redis-fastpath-auth-001";
        TransferRequestDto request = new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("100.0000"),
                "INR"
        );

        // 1. Alice performs the transfer successfully, populating Redis cache
        mockMvc.perform(post("/api/v1/transfers")
                        .with(user("alice.sec@ledger.com"))
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("COMPLETED")));

        // 2. Bob attempts to replay the same request with same idempotency key
        mockMvc.perform(post("/api/v1/transfers")
                        .with(user("bob.sec@ledger.com"))
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status", is(403)))
                .andExpect(jsonPath("$.message", containsString("not authorized")));
    }
}
