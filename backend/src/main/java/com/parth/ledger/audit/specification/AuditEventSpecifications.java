package com.parth.ledger.audit.specification;

import com.parth.ledger.account.Account;
import com.parth.ledger.audit.AuditEvent;
import com.parth.ledger.audit.AuditEntityType;
import com.parth.ledger.audit.AuditEventType;
import com.parth.ledger.transaction.Transaction;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * JPA Specifications for authorized, paginated, and filtered operational audit events queries.
 */
public final class AuditEventSpecifications {

    private AuditEventSpecifications() {
        // utility class
    }

    /**
     * Builds a specification that:
     * 1. If not an administrator, strictly confines visible events to those belonging to the user's
     *    domain ownership boundary:
     *    - The user is the actor (actorUserId == userId)
     *    - The entity is the user (entityType == USER && entityId == userId)
     *    - The entity is an account owned by the user (entityType == ACCOUNT && entityId IN user accounts)
     *    - The entity is a transaction involving an account owned by the user (entityType == TRANSACTION && entityId IN user transactions)
     * 2. Applies optional filters for eventType, entityType, and date boundaries (from, to).
     */
    public static Specification<AuditEvent> forUserWithFilters(
            UUID userId,
            boolean isAdmin,
            AuditEventType eventType,
            AuditEntityType entityType,
            Instant from,
            Instant to
    ) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            // 1. Authorization boundary (non-admin callers are strictly partitioned to their domain scope)
            if (!isAdmin) {
                if (userId == null) {
                    // Safety fallback: if no user is identified and not admin, return no results
                    return cb.disjunction();
                }

                // Predicate 1: Caller was the actor
                Predicate isActor = cb.equal(root.get("actorUserId"), userId);

                // Predicate 2: Target entity is the user
                Predicate isTargetUser = cb.and(
                        cb.equal(root.get("entityType"), AuditEntityType.USER),
                        cb.equal(root.get("entityId"), userId)
                );

                // Predicate 3: Target entity is an account owned by the user
                Subquery<UUID> userAccountSubquery = query.subquery(UUID.class);
                Root<Account> accountRoot = userAccountSubquery.from(Account.class);
                userAccountSubquery.select(accountRoot.get("id"));
                userAccountSubquery.where(cb.equal(accountRoot.get("user").get("id"), userId));

                Predicate isTargetAccount = cb.and(
                        cb.equal(root.get("entityType"), AuditEntityType.ACCOUNT),
                        root.get("entityId").in(userAccountSubquery)
                );

                // Predicate 4: Target entity is a transaction involving an account owned by the user
                Subquery<UUID> userTxSubquery = query.subquery(UUID.class);
                Root<Transaction> txRoot = userTxSubquery.from(Transaction.class);
                userTxSubquery.select(txRoot.get("id"));
                userTxSubquery.where(cb.or(
                        cb.equal(txRoot.get("sourceAccount").get("user").get("id"), userId),
                        cb.equal(txRoot.get("destinationAccount").get("user").get("id"), userId)
                ));

                Predicate isTargetTx = cb.and(
                        cb.equal(root.get("entityType"), AuditEntityType.TRANSACTION),
                        root.get("entityId").in(userTxSubquery)
                );

                predicates.add(cb.or(isActor, isTargetUser, isTargetAccount, isTargetTx));
            }

            // 2. Filter by eventType
            if (eventType != null) {
                predicates.add(cb.equal(root.get("eventType"), eventType));
            }

            // 3. Filter by entityType
            if (entityType != null) {
                predicates.add(cb.equal(root.get("entityType"), entityType));
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
