package com.devassisthub.sql.repository;

import com.devassisthub.sql.entity.SavedQuery;
import com.devassisthub.sql.service.LibraryFilter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Library search fragment (FR-4): filters AND-combined, paged and sorted. */
public interface SavedQuerySearch {

    Page<SavedQuery> search(LibraryFilter filter, Pageable pageable);
}
