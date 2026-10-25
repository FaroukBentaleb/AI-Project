package com.devassisthub.commit.repository;

import com.devassisthub.commit.entity.Project;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    @Query("select p from Project p where lower(p.name) = lower(:name)")
    Optional<Project> findByNameIgnoreCase(String name);
}
