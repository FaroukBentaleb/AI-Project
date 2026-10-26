package com.devassisthub.bug.repository;

import com.devassisthub.bug.entity.Bug;
import com.devassisthub.bug.entity.BugCategory;
import com.devassisthub.bug.entity.BugStatus;
import com.devassisthub.bug.entity.Severity;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;

/** Filters and ordering of the bug list. */
public final class BugSpecifications {

    /** Accepted values of the {@code sort} parameter. */
    public enum SortOrder { NEWEST, OLDEST, UPDATED, SEVERITY }

    private BugSpecifications() {
    }

    /**
     * Bugs matching every non-null filter; {@code text} is a case-insensitive search in the title and description.
     * The ordering is part of the specification because "severity" sorts by rank, not alphabetically.
     */
    public static Specification<Bug> matching(BugStatus status, Severity severity, BugCategory category, String text,
                                              SortOrder sort) {
        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (status != null) {
                where.add(cb.equal(root.get("status"), status));
            }
            if (severity != null) {
                where.add(cb.equal(root.get("severity"), severity));
            }
            if (category != null) {
                where.add(cb.equal(root.get("category"), category));
            }
            if (text != null && !text.isBlank()) {
                String like = "%" + escapeLike(text.strip().toLowerCase(Locale.ROOT)) + "%";
                where.add(cb.or(cb.like(cb.lower(root.get("title")), like, '\\'),
                        cb.like(cb.lower(root.get("description")), like, '\\')));
            }
            // The count query of a page must not carry an ORDER BY.
            Class<?> type = query.getResultType();
            if (type != Long.class && type != long.class) {
                switch (sort) {
                    case OLDEST -> query.orderBy(cb.asc(root.get("createdAt")), cb.asc(root.get("id")));
                    case UPDATED -> query.orderBy(cb.desc(root.get("updatedAt")), cb.desc(root.get("id")));
                    case SEVERITY -> {
                        Expression<Integer> rank = cb.<Severity, Integer>selectCase(root.get("severity"))
                                .when(Severity.CRITICAL, 4)
                                .when(Severity.HIGH, 3)
                                .when(Severity.MEDIUM, 2)
                                .otherwise(1);
                        query.orderBy(cb.desc(rank), cb.desc(root.get("createdAt")), cb.desc(root.get("id")));
                    }
                    default -> query.orderBy(cb.desc(root.get("createdAt")), cb.desc(root.get("id")));
                }
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
    }

    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
