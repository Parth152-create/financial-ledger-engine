package com.parth.ledger.audit;

import com.parth.ledger.security.AuthenticatedUserService;
import com.parth.ledger.security.ratelimit.ClientIpResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditEventServiceTest {

    private AuditEventRepository auditEventRepository;
    private AuthenticatedUserService authenticatedUserService;
    private ClientIpResolver clientIpResolver;
    private AuditEventService auditEventService;

    @BeforeEach
    void setUp() {
        auditEventRepository = Mockito.mock(AuditEventRepository.class);
        authenticatedUserService = Mockito.mock(AuthenticatedUserService.class);
        clientIpResolver = Mockito.mock(ClientIpResolver.class);
        auditEventService = new AuditEventService(auditEventRepository, authenticatedUserService, clientIpResolver);

        when(auditEventRepository.save(any(AuditEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("Should successfully record audit event with safe metadata")
    void shouldRecordAuditEventWithSafeMetadata() {
        UUID actorId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();

        AuditEvent event = auditEventService.recordEvent(
                actorId,
                AuditEventType.ACCOUNT_CREATED,
                AuditEntityType.ACCOUNT,
                entityId,
                Map.of("currency", "INR", "accountType", "USER_CHECKING")
        );

        assertThat(event).isNotNull();
        assertThat(event.getActorUserId()).isEqualTo(actorId);
        assertThat(event.getEventType()).isEqualTo(AuditEventType.ACCOUNT_CREATED);
        assertThat(event.getEntityType()).isEqualTo(AuditEntityType.ACCOUNT);
        assertThat(event.getEntityId()).isEqualTo(entityId);
        assertThat(event.getMetadata()).containsEntry("currency", "INR")
                .containsEntry("accountType", "USER_CHECKING");

        verify(auditEventRepository).save(any(AuditEvent.class));
    }

    @Test
    @DisplayName("Should strip sensitive keys from metadata (passwords, tokens, secrets, sessions, credentials)")
    void shouldStripSensitiveKeysFromMetadata() {
        UUID actorId = UUID.randomUUID();

        Map<String, Object> input = Map.of(
                "safeKey", "safeValue",
                "password", "superSecret123",
                "userToken", "jwt-or-bearer-token",
                "clientSecret", "oauth-secret",
                "sessionId", "session-456",
                "csrfToken", "csrf-val",
                "userCredential", "hash"
        );

        AuditEvent event = auditEventService.recordEvent(
                actorId,
                AuditEventType.AUTH_LOGIN,
                AuditEntityType.USER,
                actorId,
                input
        );

        assertThat(event.getMetadata()).containsKey("safeKey");
        assertThat(event.getMetadata()).doesNotContainKey("password");
        assertThat(event.getMetadata()).doesNotContainKey("userToken");
        assertThat(event.getMetadata()).doesNotContainKey("clientSecret");
        assertThat(event.getMetadata()).doesNotContainKey("sessionId");
        assertThat(event.getMetadata()).doesNotContainKey("csrfToken");
        assertThat(event.getMetadata()).doesNotContainKey("userCredential");
    }

    @Test
    @DisplayName("Should reject null eventType or entityType")
    void shouldRejectNullEventTypeOrEntityType() {
        UUID actorId = UUID.randomUUID();

        assertThatThrownBy(() -> auditEventService.recordEvent(actorId, null, AuditEntityType.USER, actorId, Map.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("eventType");

        assertThatThrownBy(() -> auditEventService.recordEvent(actorId, AuditEventType.AUTH_LOGIN, null, actorId, Map.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("entityType");
    }

    @Test
    @DisplayName("Should truncate oversized metadata strings to safe bounds")
    void shouldTruncateOversizedMetadataStrings() {
        UUID actorId = UUID.randomUUID();
        String hugeString = "A".repeat(500);

        AuditEvent event = auditEventService.recordEvent(
                actorId,
                AuditEventType.AUTH_SIGNUP,
                AuditEntityType.USER,
                actorId,
                Map.of("longText", hugeString)
        );

        String stored = (String) event.getMetadata().get("longText");
        assertThat(stored).hasSize(255);
    }
}
