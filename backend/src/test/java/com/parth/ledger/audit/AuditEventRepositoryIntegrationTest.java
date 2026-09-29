package com.parth.ledger.audit;

import com.parth.ledger.BaseIntegrationTest;
import com.parth.ledger.user.User;
import com.parth.ledger.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditEventRepositoryIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void cleanUp() {
        if (jdbcTemplate != null) {
            try {
                jdbcTemplate.execute("TRUNCATE TABLE audit_events CASCADE");
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    @DisplayName("Should successfully persist and query audit event with jsonb metadata")
    void shouldPersistAndQueryAuditEvent() {
        User user = userRepository.save(new User("audit.user@example.com", "Audit User"));

        AuditEvent event = new AuditEvent(
                user.getId(),
                AuditEventType.AUTH_LOGIN,
                AuditEntityType.USER,
                user.getId(),
                Map.of("method", "PASSWORD", "role", "USER"),
                "192.168.1.1",
                "Mozilla/5.0"
        );

        AuditEvent saved = auditEventRepository.save(event);
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();

        Optional<AuditEvent> found = auditEventRepository.findById(saved.getId());
        assertThat(found).isPresent();
        AuditEvent retrieved = found.get();
        assertThat(retrieved.getActorUserId()).isEqualTo(user.getId());
        assertThat(retrieved.getEventType()).isEqualTo(AuditEventType.AUTH_LOGIN);
        assertThat(retrieved.getEntityType()).isEqualTo(AuditEntityType.USER);
        assertThat(retrieved.getEntityId()).isEqualTo(user.getId());
        assertThat(retrieved.getIpAddress()).isEqualTo("192.168.1.1");
        assertThat(retrieved.getUserAgent()).isEqualTo("Mozilla/5.0");
        assertThat(retrieved.getMetadata()).containsEntry("method", "PASSWORD");
    }

    @Test
    @DisplayName("Database trigger should reject UPDATE on audit_events")
    void databaseTriggerShouldRejectUpdate() {
        AuditEvent event = new AuditEvent(
                null,
                AuditEventType.ACCOUNT_CREATED,
                AuditEntityType.ACCOUNT,
                UUID.randomUUID(),
                Map.of("accountType", "USER_CHECKING"),
                "127.0.0.1",
                "TestClient"
        );
        AuditEvent saved = auditEventRepository.save(event);

        assertThatThrownBy(() -> {
            jdbcTemplate.update("UPDATE audit_events SET ip_address = 'tampered' WHERE id = ?", saved.getId());
        }).isInstanceOf(DataAccessException.class)
          .hasMessageContaining("Audit events are immutable");
    }

    @Test
    @DisplayName("Database trigger should reject DELETE on audit_events")
    void databaseTriggerShouldRejectDelete() {
        AuditEvent event = new AuditEvent(
                null,
                AuditEventType.ACCOUNT_CREATED,
                AuditEntityType.ACCOUNT,
                UUID.randomUUID(),
                Map.of("accountType", "USER_CHECKING"),
                "127.0.0.1",
                "TestClient"
        );
        AuditEvent saved = auditEventRepository.save(event);

        assertThatThrownBy(() -> {
            jdbcTemplate.update("DELETE FROM audit_events WHERE id = ?", saved.getId());
        }).isInstanceOf(DataAccessException.class)
          .hasMessageContaining("Audit events are immutable");
    }

    @Test
    @DisplayName("Partial unique index should prevent duplicate transaction completion audit events")
    void partialUniqueIndexShouldPreventDuplicateTransactionCompletion() {
        UUID txId = UUID.randomUUID();

        AuditEvent event1 = new AuditEvent(
                null,
                AuditEventType.TRANSFER_COMPLETED,
                AuditEntityType.TRANSACTION,
                txId,
                Map.of("amount", 100),
                "127.0.0.1",
                "TestClient"
        );
        auditEventRepository.save(event1);

        AuditEvent event2 = new AuditEvent(
                null,
                AuditEventType.TRANSFER_COMPLETED,
                AuditEntityType.TRANSACTION,
                txId,
                Map.of("amount", 100),
                "127.0.0.1",
                "TestClient"
        );

        assertThatThrownBy(() -> {
            auditEventRepository.saveAndFlush(event2);
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Partial unique index should prevent duplicate account created audit events")
    void partialUniqueIndexShouldPreventDuplicateAccountCreated() {
        UUID accountId = UUID.randomUUID();

        AuditEvent event1 = new AuditEvent(
                null,
                AuditEventType.ACCOUNT_CREATED,
                AuditEntityType.ACCOUNT,
                accountId,
                Map.of("accountType", "USER_CHECKING"),
                "127.0.0.1",
                "TestClient"
        );
        auditEventRepository.save(event1);

        AuditEvent event2 = new AuditEvent(
                null,
                AuditEventType.ACCOUNT_CREATED,
                AuditEntityType.ACCOUNT,
                accountId,
                Map.of("accountType", "USER_CHECKING"),
                "127.0.0.1",
                "TestClient"
        );

        assertThatThrownBy(() -> {
            auditEventRepository.saveAndFlush(event2);
        }).isInstanceOf(DataIntegrityViolationException.class);
    }
}
