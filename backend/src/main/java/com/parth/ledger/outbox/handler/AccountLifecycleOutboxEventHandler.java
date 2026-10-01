package com.parth.ledger.outbox.handler;

import com.parth.ledger.observability.logging.MaskingUtils;
import com.parth.ledger.outbox.OutboxEvent;
import com.parth.ledger.outbox.OutboxEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Internal event handler for account lifecycle outbox events.
 * Handles account creation, freezing, unfreezing, and closure events.
 */
@Component
public class AccountLifecycleOutboxEventHandler implements OutboxEventHandler {

    private static final Logger log = LoggerFactory.getLogger(AccountLifecycleOutboxEventHandler.class);

    private static final Set<OutboxEventType> SUPPORTED_TYPES = Set.of(
            OutboxEventType.ACCOUNT_CREATED,
            OutboxEventType.ACCOUNT_FROZEN,
            OutboxEventType.ACCOUNT_UNFROZEN,
            OutboxEventType.ACCOUNT_CLOSED
    );

    @Override
    public boolean supports(OutboxEventType eventType) {
        return SUPPORTED_TYPES.contains(eventType);
    }

    @Override
    public void handle(OutboxEvent event) {
        log.info("Processing account lifecycle event: id={}, eventType={}, accountId={}",
                event.getId(),
                event.getEventType(),
                MaskingUtils.maskAccountId(event.getAggregateId()));
    }
}
