package com.devassisthub.sql.repository;

import com.devassisthub.sql.entity.SavedQuery;
import java.time.OffsetDateTime;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

    /** Stores the indexed flag (Story 5.3) without bumping @Version or updated_at: indexing is not an edit. */
    @Modifying
    @Query("UPDATE SavedQuery q SET q.indexed = :indexed WHERE q.id = :id")
    int setIndexed(@Param("id") Long id, @Param("indexed") boolean indexed);

    /** Reconciliation (5.4): re-reads one row under a row lock so a concurrent edit cannot race its payload. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT q FROM SavedQuery q WHERE q.id = :id")
    Optional<SavedQuery> findForIndexing(@Param("id") Long id);

    /** Reconciliation (5.4): {@code [id, qualifies, indexed]} for the next batch of rows after {@code afterId}. */
    @Query("SELECT q.id, (CASE WHEN q.readOnly = true AND q.deleted = false AND q.deprecated = false "
            + "THEN true ELSE false END), q.indexed FROM SavedQuery q WHERE q.id > :afterId ORDER BY q.id")
    List<Object[]> findIndexStateAfter(@Param("afterId") long afterId, Pageable page);

    /** Ids among {@code ids} whose row satisfies the indexing rule. */
    @Query("SELECT q.id FROM SavedQuery q WHERE q.id IN :ids AND q.readOnly = true AND q.deleted = false "
            + "AND q.deprecated = false")
    List<Long> findQualifyingIds(@Param("ids") Collection<Long> ids);

    @Query("SELECT COALESCE(MAX(q.id), 0) FROM SavedQuery q")
    long maxId();

    /** Seeding (5.5): true when a row with this seed_key exists, deleted or not (user deletes are respected). */
    boolean existsBySeedKey(String seedKey);
}
