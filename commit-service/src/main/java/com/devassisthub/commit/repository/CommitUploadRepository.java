package com.devassisthub.commit.repository;

import com.devassisthub.commit.dto.CommitSummary;
import com.devassisthub.commit.entity.CommitUpload;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CommitUploadRepository extends JpaRepository<CommitUpload, Long> {

    /** Newest first; the first row is the base for the next diff. */
    Optional<CommitUpload> findFirstByProjectIdOrderByCreatedAtDescIdDesc(Long projectId);

    @Query("select u from CommitUpload u join fetch u.project where u.id = :id")
    Optional<CommitUpload> findWithProjectById(Long id);

    /** Summaries only: the snapshot and diff columns are never read for the list. */
    @Query("""
            select new com.devassisthub.commit.dto.CommitSummary(u.id, p.name, u.createdAt, u.message,
                u.messageEdited, u.initial, u.truncated, u.fileCount, u.filesChanged)
            from CommitUpload u join u.project p
            order by u.createdAt desc, u.id desc""")
    List<CommitSummary> findAllSummaries();

    @Query("""
            select new com.devassisthub.commit.dto.CommitSummary(u.id, p.name, u.createdAt, u.message,
                u.messageEdited, u.initial, u.truncated, u.fileCount, u.filesChanged)
            from CommitUpload u join u.project p
            where lower(p.name) = lower(:project)
            order by u.createdAt desc, u.id desc""")
    List<CommitSummary> findSummariesByProject(String project);
}
