package com.devassisthub.sql.repository;

import com.devassisthub.sql.entity.SavedQuery;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SavedQueryRepository extends JpaRepository<SavedQuery, Long>, SavedQuerySearch {

    /** Every API read/edit goes through this; soft-deleted rows are invisible (FR6). */
    Optional<SavedQuery> findByIdAndDeletedFalse(Long id);

    /**
     * Records one usage (FR8) in a single UPDATE so concurrent calls never lose increments (the row lock
     * serialises them). Bypasses @Version/@UpdateTimestamp on purpose: usage is not an edit.
     * Returns 0 when the row is unknown or soft-deleted.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE SavedQuery q SET q.useCount = q.useCount + 1, q.lastUsedAt = :at "
            + "WHERE q.id = :id AND q.deleted = false")
    int recordUsage(@Param("id") Long id, @Param("at") OffsetDateTime at);
}
