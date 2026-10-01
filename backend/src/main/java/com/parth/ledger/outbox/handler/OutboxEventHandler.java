package com.parth.ledger.outbox.handler;

import com.parth.ledger.outbox.OutboxEvent;
import com.parth.ledger.outbox.OutboxEventType;

/**
 * Strategy interface for handling specific outbox event types.
 * Downstream implementations must be idempotent to handle at-least-once delivery semantics.
 */
public interface OutboxEventHandler {

    /**
     * Determines whether this handler processes the given event type.
     *
     * @param eventType Event type to evaluate.
     * @return true if supported, false otherwise.
     */
    boolean supports(OutboxEventType eventType);

    /**
     * Executes downstream business or integration logic for the given event.
     * Downstream processing must not make financial correctness depend on external side effects.
     *
     * @param event The outbox event record.
     */
    void handle(OutboxEvent event);
}
