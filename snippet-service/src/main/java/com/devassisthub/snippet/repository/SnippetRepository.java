package com.devassisthub.snippet.repository;

import com.devassisthub.snippet.dto.SnippetSummary;
import com.devassisthub.snippet.entity.Snippet;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SnippetRepository extends JpaRepository<Snippet, Long> {

    /** Summaries only: the code column is never read for the list. Empty strings mean "no filter". */
    @Query("""
            select new com.devassisthub.snippet.dto.SnippetSummary(s.id, s.title, s.language, s.docstring,
                s.docstringEdited, s.createdAt, s.updatedAt)
            from Snippet s
            where (:language = '' or s.language = :language)
              and (:q = '' or lower(s.title) like lower(concat('%', :q, '%'))
                   or lower(s.code) like lower(concat('%', :q, '%')))
            order by s.createdAt desc, s.id desc""")
    List<SnippetSummary> search(String language, String q);
}
