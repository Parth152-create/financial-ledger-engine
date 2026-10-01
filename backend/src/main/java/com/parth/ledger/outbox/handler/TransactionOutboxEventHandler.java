package com.parth.ledger.outbox.handler;

import com.parth.ledger.observability.logging.MaskingUtils;
import com.parth.ledger.outbox.OutboxEvent;
import com.parth.ledger.outbox.OutboxEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Internal event handler for financial transaction outbox events.
 * Performs safe internal downstream processing and structured logging without external network dependencies.
 */
@Component
public class TransactionOutboxEventHandler implements OutboxEventHandler {

    private static final Logger log = LoggerFactory.getLogger(TransactionOutboxEventHandler.class);

    private static final Set<OutboxEventType> SUPPORTED_TYPES = Set.of(
            OutboxEventType.TRANSFER_COMPLETED,
            OutboxEventType.DEPOSIT_COMPLETED,
            OutboxEventType.WITHDRAWAL_COMPLETED,
            OutboxEventType.TRANSACTION_REVERSED
    );

    @Override
    public boolean supports(OutboxEventType eventType) {
        return SUPPORTED_TYPES.contains(eventType);
    }

    @Override
    public void handle(OutboxEvent event) {
        log.info("Processing financial transaction event: id={}, eventType={}, txId={}",
                event.getId(),
                event.getEventType(),
                MaskingUtils.maskAccountId(event.getAggregateId()));
    }
}
