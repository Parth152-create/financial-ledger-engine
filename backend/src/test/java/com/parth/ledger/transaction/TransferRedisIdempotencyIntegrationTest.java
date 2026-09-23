package com.parth.ledger.transaction;

import com.parth.ledger.BaseIntegrationTest;
import com.parth.ledger.account.Account;
import com.parth.ledger.account.AccountRepository;
import com.parth.ledger.idempotency.IdempotencyCacheService;
import com.parth.ledger.ledger.LedgerEntry;
import com.parth.ledger.ledger.LedgerEntryRepository;
import com.parth.ledger.transaction.dto.TransferRequestDto;
import com.parth.ledger.transaction.dto.TransferResponseDto;
import com.parth.ledger.transaction.exception.IdempotencyConflictException;
import com.parth.ledger.transaction.service.TransferService;
import com.parth.ledger.user.User;
import com.parth.ledger.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

class TransferRedisIdempotencyIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TransferService transferService;

    @Autowired
    private IdempotencyCacheService idempotencyCacheService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @MockitoSpyBean
    private AccountRepository spyAccountRepository;

    @MockitoSpyBean
    private IdempotencyCacheService spyIdempotencyCacheService;

    private User aliceUser;
    private User bobUser;
    private Account aliceAccount;
    private Account bobAccount;

    @BeforeEach
    void setUp() {
        clearRedis();
        ledgerEntryRepository.deleteAll();
        transactionRepository.deleteAll();
        spyAccountRepository.deleteAll();
        userRepository.deleteAll();

        aliceUser = userRepository.save(new User("alice.redis@ledger.com", "Alice"));
        bobUser = userRepository.save(new User("bob.redis@ledger.com", "Bob"));

        aliceAccount = spyAccountRepository.save(new Account(aliceUser, "USD", new BigDecimal("1000.0000")));
        bobAccount = spyAccountRepository.save(new Account(bobUser, "USD", new BigDecimal("500.0000")));

        reset(spyAccountRepository);
        reset(spyIdempotencyCacheService);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice.redis@ledger.com", null, Collections.emptyList())
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Successful transfer populates Redis fast-path cache after commit")
    void verifySuccessfulTransferPopulatesRedis() throws Exception {
        String idempotencyKey = "tx-redis-pop-001";
        BigDecimal transferAmount = new BigDecimal("100.0000");

        TransferRequestDto request = new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                transferAmount,
                "USD"
        );

        TransferResponseDto response = transferService.executeTransfer(idempotencyKey, request);
        assertThat(response.status()).isEqualTo(TransactionStatus.COMPLETED);

        // Verify key in Redis directly
        String redisKey = "ledger:idempotency:" + idempotencyKey;
        String cachedJson = redisTemplate.opsForValue().get(redisKey);
        assertThat(cachedJson).isNotNull();

        TransferResponseDto cachedDto = objectMapper.readValue(cachedJson, TransferResponseDto.class);
        assertThat(cachedDto.transactionId()).isEqualTo(response.transactionId());
        assertThat(cachedDto.status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(cachedDto.sourceAccountId()).isEqualTo(aliceAccount.getId());
        assertThat(cachedDto.destinationAccountId()).isEqualTo(bobAccount.getId());
        assertThat(cachedDto.amount()).isEqualByComparingTo(transferAmount);
        assertThat(cachedDto.currency()).isEqualTo("USD");
    }

    @Test
    @DisplayName("Subsequent transfer with same key is served directly from Redis fast-path without DB row locks")
    void verifySameIdempotencyKeyServedFromRedisFastPath() {
        String idempotencyKey = "tx-redis-served-001";
        BigDecimal transferAmount = new BigDecimal("100.0000");

        TransferRequestDto request = new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                transferAmount,
                "USD"
        );

        // First transfer: executes DB transaction and populates Redis
        TransferResponseDto response1 = transferService.executeTransfer(idempotencyKey, request);
        assertThat(response1.status()).isEqualTo(TransactionStatus.COMPLETED);

        // Reset spy invocation records
        reset(spyAccountRepository);

        // Second transfer with same key: must hit Redis fast-path
        TransferResponseDto response2 = transferService.executeTransfer(idempotencyKey, request);

        // Verify exact same result returned
        assertThat(response2.transactionId()).isEqualTo(response1.transactionId());
        assertThat(response2.status()).isEqualTo(response1.status());
        assertThat(response2.amount()).isEqualByComparingTo(response1.amount());
        assertThat(response2.completedAt()).isEqualTo(response1.completedAt());

        // Verify PostgreSQL row locking was completely bypassed on cache hit
        verify(spyAccountRepository, never()).findByIdForUpdate(any());

        // Verify balances changed only once
        Account currentAlice = spyAccountRepository.findById(aliceAccount.getId()).orElseThrow();
        Account currentBob = spyAccountRepository.findById(bobAccount.getId()).orElseThrow();
        assertThat(currentAlice.getBalance()).isEqualByComparingTo(new BigDecimal("900.0000"));
        assertThat(currentBob.getBalance()).isEqualByComparingTo(new BigDecimal("600.0000"));

        // Only one transaction in DB
        assertThat(transactionRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Redis cache hit detects idempotency conflict when parameters differ and returns 409")
    void verifyRedisCacheHitDetectsConflict() {
        String idempotencyKey = "tx-redis-conflict-001";

        TransferRequestDto request1 = new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("100.0000"),
                "USD"
        );

        // First transfer populates Redis
        transferService.executeTransfer(idempotencyKey, request1);

        reset(spyAccountRepository);

        // Second transfer with different amount
        TransferRequestDto requestConflict = new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("200.0000"),
                "USD"
        );

        assertThatThrownBy(() -> transferService.executeTransfer(idempotencyKey, requestConflict))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("different parameters");

        // DB row lock was never acquired because Redis caught the conflict
        verify(spyAccountRepository, never()).findByIdForUpdate(any());

        // Balances reflect only the first transfer
        Account currentAlice = spyAccountRepository.findById(aliceAccount.getId()).orElseThrow();
        Account currentBob = spyAccountRepository.findById(bobAccount.getId()).orElseThrow();
        assertThat(currentAlice.getBalance()).isEqualByComparingTo(new BigDecimal("900.0000"));
        assertThat(currentBob.getBalance()).isEqualByComparingTo(new BigDecimal("600.0000"));
    }

    @Test
    @DisplayName("Redis cache expiration is respected and seamlessly falls back to PostgreSQL")
    void verifyRedisCacheExpirationRespected() throws InterruptedException {
        String idempotencyKey = "tx-redis-ttl-001";
        BigDecimal transferAmount = new BigDecimal("50.0000");

        TransferRequestDto request = new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                transferAmount,
                "USD"
        );

        TransferResponseDto initialResponse = transferService.executeTransfer(idempotencyKey, request);

        // Overwrite key with a 500ms short TTL for testing expiration
        idempotencyCacheService.set(idempotencyKey, initialResponse, Duration.ofMillis(500));

        // Key exists immediately
        Optional<TransferResponseDto> immediate = idempotencyCacheService.get(idempotencyKey);
        assertThat(immediate).isPresent();

        // Wait 700ms for key to expire
        Thread.sleep(700);

        // Key expired in Redis
        Optional<TransferResponseDto> afterTtl = idempotencyCacheService.get(idempotencyKey);
        assertThat(afterTtl).isEmpty();

        // Re-execute transfer: cache miss in Redis, but PostgreSQL authoritative check succeeds
        TransferResponseDto fallbackResponse = transferService.executeTransfer(idempotencyKey, request);
        assertThat(fallbackResponse.transactionId()).isEqualTo(initialResponse.transactionId());

        // Balances modified only once
        Account currentAlice = spyAccountRepository.findById(aliceAccount.getId()).orElseThrow();
        Account currentBob = spyAccountRepository.findById(bobAccount.getId()).orElseThrow();
        assertThat(currentAlice.getBalance()).isEqualByComparingTo(new BigDecimal("950.0000"));
        assertThat(currentBob.getBalance()).isEqualByComparingTo(new BigDecimal("550.0000"));

        // Redis re-populated after fallback
        assertThat(idempotencyCacheService.get(idempotencyKey)).isPresent();
    }

    @Test
    @DisplayName("Redis unavailable: GET failure fails open to PostgreSQL and transfer succeeds")
    void verifyRedisUnavailableFallsBackToPostgres() {
        String idempotencyKey = "tx-redis-outage-001";
        BigDecimal transferAmount = new BigDecimal("75.0000");

        TransferRequestDto request = new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                transferAmount,
                "USD"
        );

        // Simulate Redis outage on GET
        doThrow(new RedisConnectionFailureException("Simulated Redis outage"))
                .when(spyIdempotencyCacheService).get(anyString());

        // First transfer succeeds via PostgreSQL
        TransferResponseDto response1 = transferService.executeTransfer(idempotencyKey, request);
        assertThat(response1.status()).isEqualTo(TransactionStatus.COMPLETED);

        // Second identical transfer while Redis is still down
        TransferResponseDto response2 = transferService.executeTransfer(idempotencyKey, request);
        assertThat(response2.transactionId()).isEqualTo(response1.transactionId());

        // Balances changed exactly once
        Account currentAlice = spyAccountRepository.findById(aliceAccount.getId()).orElseThrow();
        Account currentBob = spyAccountRepository.findById(bobAccount.getId()).orElseThrow();
        assertThat(currentAlice.getBalance()).isEqualByComparingTo(new BigDecimal("925.0000"));
        assertThat(currentBob.getBalance()).isEqualByComparingTo(new BigDecimal("575.0000"));
    }

    @Test
    @DisplayName("Redis SET failure after PostgreSQL commit does not roll back or fail the transfer")
    void verifyRedisSetFailureDoesNotFailTransfer() {
        String idempotencyKey = "tx-redis-set-fail-001";
        BigDecimal transferAmount = new BigDecimal("80.0000");

        TransferRequestDto request = new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                transferAmount,
                "USD"
        );

        // Simulate Redis failure during SET
        doThrow(new RedisConnectionFailureException("Simulated Redis write timeout"))
                .when(spyIdempotencyCacheService).set(anyString(), any(TransferResponseDto.class));

        // Transfer must succeed even though Redis SET failed after commit
        TransferResponseDto response = transferService.executeTransfer(idempotencyKey, request);
        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(TransactionStatus.COMPLETED);

        // Verify PostgreSQL transaction remained committed
        Account currentAlice = spyAccountRepository.findById(aliceAccount.getId()).orElseThrow();
        Account currentBob = spyAccountRepository.findById(bobAccount.getId()).orElseThrow();
        assertThat(currentAlice.getBalance()).isEqualByComparingTo(new BigDecimal("920.0000"));
        assertThat(currentBob.getBalance()).isEqualByComparingTo(new BigDecimal("580.0000"));

        assertThat(transactionRepository.findByIdempotencyKey(idempotencyKey)).isPresent();
        List<LedgerEntry> entries = ledgerEntryRepository.findByTransactionId(response.transactionId());
        assertThat(entries).hasSize(2);
    }
}
