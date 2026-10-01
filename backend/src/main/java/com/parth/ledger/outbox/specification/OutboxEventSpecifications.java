package com.parth.ledger.outbox.specification;

import com.parth.ledger.outbox.OutboxAggregateType;
import com.parth.ledger.outbox.OutboxEvent;
import com.parth.ledger.outbox.OutboxEventType;
import com.parth.ledger.outbox.OutboxStatus;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * JPA Specifications for dynamic filtering of OutboxEvent entities.
 */
public class OutboxEventSpecifications {

    private OutboxEventSpecifications() {
    }

    public static Specification<OutboxEvent> withFilters(
            OutboxStatus status,
            OutboxEventType eventType,
            OutboxAggregateType aggregateType,
            UUID aggregateId
    ) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (eventType != null) {
                predicates.add(cb.equal(root.get("eventType"), eventType));
            }
            if (aggregateType != null) {
                predicates.add(cb.equal(root.get("aggregateType"), aggregateType));
            }
            if (aggregateId != null) {
                predicates.add(cb.equal(root.get("aggregateId"), aggregateId));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
