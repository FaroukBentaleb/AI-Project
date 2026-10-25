package com.devassisthub.commit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.devassisthub.commit.config.ModelProperties;
import com.devassisthub.commit.config.UploadProperties;
import com.devassisthub.commit.dto.CommitDetail;
import com.devassisthub.commit.entity.CommitUpload;
import com.devassisthub.commit.entity.Project;
import com.devassisthub.commit.exception.ApiException;
import com.devassisthub.commit.repository.CommitUploadRepository;
import com.devassisthub.commit.repository.ProjectRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

class CommitUploadServiceTest {

    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final CommitUploadRepository uploads = mock(CommitUploadRepository.class);
    private final ModelClient model = mock(ModelClient.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private CommitUploadService service;

    @BeforeEach
    void setUp() {
        ModelProperties props = new ModelProperties("http://x", Duration.ofSeconds(1), Duration.ofSeconds(1), 1,
                4000, 1500);
        service = new CommitUploadService(new ZipSnapshotReader(new UploadProperties(50, 100_000, 1_000_000)),
                new DiffBuilder(), new DiffTrimmer(props), model, projects, uploads, mapper);
        when(projects.save(any(Project.class))).thenAnswer(i -> i.getArgument(0));
        when(uploads.save(any(CommitUpload.class))).thenAnswer(i -> i.getArgument(0));
    }

    private static InputStream zipOf(String... pathAndContent) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (int i = 0; i < pathAndContent.length; i += 2) {
            entries.put(pathAndContent[i], ZipSnapshotReaderTest.text(pathAndContent[i + 1]));
        }
        return new ByteArrayInputStream(ZipSnapshotReaderTest.zip(entries));
    }

    private CommitUpload previousUpload(Project project, String snapshotJson) {
        return new CommitUpload(project, snapshotJson, "old diff", "feat: previous", true, false, 1, 1);
    }

    @Test
    void firstUploadSendsTheWholeProjectAndMarksItInitial() throws IOException {
        when(projects.findByNameIgnoreCase("demo")).thenReturn(Optional.empty());
        when(model.generate(anyString())).thenReturn("feat: add index page");

        CommitDetail detail = service.upload("  demo ", zipOf("index.html", "<h1>Hi</h1>\n"));

        assertThat(detail.initial()).isTrue();
        assertThat(detail.project()).isEqualTo("demo");
        assertThat(detail.message()).isEqualTo("feat: add index page");
        assertThat(detail.filesChanged()).isEqualTo(1);
        assertThat(detail.diff()).contains("+++ b/index.html", "+<h1>Hi</h1>");
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(model).generate(sent.capture());
        assertThat(sent.getValue()).contains("+<h1>Hi</h1>");
    }

    @Test
    void laterUploadIsDiffedAgainstThePreviousSnapshot() throws IOException {
        Project project = new Project("demo");
        when(projects.findByNameIgnoreCase("Demo")).thenReturn(Optional.of(project));
        when(uploads.findFirstByProjectIdOrderByCreatedAtDescIdDesc(any()))
                .thenReturn(Optional.of(previousUpload(project, "{\"index.html\":\"<h1>Hi</h1>\\n\"}")));
        when(model.generate(anyString())).thenReturn("fix: change title");

        CommitDetail detail = service.upload("Demo", zipOf("index.html", "<h1>Hello</h1>\n"));

        assertThat(detail.initial()).isFalse();
        assertThat(detail.diff()).contains("-<h1>Hi</h1>", "+<h1>Hello</h1>");
        verify(projects, never()).save(any(Project.class));
    }

    @Test
    void identicalUploadIsRejectedWithoutCallingTheModel() throws IOException {
        Project project = new Project("demo");
        when(projects.findByNameIgnoreCase("demo")).thenReturn(Optional.of(project));
        when(uploads.findFirstByProjectIdOrderByCreatedAtDescIdDesc(any()))
                .thenReturn(Optional.of(previousUpload(project, "{\"index.html\":\"same\\n\"}")));

        assertThatThrownBy(() -> service.upload("demo", zipOf("index.html", "same\n")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getError()).isEqualTo("NO_CHANGES");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        verify(model, never()).generate(anyString());
    }

    @Test
    void modelFailureStoresNothingAndCreatesNoProject() throws IOException {
        when(projects.findByNameIgnoreCase("demo")).thenReturn(Optional.empty());
        when(model.generate(anyString())).thenThrow(
                new ApiException(HttpStatus.BAD_GATEWAY, "MODEL_UNAVAILABLE", "down"));

        assertThatThrownBy(() -> service.upload("demo", zipOf("a.txt", "x\n"))).isInstanceOf(ApiException.class);

        verify(projects, never()).save(any(Project.class));
        verify(uploads, never()).save(any(CommitUpload.class));
    }

    @Test
    void blankOrOverlongProjectNameIsRejected() {
        assertThatThrownBy(() -> service.upload("   ", new ByteArrayInputStream(new byte[0])))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getField()).isEqualTo("project"));
        assertThatThrownBy(() -> service.upload("x".repeat(101), new ByteArrayInputStream(new byte[0])))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void editingAMessageMarksItEdited() {
        Project project = new Project("demo");
        CommitUpload upload = previousUpload(project, "{}");
        when(uploads.findWithProjectById(5L)).thenReturn(Optional.of(upload));

        CommitDetail detail = service.updateMessage(5L, "  docs: reword  ");

        assertThat(detail.message()).isEqualTo("docs: reword");
        assertThat(detail.messageEdited()).isTrue();
    }

    @Test
    void unknownIdIsNotFound() {
        when(uploads.findWithProjectById(9L)).thenReturn(Optional.empty());
        when(uploads.existsById(9L)).thenReturn(false);

        assertThatThrownBy(() -> service.get(9L)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.delete(9L)).isInstanceOf(ApiException.class);
    }
}
