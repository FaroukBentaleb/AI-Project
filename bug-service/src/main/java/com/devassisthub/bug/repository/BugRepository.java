package com.devassisthub.bug.repository;

import com.devassisthub.bug.entity.Bug;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface BugRepository extends JpaRepository<Bug, Long>, JpaSpecificationExecutor<Bug> {

    /** Rows of {@code [status, count]}. */
    @Query("select b.status, count(b) from Bug b group by b.status")
    List<Object[]> countByStatus();

    /** Rows of {@code [severity, count]} over bugs that are not resolved or closed. */
    @Query("select b.severity, count(b) from Bug b where b.status in (com.devassisthub.bug.entity.BugStatus.OPEN, "
            + "com.devassisthub.bug.entity.BugStatus.IN_PROGRESS) group by b.severity")
    List<Object[]> countOpenBySeverity();

    /** Rows of {@code [category, count]}. */
    @Query("select b.category, count(b) from Bug b group by b.category")
    List<Object[]> countByCategory();
}
