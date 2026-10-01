package com.parth.ledger.outbox;

import com.parth.ledger.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxDatabaseSchemaIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Test
    @DisplayName("V14 migration creates outbox_events table with all required columns")
    void outboxEventsTableHasExpectedColumns() {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList("""
            SELECT column_name, data_type, is_nullable
            FROM information_schema.columns
            WHERE table_name = 'outbox_events'
            ORDER BY ordinal_position
        """);

        List<String> columnNames = columns.stream()
                .map(c -> (String) c.get("column_name"))
                .toList();

        assertThat(columnNames).contains(
                "id",
                "aggregate_type",
                "aggregate_id",
                "event_type",
                "payload",
                "status",
                "attempt_count",
                "available_at",
                "created_at",
                "processed_at",
                "last_error"
        );
    }

    @Test
    @DisplayName("outbox_events indexes exist as specified")
    void outboxEventsIndexesExist() {
        List<String> indexNames = jdbcTemplate.queryForList("""
            SELECT indexname FROM pg_indexes WHERE tablename = 'outbox_events'
        """, String.class);

        assertThat(indexNames).contains(
                "idx_outbox_events_status_available",
                "idx_outbox_events_created_at",
                "idx_outbox_events_aggregate",
                "idx_outbox_events_event_type",
                "uk_outbox_events_transaction_event",
                "uk_outbox_events_account_created",
                "uk_outbox_events_account_closed"
        );
    }

    @Test
    @DisplayName("Valid outbox event statuses are accepted")
    void validStatusesAccepted() {
        for (String status : List.of("PENDING", "PROCESSING", "PROCESSED", "FAILED")) {
            UUID id = UUID.randomUUID();
            UUID aggId = UUID.randomUUID();
            String processedAtSql = "PROCESSED".equals(status) ? "NOW()" : "NULL";
            jdbcTemplate.update("""
                INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload, status, attempt_count, available_at, created_at, processed_at)
                VALUES (?, 'TRANSACTION', ?, 'TRANSFER_COMPLETED', '{"test":true}'::jsonb, ?, 0, NOW(), NOW(), """ + processedAtSql + ")",
                    id, aggId, status);

            assertThat(outboxEventRepository.existsById(id)).isTrue();
        }
    }

    @Test
    @DisplayName("Invalid outbox event status is rejected by CHECK constraint")
    void invalidStatusRejected() {
        UUID id = UUID.randomUUID();
        UUID aggId = UUID.randomUUID();

        assertThatThrownBy(() -> jdbcTemplate.update("""
            INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload, status, attempt_count, available_at, created_at)
            VALUES (?, 'TRANSACTION', ?, 'TRANSFER_COMPLETED', '{"test":true}'::jsonb, 'UNKNOWN_STATUS', 0, NOW(), NOW())
        """, id, aggId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Negative attempt count is rejected by CHECK constraint")
    void negativeAttemptCountRejected() {
        UUID id = UUID.randomUUID();
        UUID aggId = UUID.randomUUID();

        assertThatThrownBy(() -> jdbcTemplate.update("""
            INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload, status, attempt_count, available_at, created_at)
            VALUES (?, 'TRANSACTION', ?, 'TRANSFER_COMPLETED', '{"test":true}'::jsonb, 'PENDING', -1, NOW(), NOW())
        """, id, aggId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Invalid event type is rejected by CHECK constraint")
    void invalidEventTypeRejected() {
        UUID id = UUID.randomUUID();
        UUID aggId = UUID.randomUUID();

        assertThatThrownBy(() -> jdbcTemplate.update("""
            INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload, status, attempt_count, available_at, created_at)
            VALUES (?, 'TRANSACTION', ?, 'INVALID_TYPE', '{"test":true}'::jsonb, 'PENDING', 0, NOW(), NOW())
        """, id, aggId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Invalid aggregate type is rejected by CHECK constraint")
    void invalidAggregateTypeRejected() {
        UUID id = UUID.randomUUID();
        UUID aggId = UUID.randomUUID();

        assertThatThrownBy(() -> jdbcTemplate.update("""
            INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload, status, attempt_count, available_at, created_at)
            VALUES (?, 'UNKNOWN_AGGREGATE', ?, 'TRANSFER_COMPLETED', '{"test":true}'::jsonb, 'PENDING', 0, NOW(), NOW())
        """, id, aggId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Duplicate completion event for the same transaction is rejected by uk_outbox_events_transaction_event")
    void duplicateTransactionEventRejected() {
        UUID txId = UUID.randomUUID();

        jdbcTemplate.update("""
            INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload, status, attempt_count, available_at, created_at)
            VALUES (?, 'TRANSACTION', ?, 'TRANSFER_COMPLETED', '{"test":1}'::jsonb, 'PENDING', 0, NOW(), NOW())
        """, UUID.randomUUID(), txId);

        assertThatThrownBy(() -> jdbcTemplate.update("""
            INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload, status, attempt_count, available_at, created_at)
            VALUES (?, 'TRANSACTION', ?, 'TRANSFER_COMPLETED', '{"test":2}'::jsonb, 'PENDING', 0, NOW(), NOW())
        """, UUID.randomUUID(), txId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Duplicate ACCOUNT_CREATED event for the same account is rejected by uk_outbox_events_account_created")
    void duplicateAccountCreatedEventRejected() {
        UUID accountId = UUID.randomUUID();

        jdbcTemplate.update("""
            INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload, status, attempt_count, available_at, created_at)
            VALUES (?, 'ACCOUNT', ?, 'ACCOUNT_CREATED', '{"test":1}'::jsonb, 'PENDING', 0, NOW(), NOW())
        """, UUID.randomUUID(), accountId);

        assertThatThrownBy(() -> jdbcTemplate.update("""
            INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload, status, attempt_count, available_at, created_at)
            VALUES (?, 'ACCOUNT', ?, 'ACCOUNT_CREATED', '{"test":2}'::jsonb, 'PENDING', 0, NOW(), NOW())
        """, UUID.randomUUID(), accountId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
