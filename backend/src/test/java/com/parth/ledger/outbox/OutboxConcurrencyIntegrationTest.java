package com.parth.ledger.outbox;

import com.parth.ledger.BaseIntegrationTest;
import com.parth.ledger.outbox.handler.OutboxEventHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = {
        "ledger.outbox.enabled=false"
})
class OutboxConcurrencyIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private OutboxService outboxService;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private Set<UUID> processedEventIds;
    private AtomicInteger duplicateProcessingCount;
    private OutboxProcessor outboxProcessor;

    @BeforeEach
    void setUp() {
        processedEventIds = Collections.newSetFromMap(new ConcurrentHashMap<>());
        duplicateProcessingCount = new AtomicInteger(0);

        OutboxEventHandler concurrentHandler = new OutboxEventHandler() {
            @Override
            public boolean supports(OutboxEventType eventType) {
                return true;
            }

            @Override
            public void handle(OutboxEvent event) {
                // If this event was already processed by another worker, record duplicate!
                if (!processedEventIds.add(event.getId())) {
                    duplicateProcessingCount.incrementAndGet();
                }
                // Small sleep to increase concurrency interleaving
                try {
                    Thread.sleep(10);
                } catch (InterruptedException ignored) {
                }
            }
        };

        OutboxEventDispatcher dispatcher = new OutboxEventDispatcher(List.of(concurrentHandler));
        OutboxProperties testProperties = OutboxProperties.defaults();
        outboxProcessor = new OutboxProcessor(outboxEventRepository, dispatcher, testProperties, null, transactionManager);
    }

    @AfterEach
    void tearDown() {
        if (jdbcTemplate != null) {
            try {
                jdbcTemplate.execute("TRUNCATE TABLE outbox_events CASCADE");
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    @DisplayName("Multiple concurrent workers using FOR UPDATE SKIP LOCKED do not claim or process the same event")
    void concurrentWorkersDoNotClaimSameEvent() throws InterruptedException {
        int totalEvents = 20;
        List<UUID> createdIds = new ArrayList<>();

        for (int i = 0; i < totalEvents; i++) {
            OutboxEvent event = outboxService.recordEvent(
                    OutboxAggregateType.TRANSACTION,
                    UUID.randomUUID(),
                    OutboxEventType.TRANSFER_COMPLETED,
                    Map.of("index", i, "amount", "100.0000")
            );
            createdIds.add(event.getId());
        }

        int workerCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        CountDownLatch readyLatch = new CountDownLatch(workerCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(workerCount);

        AtomicInteger totalClaimed = new AtomicInteger(0);

        for (int i = 0; i < workerCount; i++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    // Each worker attempts to process batches until no more eligible events remain
                    int processed;
                    do {
                        processed = outboxProcessor.processBatch(5);
                        totalClaimed.addAndGet(processed);
                    } while (processed > 0);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown(); // Release all workers concurrently
        boolean completed = doneLatch.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(duplicateProcessingCount.get()).isZero();
        assertThat(processedEventIds).hasSize(totalEvents);
        assertThat(totalClaimed.get()).isEqualTo(totalEvents);

        // Verify database state: all 20 events must be PROCESSED
        List<OutboxEvent> allInDb = outboxEventRepository.findAll();
        assertThat(allInDb).hasSize(totalEvents);
        for (OutboxEvent event : allInDb) {
            assertThat(event.getStatus()).isEqualTo(OutboxStatus.PROCESSED);
            assertThat(event.getProcessedAt()).isNotNull();
        }
    }
}
