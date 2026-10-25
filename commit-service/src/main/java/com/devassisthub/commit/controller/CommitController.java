package com.devassisthub.commit.controller;

import com.devassisthub.commit.dto.CommitDetail;
import com.devassisthub.commit.dto.CommitSummary;
import com.devassisthub.commit.dto.UpdateMessageRequest;
import com.devassisthub.commit.exception.ApiException;
import com.devassisthub.commit.service.CommitUploadService;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/commits")
public class CommitController {

    private final CommitUploadService service;

    public CommitController(CommitUploadService service) {
        this.service = service;
    }

    /** Uploads a ZIP of the project; answers with the diff against the previous upload and the generated message. */
    @PostMapping(value = "/uploads", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public CommitDetail upload(@RequestParam("project") String project, @RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            throw ApiException.invalidUpload("The uploaded file is empty");
        }
        try (InputStream in = file.getInputStream()) {
            return service.upload(project, in);
        } catch (IOException e) {
            throw ApiException.invalidUpload("The uploaded file could not be read");
        }
    }

    /** History, newest first; optionally for one project. */
    @GetMapping("/uploads")
    public List<CommitSummary> list(@RequestParam(value = "project", required = false) String project) {
        return service.list(project);
    }

    @GetMapping("/uploads/{id}")
    public CommitDetail get(@PathVariable long id) {
        return service.get(id);
    }

    @PutMapping("/uploads/{id}")
    public CommitDetail updateMessage(@PathVariable long id, @Valid @RequestBody UpdateMessageRequest request) {
        return service.updateMessage(id, request.message());
    }

    @DeleteMapping("/uploads/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        service.delete(id);
    }

    @GetMapping("/projects")
    public List<String> projects() {
        return service.projectNames();
    }
}
