package com.parth.ledger.outbox;

import com.parth.ledger.outbox.handler.OutboxEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Dispatches claimed outbox events to registered handlers.
 * Decouples event dispatching from the polling, locking, and persistence lifecycle.
 */
@Component
public class OutboxEventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxEventDispatcher.class);

    private final List<OutboxEventHandler> handlers;

    public OutboxEventDispatcher(List<OutboxEventHandler> handlers) {
        this.handlers = handlers != null ? handlers : List.of();
    }

    /**
     * Dispatches the event to all supporting handlers.
     *
     * @param event The event being processed.
     */
    public void dispatch(OutboxEvent event) {
        boolean handled = false;
        for (OutboxEventHandler handler : handlers) {
            if (handler.supports(event.getEventType())) {
                handler.handle(event);
                handled = true;
            }
        }
        if (!handled) {
            log.debug("No specific handler registered for event type {}. Event processed with default handling: id={}",
                    event.getEventType(), event.getId());
        }
    }
}
