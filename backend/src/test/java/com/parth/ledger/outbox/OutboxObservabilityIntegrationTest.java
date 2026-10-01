package com.parth.ledger.outbox;

import com.parth.ledger.BaseIntegrationTest;
import com.parth.ledger.account.Account;
import com.parth.ledger.account.AccountRepository;
import com.parth.ledger.account.AccountStatus;
import com.parth.ledger.account.AccountType;
import com.parth.ledger.observability.metrics.LedgerMetrics;
import com.parth.ledger.outbox.handler.OutboxEventHandler;
import com.parth.ledger.system.SystemFundingService;
import com.parth.ledger.transaction.dto.TransferRequestDto;
import com.parth.ledger.transaction.service.TransferService;
import com.parth.ledger.user.User;
import com.parth.ledger.user.UserRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxObservabilityIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private TransferService transferService;

    @Autowired
    private OutboxService outboxService;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private OutboxProperties properties;

    @Autowired
    private LedgerMetrics ledgerMetrics;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private SystemFundingService systemFundingService;

    private User alice;
    private User bob;
    private Account aliceAccount;
    private Account bobAccount;
    private OutboxProcessor outboxProcessor;
    private AtomicBoolean failHandler;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        systemFundingService.ensureBootstrapFunding();

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        alice = userRepository.save(new User("alice." + suffix + "@ledger.com", "Alice"));
        bob = userRepository.save(new User("bob." + suffix + "@ledger.com", "Bob"));

        aliceAccount = accountRepository.save(new Account(alice, "INR", new BigDecimal("1000.0000"), AccountType.USER_CHECKING, AccountStatus.ACTIVE, "ACCT-A-" + suffix));
        bobAccount = accountRepository.save(new Account(bob, "INR", new BigDecimal("500.0000"), AccountType.USER_CHECKING, AccountStatus.ACTIVE, "ACCT-B-" + suffix));

        failHandler = new AtomicBoolean(false);
        OutboxEventHandler testHandler = new OutboxEventHandler() {
            @Override
            public boolean supports(OutboxEventType eventType) {
                return true;
            }

            @Override
            public void handle(OutboxEvent event) {
                if (failHandler.get()) {
                    throw new RuntimeException("Simulated failure for metric test");
                }
            }
        };

        OutboxEventDispatcher dispatcher = new OutboxEventDispatcher(List.of(testHandler));
        outboxProcessor = new OutboxProcessor(outboxEventRepository, dispatcher, properties, ledgerMetrics, transactionManager);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(alice.getEmail(), null, Collections.emptyList())
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        if (jdbcTemplate != null) {
            try {
                jdbcTemplate.execute("TRUNCATE TABLE outbox_events CASCADE");
                jdbcTemplate.execute("TRUNCATE TABLE ledger_entries CASCADE");
                jdbcTemplate.execute("TRUNCATE TABLE transactions CASCADE");
                jdbcTemplate.execute("TRUNCATE TABLE accounts CASCADE");
                jdbcTemplate.execute("TRUNCATE TABLE users CASCADE");
            } catch (Exception ignored) {
            }
        }
    }

    private double getCounterValue(String name, String... tags) {
        Counter counter = meterRegistry.find(name).tags(tags).counter();
        return counter != null ? counter.count() : 0.0;
    }

    private long getTimerCount(String name, String... tags) {
        Timer timer = meterRegistry.find(name).tags(tags).timer();
        return timer != null ? timer.count() : 0L;
    }

    @Test
    @DisplayName("Outbox event creation increments created metric counter with bounded event_type tag")
    void outboxEventCreationIncrementsMetric() {
        double before = getCounterValue("ledger.outbox.events.created.total", "event_type", "TRANSFER_COMPLETED");

        String key = "TX-METRIC-" + UUID.randomUUID();
        TransferRequestDto request = new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("100.0000"),
                "INR",
                "Observability test transfer"
        );
        transferService.executeTransfer(key, request);

        double after = getCounterValue("ledger.outbox.events.created.total", "event_type", "TRANSFER_COMPLETED");
        assertThat(after).isEqualTo(before + 1.0);
    }

    @Test
    @DisplayName("Outbox event processing increments processed metric counter and duration timer")
    void outboxProcessingIncrementsProcessedMetricAndTimer() {
        double processedBefore = getCounterValue("ledger.outbox.events.processed.total", "event_type", "TRANSFER_COMPLETED", "outcome", "SUCCESS");
        long timerBefore = getTimerCount("ledger.outbox.processing.duration", "event_type", "TRANSFER_COMPLETED", "status", "SUCCESS");

        String key = "TX-METRIC-PROC-" + UUID.randomUUID();
        TransferRequestDto request = new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("50.0000"),
                "INR",
                "Observability processing transfer"
        );
        transferService.executeTransfer(key, request);

        // Process batch
        outboxProcessor.processBatch(10);

        double processedAfter = getCounterValue("ledger.outbox.events.processed.total", "event_type", "TRANSFER_COMPLETED", "outcome", "SUCCESS");
        long timerAfter = getTimerCount("ledger.outbox.processing.duration", "event_type", "TRANSFER_COMPLETED", "status", "SUCCESS");

        assertThat(processedAfter).isEqualTo(processedBefore + 1.0);
        assertThat(timerAfter).isEqualTo(timerBefore + 1L);
    }

    @Test
    @DisplayName("Outbox transient failure increments retried metric and terminal failure increments failed metric")
    void outboxFailureMetricsIncrement() {
        double retriedBefore = getCounterValue("ledger.outbox.events.retried.total", "event_type", "TRANSFER_COMPLETED");
        double failedBefore = getCounterValue("ledger.outbox.events.failed.total", "event_type", "TRANSFER_COMPLETED");

        String key = "TX-METRIC-FAIL-" + UUID.randomUUID();
        TransferRequestDto request = new TransferRequestDto(
                aliceAccount.getId(),
                bobAccount.getId(),
                new BigDecimal("25.0000"),
                "INR",
                "Observability fail transfer"
        );
        transferService.executeTransfer(key, request);

        // First attempt fails -> RETRY
        failHandler.set(true);
        outboxProcessor.processBatch(10);

        double retriedAfter = getCounterValue("ledger.outbox.events.retried.total", "event_type", "TRANSFER_COMPLETED");
        assertThat(retriedAfter).isEqualTo(retriedBefore + 1.0);

        // Force remaining attempts to trigger terminal failure
        for (int i = 2; i <= properties.maxAttempts(); i++) {
            List<OutboxEvent> pending = outboxEventRepository.findByStatus(OutboxStatus.PENDING);
            for (OutboxEvent ev : pending) {
                ev.setAvailableAt(java.time.Instant.now().minusSeconds(1));
                outboxEventRepository.save(ev);
            }
            outboxProcessor.processBatch(10);
        }

        double failedAfter = getCounterValue("ledger.outbox.events.failed.total", "event_type", "TRANSFER_COMPLETED");
        assertThat(failedAfter).isEqualTo(failedBefore + 1.0);
    }

    @Test
    @DisplayName("All outbox metric labels are strictly bounded with zero high-cardinality tags")
    void metricLabelsAreStrictlyBounded() {
        Set<String> forbiddenTagKeys = Set.of(
                "eventId", "event_id",
                "transactionId", "transaction_id",
                "accountId", "account_id",
                "aggregateId", "aggregate_id",
                "idempotencyKey", "idempotency_key",
                "userId", "user_id",
                "payload", "error", "message"
        );

        for (Meter meter : meterRegistry.getMeters()) {
            String meterName = meter.getId().getName();
            if (meterName.startsWith("ledger.outbox")) {
                for (Tag tag : meter.getId().getTags()) {
                    assertThat(forbiddenTagKeys)
                            .as("Outbox metric %s must not contain high-cardinality tag %s", meterName, tag.getKey())
                            .doesNotContain(tag.getKey());
                }
            }
        }
    }

    @Test
    @DisplayName("Finding 1: Processor metrics are not emitted if transaction rolls back")
    void processorMetricsNotEmittedOnTransactionRollback() {
        double processedBefore = getCounterValue("ledger.outbox.events.processed.total", "event_type", "TRANSFER_COMPLETED", "outcome", "SUCCESS");

        outboxService.recordEvent(
                OutboxAggregateType.TRANSACTION,
                UUID.randomUUID(),
                OutboxEventType.TRANSFER_COMPLETED,
                Map.of("amount", "100.0000")
        );

        org.springframework.transaction.support.TransactionTemplate txTemplate =
                new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        txTemplate.execute(status -> {
            outboxProcessor.processBatch(10);
            status.setRollbackOnly();
            return null;
        });

        double processedAfter = getCounterValue("ledger.outbox.events.processed.total", "event_type", "TRANSFER_COMPLETED", "outcome", "SUCCESS");
        assertThat(processedAfter).isEqualTo(processedBefore);
    }
}
