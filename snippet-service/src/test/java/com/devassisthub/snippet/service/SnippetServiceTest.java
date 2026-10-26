package com.devassisthub.snippet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.devassisthub.snippet.config.ModelProperties;
import com.devassisthub.snippet.dto.SnippetDetail;
import com.devassisthub.snippet.dto.SnippetRequest;
import com.devassisthub.snippet.entity.Snippet;
import com.devassisthub.snippet.exception.ApiException;
import com.devassisthub.snippet.repository.SnippetRepository;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class SnippetServiceTest {

    private final SnippetRepository repository = mock(SnippetRepository.class);
    private final ModelClient model = mock(ModelClient.class);
    private SnippetService service;

    @BeforeEach
    void setUp() {
        ModelProperties props = new ModelProperties("http://x", Duration.ofSeconds(1), Duration.ofSeconds(1), 1, 100);
        service = new SnippetService(repository, model, new CodeTrimmer(props));
        when(repository.save(any(Snippet.class))).thenAnswer(i -> i.getArgument(0));
    }

    private static SnippetRequest request(String docstring, Boolean generate) {
        return new SnippetRequest("  add  ", "python", "def add(a, b):\n    return a + b", docstring, generate);
    }

    @Test
    void createWithoutGenerateSavesNoDocstringAndNeverCallsTheModel() {
        SnippetDetail saved = service.create(request(null, null));

        assertThat(saved.title()).isEqualTo("add");
        assertThat(saved.docstring()).isNull();
        verify(model, never()).generate(anyString(), anyString());
    }

    @Test
    void createWithGenerateStoresTheModelDocstring() {
        when(model.generate("def add(a, b):\n    return a + b", "python")).thenReturn("Add two numbers.");

        SnippetDetail saved = service.create(request(null, true));

        assertThat(saved.docstring()).isEqualTo("Add two numbers.");
        assertThat(saved.docstringEdited()).isFalse();
        assertThat(saved.truncated()).isFalse();
    }

    @Test
    void aGivenDocstringWinsOverGenerate() {
        SnippetDetail saved = service.create(request("  Mine.  ", true));

        assertThat(saved.docstring()).isEqualTo("Mine.");
        assertThat(saved.docstringEdited()).isTrue();
        verify(model, never()).generate(anyString(), anyString());
    }

    @Test
    void longCodeIsCutBeforeItIsSentAndFlagged() {
        String code = "def f():\n" + "    x = 1\n".repeat(50);
        when(model.generate(anyString(), anyString())).thenReturn("Does f.");

        SnippetDetail saved = service.create(new SnippetRequest("f", "python", code, null, true));

        org.mockito.ArgumentCaptor<String> sent = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(model).generate(sent.capture(), anyString());
        assertThat(sent.getValue()).hasSizeLessThanOrEqualTo(100).endsWith(CodeTrimmer.CUT_MARKER);
        assertThat(saved.truncated()).isTrue();
        assertThat(saved.code()).isEqualTo(code);
    }

    @Test
    void aModelFailureOnCreateSavesNothing() {
        when(model.generate(anyString(), anyString())).thenThrow(
                new ApiException(HttpStatus.BAD_GATEWAY, "MODEL_UNAVAILABLE", "down"));

        assertThatThrownBy(() -> service.create(request(null, true))).isInstanceOf(ApiException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void generateDocstringReplacesTheExistingOne() {
        Snippet existing = new Snippet("add", "python", "def add(a, b): return a + b");
        existing.setManualDocstring("old, hand written");
        when(repository.findById(7L)).thenReturn(Optional.of(existing));
        when(model.generate(anyString(), anyString())).thenReturn("Add two numbers.");

        SnippetDetail result = service.generateDocstring(7);

        assertThat(result.docstring()).isEqualTo("Add two numbers.");
        assertThat(result.docstringEdited()).isFalse();
    }

    @Test
    void updateMarksOnlyAChangedDocstringAsEdited() {
        Snippet existing = new Snippet("add", "python", "def add(a, b): return a + b");
        existing.setGeneratedDocstring("Add two numbers.", false);
        when(repository.findById(1L)).thenReturn(Optional.of(existing));

        SnippetDetail same = service.update(1, new SnippetRequest("add 2", "python", "def add(a, b): pass",
                "Add two numbers.", null));
        assertThat(same.title()).isEqualTo("add 2");
        assertThat(same.docstringEdited()).isFalse();

        SnippetDetail changed = service.update(1, new SnippetRequest("add 2", "python", "def add(a, b): pass",
                "Sum a and b.", null));
        assertThat(changed.docstringEdited()).isTrue();

        SnippetDetail cleared = service.update(1, new SnippetRequest("add 2", "python", "def add(a, b): pass",
                "  ", null));
        assertThat(cleared.docstring()).isNull();
        assertThat(cleared.docstringEdited()).isFalse();
    }

    @Test
    void missingSnippetsAreNotFound() {
        when(repository.findById(9L)).thenReturn(Optional.empty());
        when(repository.existsById(9L)).thenReturn(false);

        assertThatThrownBy(() -> service.get(9)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.generateDocstring(9)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.delete(9)).isInstanceOf(ApiException.class);
        verify(repository, never()).deleteById(any());
    }

    @Test
    void listPassesEmptyStringsForMissingFilters() {
        service.list(null, null);
        verify(repository).search("", "");
        service.list(" java ", " parse ");
        verify(repository).search("java", "parse");
    }
}
