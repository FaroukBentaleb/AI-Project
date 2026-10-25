package com.devassisthub.commit.service;

import com.devassisthub.commit.dto.CommitDetail;
import com.devassisthub.commit.dto.CommitSummary;
import com.devassisthub.commit.entity.CommitUpload;
import com.devassisthub.commit.entity.Project;
import com.devassisthub.commit.exception.ApiException;
import com.devassisthub.commit.repository.CommitUploadRepository;
import com.devassisthub.commit.repository.ProjectRepository;
import com.devassisthub.commit.service.DiffBuilder.FileDiff;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Upload flow: read the ZIP, diff it against the project's previous upload (the whole project on the first upload),
 * ask the model for a commit message, store snapshot + diff + message. The model call runs outside any database
 * transaction, and the project row is only created once the model has answered.
 */
@Service
public class CommitUploadService {

    static final int MAX_PROJECT_NAME = 100;
    private static final TypeReference<Map<String, String>> SNAPSHOT_TYPE = new TypeReference<>() {
    };

    private final ZipSnapshotReader reader;
    private final DiffBuilder diffBuilder;
    private final DiffTrimmer trimmer;
    private final ModelClient model;
    private final ProjectRepository projects;
    private final CommitUploadRepository uploads;
    private final ObjectMapper mapper;

    public CommitUploadService(ZipSnapshotReader reader, DiffBuilder diffBuilder, DiffTrimmer trimmer,
                               ModelClient model, ProjectRepository projects, CommitUploadRepository uploads,
                               ObjectMapper mapper) {
        this.reader = reader;
        this.diffBuilder = diffBuilder;
        this.trimmer = trimmer;
        this.model = model;
        this.projects = projects;
        this.uploads = uploads;
        this.mapper = mapper;
    }

    public CommitDetail upload(String projectName, InputStream zip) {
        String name = validName(projectName);
        Map<String, String> after = reader.read(zip);

        Project existing = projects.findByNameIgnoreCase(name).orElse(null);
        CommitUpload previous = existing == null ? null
                : uploads.findFirstByProjectIdOrderByCreatedAtDescIdDesc(existing.getId()).orElse(null);
        Map<String, String> before = previous == null ? Map.of() : readSnapshot(previous.getSnapshot());

        List<FileDiff> diffs = diffBuilder.build(before, after);
        if (diffs.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "NO_CHANGES",
                    previous == null ? "The ZIP has no content to describe" : "No changes since the previous upload");
        }
        DiffTrimmer.Trimmed input = trimmer.trim(diffs);
        String message = model.generate(input.text());

        Project project = existing != null ? existing : projects.save(new Project(name));
        CommitUpload saved = uploads.save(new CommitUpload(project, writeSnapshot(after), DiffBuilder.join(diffs),
                message, previous == null, input.truncated(), after.size(), diffs.size()));
        return CommitDetail.of(saved);
    }

    @Transactional(readOnly = true)
    public List<CommitSummary> list(String project) {
        return project == null || project.isBlank() ? uploads.findAllSummaries()
                : uploads.findSummariesByProject(project.strip());
    }

    @Transactional(readOnly = true)
    public CommitDetail get(long id) {
        return CommitDetail.of(find(id));
    }

    @Transactional
    public CommitDetail updateMessage(long id, String message) {
        CommitUpload upload = find(id);
        upload.editMessage(message.strip());
        return CommitDetail.of(upload);
    }

    public void delete(long id) {
        if (!uploads.existsById(id)) {
            throw ApiException.notFound("Commit");
        }
        uploads.deleteById(id);
    }

    @Transactional(readOnly = true)
    public List<String> projectNames() {
        return projects.findAll(Sort.by("name")).stream().map(Project::getName).toList();
    }

    private CommitUpload find(long id) {
        return uploads.findWithProjectById(id).orElseThrow(() -> ApiException.notFound("Commit"));
    }

    private static String validName(String projectName) {
        String name = projectName == null ? "" : projectName.strip();
        if (name.isEmpty() || name.length() > MAX_PROJECT_NAME) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED",
                    "The project name must be 1 to " + MAX_PROJECT_NAME + " characters", "project");
        }
        return name;
    }

    private Map<String, String> readSnapshot(String json) {
        try {
            return mapper.readValue(json, SNAPSHOT_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored snapshot is not readable", e);
        }
    }

    private String writeSnapshot(Map<String, String> files) {
        try {
            return mapper.writeValueAsString(files);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Snapshot could not be serialized", e);
        }
    }
}
