package com.devassisthub.sql.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.devassisthub.sql.dto.GenerateRequest;
import com.devassisthub.sql.dto.GenerateResponse;
import com.devassisthub.sql.llm.LlmClient;
import com.devassisthub.sql.llm.LlmResult;
import com.devassisthub.sql.llm.PromptTemplates;
import com.devassisthub.sql.rag.ExampleRetriever;
import com.devassisthub.sql.rag.ExampleRetriever.Example;
import com.devassisthub.sql.rag.ExampleRetriever.Retrieval;
import com.devassisthub.sql.validation.ReadOnlyValidator;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Story 6.4: generation with a mocked retriever and LLM. */
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class GenerationServiceRagTest {

    final LlmClient llm = mock(LlmClient.class);
    final ExampleRetriever retriever = mock(ExampleRetriever.class);
    final GenerationService service = new GenerationService(llm, new PromptTemplates("v1"),
            new ReadOnlyValidator(), Duration.ofSeconds(13), retriever);
    final GenerateRequest req = new GenerateRequest("weekly orders by region", null);

    GenerationServiceRagTest() {
        when(llm.timeout()).thenReturn(Duration.ofSeconds(5));
    }

    private ArgumentCaptor<String> prompts(int calls) {
        ArgumentCaptor<String> c = ArgumentCaptor.forClass(String.class);
        verify(llm, times(calls)).complete(c.capture());
        return c;
    }

    @Test
    void examplesGoIntoThePromptAndExamplesUsed() {
        when(retriever.retrieve("weekly orders by region")).thenReturn(new Retrieval(List.of(
                new Example(12, "Weekly orders by region", "orders per region", "SELECT region FROM orders"),
                new Example(4, "Other", "o", "SELECT 4")), List.of()));
        when(llm.complete(anyString())).thenReturn(new LlmResult.Completed("SELECT region FROM orders"));
        GenerateResponse r = service.generate(req);
        assertThat(r.examplesUsed()).containsExactly(new GenerateResponse.ExampleRef(12, "Weekly orders by region"),
                new GenerateResponse.ExampleRef(4, "Other"));
        assertThat(r.warnings()).isEmpty();
        assertThat(prompts(1).getValue()).contains("<example id=\"12\">").contains("SELECT region FROM orders")
                .contains("<example id=\"4\">");
    }

    @Test
    void correctiveCallReusesTheExamples() {
        when(retriever.retrieve(anyString())).thenReturn(new Retrieval(
                List.of(new Example(7, "T", "i", "SELECT 7")), List.of()));
        when(llm.complete(anyString())).thenReturn(new LlmResult.Completed("sorry, no idea"),
                new LlmResult.Completed("SELECT 7"));
        GenerateResponse r = service.generate(req);
        assertThat(r.examplesUsed()).extracting(GenerateResponse.ExampleRef::id).containsExactly(7L);
        assertThat(prompts(2).getAllValues()).allSatisfy(p -> assertThat(p).contains("<example id=\"7\">"));
        verify(retriever, times(1)).retrieve(anyString());
    }

    @Test
    void ragWarningsAreReturnedWithZeroShotPrompt() {
        for (String w : List.of(ExampleRetriever.NO_SIMILAR_WARNING, ExampleRetriever.RAG_UNAVAILABLE_WARNING)) {
            when(retriever.retrieve(anyString())).thenReturn(new Retrieval(List.of(), List.of(w)));
            when(llm.complete(anyString())).thenReturn(new LlmResult.Completed("SELECT 1"));
            GenerateResponse r = service.generate(req);
            assertThat(r.examplesUsed()).isNotNull().isEmpty();
            assertThat(r.warnings()).containsExactly(w);
        }
        assertThat(prompts(2).getAllValues()).allSatisfy(p -> assertThat(p).doesNotContain("<example id="));
    }

    @Test
    void examplesDroppedByTheTotalCapAreNotListed() {
        List<Example> big = List.of(1, 2, 3, 4, 5).stream()
                .map(i -> new Example(i, "T" + i, "i", "SELECT " + "x".repeat(3000))).toList();
        when(retriever.retrieve(anyString())).thenReturn(new Retrieval(big, List.of()));
        when(llm.complete(anyString())).thenReturn(new LlmResult.Completed("SELECT 1"));
        assertThat(service.generate(req).examplesUsed()).extracting(GenerateResponse.ExampleRef::id)
                .containsExactly(1L, 2L, 3L);
    }

    /** Story 6.5 (NFR7): one line with ids, counts and timings; prompt, example and reply text never logged. */
    @Test
    void logsOneNfr7LineWithoutText(org.springframework.boot.test.system.CapturedOutput out) {
        when(retriever.retrieve(anyString())).thenReturn(new Retrieval(
                List.of(new Example(7, "T", "secret-intent", "SELECT 7")), List.of()));
        when(llm.complete(anyString())).thenReturn(new LlmResult.Completed("sorry, no idea"),
                new LlmResult.Completed("DELETE FROM orders"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.generate(req));
        String line = out.getOut().lines().filter(l -> l.contains("generation outcome=")).reduce("", (a, b) -> b);
        assertThat(out.getOut().lines().filter(l -> l.contains("generation outcome=")).count()).isEqualTo(1);
        assertThat(line).contains("outcome=SQL_REJECTED", "prompt_version=v1", "prompt_chars=23", "examples=1",
                "example_ids=[7]", "llm_calls=2", "llm_ms=[", "rag_ms=", "total_ms=");
        assertThat(line).doesNotContain("weekly orders", "secret-intent", "SELECT 7", "DELETE", "sorry");
    }
}
