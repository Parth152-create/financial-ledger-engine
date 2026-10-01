package com.parth.ledger.outbox;

import com.parth.ledger.BaseIntegrationTest;
import com.parth.ledger.outbox.handler.OutboxEventHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxProcessorIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private OutboxService outboxService;

    @Autowired
    private OutboxProperties properties;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private OutboxProcessor outboxProcessor;
    private AtomicBoolean shouldFailHandler;
    private AtomicInteger handlerExecutionCount;

    @BeforeEach
    void setUp() {
        shouldFailHandler = new AtomicBoolean(false);
        handlerExecutionCount = new AtomicInteger(0);

        // Create a test handler that can be controlled to succeed or fail
        OutboxEventHandler testHandler = new OutboxEventHandler() {
            @Override
            public boolean supports(OutboxEventType eventType) {
                return true;
            }

            @Override
            public void handle(OutboxEvent event) {
                handlerExecutionCount.incrementAndGet();
                if (shouldFailHandler.get()) {
                    throw new RuntimeException("Simulated transient downstream error: connection timeout");
                }
            }
        };

        OutboxEventDispatcher dispatcher = new OutboxEventDispatcher(List.of(testHandler));
        outboxProcessor = new OutboxProcessor(outboxEventRepository, dispatcher, properties, null, transactionManager);
    }

    @Test
    @DisplayName("Pending outbox event is claimed, executed, and transitioned to PROCESSED")
    void pendingEventProcessedSuccessfully() {
        UUID txId = UUID.randomUUID();
        OutboxEvent event = outboxService.recordEvent(
                OutboxAggregateType.TRANSACTION,
                txId,
                OutboxEventType.TRANSFER_COMPLETED,
                Map.of("amount", "100.0000", "currency", "INR")
        );

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);

        int processedCount = outboxProcessor.processBatch(10);
        assertThat(processedCount).isEqualTo(1);
        assertThat(handlerExecutionCount.get()).isEqualTo(1);

        OutboxEvent updated = outboxEventRepository.findById(event.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(OutboxStatus.PROCESSED);
        assertThat(updated.getProcessedAt()).isNotNull();
        assertThat(updated.getLastError()).isNull();
    }

    @Test
    @DisplayName("Transient handler failure causes event to return to PENDING with exponential backoff and incremented attemptCount")
    void transientFailureSchedulesRetryWithBackoff() {
        UUID txId = UUID.randomUUID();
        OutboxEvent event = outboxService.recordEvent(
                OutboxAggregateType.TRANSACTION,
                txId,
                OutboxEventType.TRANSFER_COMPLETED,
                Map.of("amount", "100.0000", "currency", "INR")
        );

        // Instruct handler to fail
        shouldFailHandler.set(true);

        Instant beforeProcessing = Instant.now();
        int processedCount = outboxProcessor.processBatch(10);
        assertThat(processedCount).isEqualTo(1);

        OutboxEvent updated = outboxEventRepository.findById(event.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(updated.getAttemptCount()).isEqualTo(1);
        assertThat(updated.getProcessedAt()).isNull();
        assertThat(updated.getLastError()).contains("Simulated transient downstream error");
        assertThat(updated.getAvailableAt()).isAfter(beforeProcessing);

        // Immediate next batch does NOT claim this event because availableAt is in the future
        int secondBatch = outboxProcessor.processBatch(10);
        assertThat(secondBatch).isEqualTo(0);

        // Simulate time advancing: make event available now
        updated.setAvailableAt(Instant.now().minusSeconds(1));
        outboxEventRepository.save(updated);

        // Handler now recovers
        shouldFailHandler.set(false);

        int thirdBatch = outboxProcessor.processBatch(10);
        assertThat(thirdBatch).isEqualTo(1);

        OutboxEvent finalEvent = outboxEventRepository.findById(event.getId()).orElseThrow();
        assertThat(finalEvent.getStatus()).isEqualTo(OutboxStatus.PROCESSED);
        assertThat(finalEvent.getProcessedAt()).isNotNull();
        assertThat(finalEvent.getLastError()).isNull();
    }

    @Test
    @DisplayName("Repeated failures exceeding maxAttempts transition event to terminal FAILED state")
    void repeatedFailuresTransitionToFailed() {
        UUID txId = UUID.randomUUID();
        OutboxEvent event = outboxService.recordEvent(
                OutboxAggregateType.TRANSACTION,
                txId,
                OutboxEventType.TRANSFER_COMPLETED,
                Map.of("amount", "50.0000", "currency", "INR")
        );

        shouldFailHandler.set(true);

        // Exhaust all attempts up to maxAttempts (default 5)
        for (int i = 1; i <= properties.maxAttempts(); i++) {
            // Make available immediately
            OutboxEvent current = outboxEventRepository.findById(event.getId()).orElseThrow();
            current.setAvailableAt(Instant.now().minusSeconds(1));
            outboxEventRepository.save(current);

            outboxProcessor.processBatch(10);
        }

        OutboxEvent failedEvent = outboxEventRepository.findById(event.getId()).orElseThrow();
        assertThat(failedEvent.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(failedEvent.getAttemptCount()).isEqualTo(properties.maxAttempts());
        assertThat(failedEvent.getLastError()).isNotNull();

        // Terminal event is never claimed again
        int nextBatch = outboxProcessor.processBatch(10);
        assertThat(nextBatch).isEqualTo(0);
    }

    @Test
    @DisplayName("calculateBackoffDelay respects base delay, exponential factor, and max delay")
    void testBackoffCalculation() {
        // Base delay = 2, max = 300
        assertThat(outboxProcessor.calculateBackoffDelay(1)).isEqualTo(2);  // 2 * 2^0 = 2
        assertThat(outboxProcessor.calculateBackoffDelay(2)).isEqualTo(4);  // 2 * 2^1 = 4
        assertThat(outboxProcessor.calculateBackoffDelay(3)).isEqualTo(8);  // 2 * 2^2 = 8
        assertThat(outboxProcessor.calculateBackoffDelay(4)).isEqualTo(16); // 2 * 2^3 = 16
        assertThat(outboxProcessor.calculateBackoffDelay(10)).isLessThanOrEqualTo(300);
        assertThat(outboxProcessor.calculateBackoffDelay(50)).isEqualTo(300); // capped at max
    }
}
