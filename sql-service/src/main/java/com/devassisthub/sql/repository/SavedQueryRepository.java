package com.devassisthub.sql.repository;

import com.devassisthub.sql.entity.SavedQuery;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SavedQueryRepository extends JpaRepository<SavedQuery, Long> {
}
