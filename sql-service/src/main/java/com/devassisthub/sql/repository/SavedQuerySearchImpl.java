package com.devassisthub.sql.repository;

import com.devassisthub.sql.entity.SavedQuery;
import com.devassisthub.sql.service.LibraryFilter;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Native SQL so the tag filter can use PostgreSQL's {@code &&} against {@code text[]} (served by the V2 GIN
 * index); Hibernate 6.5 criteria binds list parameters as {@code varchar[]}, which has no such operator.
 * Only whitelisted column names and bound parameters reach the SQL.
 */
class SavedQuerySearchImpl implements SavedQuerySearch {

    /** Entity property (as produced by LibraryPaging) -> column. Anything else is a programming error. */
    private static final Map<String, String> COLUMNS = Map.of(
            "id", "id", "title", "title", "createdAt", "created_at", "updatedAt", "updated_at",
            "useCount", "use_count", "lastUsedAt", "last_used_at");

    @PersistenceContext
    private EntityManager em;

    @Override
    public Page<SavedQuery> search(LibraryFilter filter, Pageable pageable) {
        List<String> where = new ArrayList<>();
        where.add("deleted = false");
        Map<String, Object> params = new LinkedHashMap<>();
        if (filter.q() != null) {
            where.add("(lower(title) LIKE :q ESCAPE '\\' OR lower(description) LIKE :q ESCAPE '\\')");
            params.put("q", "%" + LibraryFilter.escapeLike(filter.q().toLowerCase(Locale.ROOT)) + "%");
        }
        if (filter.tags() != null) {
            where.add("tags && cast(:tags AS text[])");
            params.put("tags", filter.tags().toArray(String[]::new));
        }
        if (filter.author() != null) {
            where.add("author = :author");
            params.put("author", filter.author());
        }
        if (filter.llmGenerated() != null) {
            where.add("is_llm_generated = :llm");
            params.put("llm", filter.llmGenerated());
        }
        if (filter.deprecated() != null) {
            where.add("deprecated = :deprecated");
            params.put("deprecated", filter.deprecated());
        }
        String whereSql = " WHERE " + String.join(" AND ", where);

        Query select = em.createNativeQuery("SELECT * FROM saved_query" + whereSql + orderBy(pageable.getSort())
                + " OFFSET :offset LIMIT :limit", SavedQuery.class);
        params.forEach(select::setParameter);
        select.setParameter("offset", pageable.getOffset());
        select.setParameter("limit", pageable.getPageSize());
        @SuppressWarnings("unchecked")
        List<SavedQuery> content = select.getResultList();

        Query count = em.createNativeQuery("SELECT count(*) FROM saved_query" + whereSql, Long.class);
        params.forEach(count::setParameter);
        long total = ((Number) count.getSingleResult()).longValue();
        return new PageImpl<>(content, pageable, total);
    }

    static String orderBy(Sort sort) {
        if (sort.isUnsorted()) {
            return " ORDER BY created_at DESC, id DESC";
        }
        List<String> parts = new ArrayList<>();
        for (Sort.Order o : sort) {
            String column = COLUMNS.get(o.getProperty());
            if (column == null) {
                throw new IllegalArgumentException("Unsupported sort property " + o.getProperty());
            }
            // Never-used rows (last_used_at NULL) sort last in both directions.
            String nulls = column.equals("last_used_at") ? " NULLS LAST" : "";
            parts.add(column + (o.isAscending() ? " ASC" : " DESC") + nulls);
        }
        return " ORDER BY " + String.join(", ", parts);
    }
}
