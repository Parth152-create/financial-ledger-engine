package com.parth.ledger.transaction.specification;

import com.parth.ledger.transaction.Transaction;
import com.parth.ledger.transaction.TransactionStatus;
import com.parth.ledger.transaction.TransactionType;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * JPA Specifications for dynamic filtering and pagination of Transaction history.
 */
public final class TransactionSpecifications {

    private TransactionSpecifications() {
        // utility class
    }

    /**
     * Builds a JPA Specification to query transactions where the specified account is either the
     * source or destination, applying optional filters for type, status, and timestamp range.
     *
     * @param accountId       The account ID whose history is being retrieved.
     * @param transactionType Optional transaction type filter.
     * @param status          Optional transaction status filter.
     * @param from            Optional inclusive start timestamp (createdAt &gt;= from).
     * @param to              Optional exclusive end timestamp (createdAt &lt; to).
     * @return Specification matching the query criteria.
     */
    public static Specification<Transaction> forAccountWithFilters(
            UUID accountId,
            TransactionType transactionType,
            TransactionStatus status,
            Instant from,
            Instant to
    ) {
        return (root, query, cb) -> {
            // Join fetch associations for content query to avoid N+1 queries.
            // Exclude join fetches on count queries to avoid Hibernate SemanticException.
            if (Long.class != query.getResultType() && long.class != query.getResultType()) {
                root.fetch("sourceAccount", JoinType.INNER);
                root.fetch("destinationAccount", JoinType.INNER);
                root.fetch("initiatedByUser", JoinType.LEFT);
            }

            List<Predicate> predicates = new ArrayList<>();

            // 1. Account participation: source OR destination
            predicates.add(cb.or(
                    cb.equal(root.get("sourceAccount").get("id"), accountId),
                    cb.equal(root.get("destinationAccount").get("id"), accountId)
            ));

            // 2. Transaction Type filter
            if (transactionType != null) {
                predicates.add(cb.equal(root.get("transactionType"), transactionType));
            }

            // 3. Status filter
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }

            // 4. from filter (inclusive: createdAt >= from)
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            }

            // 5. to filter (exclusive: createdAt < to)
            if (to != null) {
                predicates.add(cb.lessThan(root.get("createdAt"), to));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
