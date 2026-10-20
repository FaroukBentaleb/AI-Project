package com.devassisthub.sql.service;

import com.devassisthub.sql.dto.GenerateRequest;
import com.devassisthub.sql.dto.GenerateResponse;
import com.devassisthub.sql.dto.GenerationErrorDetails;
import com.devassisthub.sql.exception.ApiException;
import com.devassisthub.sql.exception.LlmFailureException;
import com.devassisthub.sql.llm.LlmClient;
import com.devassisthub.sql.llm.LlmResult;
import com.devassisthub.sql.llm.PromptTemplates;
import com.devassisthub.sql.llm.RefusalDetector;
import com.devassisthub.sql.llm.SqlExtractor;
import com.devassisthub.sql.rag.ExampleRetriever;
import com.devassisthub.sql.validation.ReadOnlyValidator;
import com.devassisthub.sql.validation.RejectionCode;
import com.devassisthub.sql.validation.SafetyNote;
import com.devassisthub.sql.validation.ValidationResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Stories 6.1-6.4: retrieves team Examples (RAG, degrading to zero-shot), renders the versioned prompt, calls the LLM, extracts and validates the SQL. An empty,
 * unparseable or refusal reply gets one corrective call (retry template), at most two calls in total, and only if
 * the remaining {@code sql.generation.deadline} can fit a full {@code hf.timeout}. Transport failures and
 * validator-rejected SQL are never retried. Never saves and never executes anything.
 */
@Service
public class GenerationService {

    public static final String SQL_REJECTED = "SQL_REJECTED";
    public static final String SQL_PARSE_ERROR = "SQL_PARSE_ERROR";
    public static final String LLM_REFUSED = "LLM_REFUSED";
    public static final String REFUSED_MESSAGE = "The model declined to generate SQL for this request";
    public static final String PARSE_ERROR_MESSAGE = "The model reply did not contain SQL that could be parsed";
    static final int RAW_RESPONSE_LIMIT = 4000;

    private static final Logger log = LoggerFactory.getLogger(GenerationService.class);
    /** Story 6.5 (NFR7): one line per generation on this named logger; never prompt or example text (NFR5). */
    public static final String GENERATION_LOGGER = "sql.generation";
    private static final Logger genLog = LoggerFactory.getLogger(GENERATION_LOGGER);

    private final LlmClient llm;
    private final PromptTemplates templates;
    private final ReadOnlyValidator validator;
    private final Duration deadline;
    private final ExampleRetriever retriever;

    public GenerationService(LlmClient llm, PromptTemplates templates, ReadOnlyValidator validator,
                             @Value("${sql.generation.deadline:13s}") Duration deadline,
                             ExampleRetriever retriever) {
        this.retriever = retriever;
        this.llm = llm;
        this.templates = templates;
        this.validator = validator;
        this.deadline = deadline;
    }

    public GenerateResponse generate(GenerateRequest request) {
        Trace trace = new Trace(System.nanoTime());
        try {
            GenerateResponse r = run(request, trace);
            trace.outcome = "OK";
            trace.warnings = r.warnings().size();
            return r;
        } catch (ApiException e) {
            trace.outcome = e.getError();
            throw e;
        } catch (RuntimeException e) {
            trace.outcome = "INTERNAL_ERROR";
            throw e;
        } finally {
            genLog.info("generation outcome={} prompt_version={} prompt_chars={} examples={} example_ids={} "
                            + "rag_warnings={} warnings={} rag_ms={} llm_calls={} llm_ms={} total_ms={}",
                    trace.outcome, templates.version(), request.prompt() == null ? 0 : request.prompt().length(),
                    trace.exampleIds.size(), trace.exampleIds, trace.ragWarnings, trace.warnings, trace.ragMs,
                    trace.llmMs.size(), trace.llmMs, (System.nanoTime() - trace.start) / 1_000_000);
        }
    }

    private GenerateResponse run(GenerateRequest request, Trace trace) {
        long start = trace.start;
        String dialect = request.dialectOrDefault();
        // Story 6.4: retrieval runs inside the deadline; it never throws (failures become a Warning).
        long ragStart = System.nanoTime();
        ExampleRetriever.Retrieval rag = retriever.retrieve(request.prompt());
        trace.ragMs = (System.nanoTime() - ragStart) / 1_000_000;
        List<ExampleRetriever.Example> examples = PromptTemplates.fitting(rag.examples());
        trace.ragWarnings = rag.warnings().size();
        examples.forEach(e -> trace.exampleIds.add(e.id()));
        Outcome first = attempt(templates.renderGenerate(request.prompt(), dialect, examples), trace);
        if (first.response() != null) {
            return respond(request, dialect, first.response(), examples, rag.warnings());
        }
        Duration remaining = deadline.minusNanos(System.nanoTime() - start);
        if (remaining.compareTo(llm.timeout()) < 0) {
            // FR-17 deadline guard: fail as if the second reply were malformed too.
            log.info("Corrective LLM call skipped: {} ms left, below hf.timeout", remaining.toMillis());
            throw first.error();
        }
        Outcome second = attempt(templates.renderRetry(request.prompt(), dialect, first.raw(), examples), trace);
        if (second.response() != null) {
            return respond(request, dialect, second.response(), examples, rag.warnings());
        }
        throw second.error();
    }

    /** Mutable per-request facts for the NFR7 line: sizes, ids, timings and outcome only. */
    private static final class Trace {
        final long start;
        final List<Long> exampleIds = new ArrayList<>();
        final List<Long> llmMs = new ArrayList<>();
        long ragMs;
        int ragWarnings;
        int warnings;
        String outcome = "INTERNAL_ERROR";

        Trace(long start) {
            this.start = start;
        }
    }

    /**
     * One LLM call. Returns validated SQL, or a retryable error (empty, unparseable, refusal). Transport failures and
     * validator rejections are thrown at once: they are final.
     */
    private Outcome attempt(String prompt, Trace trace) {
        long t0 = System.nanoTime();
        LlmResult result;
        try {
            result = llm.complete(prompt);
        } finally {
            trace.llmMs.add((System.nanoTime() - t0) / 1_000_000);
        }
        if (result instanceof LlmResult.Failed failed) {
            throw LlmFailureException.of(failed);
        }
        String raw = ((LlmResult.Completed) result).text();
        if (RefusalDetector.isRefusal(raw)) {
            return Outcome.retryable(raw, new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, LLM_REFUSED,
                    REFUSED_MESSAGE, null, new GenerationErrorDetails(null, List.of(), truncate(raw), null, null)));
        }
        Optional<String> sql = SqlExtractor.extract(raw);
        if (sql.isEmpty()) {
            return Outcome.retryable(raw, parseError(null, raw));
        }
        ValidationResult v = validator.validate(sql.get());
        if (!v.accepted()) {
            boolean parseOnly = v.rejections().stream().allMatch(r -> r.code() == RejectionCode.SQL_PARSE_ERROR);
            if (parseOnly) {
                return Outcome.retryable(raw, parseError(v.normalizedSql(), raw));
            }
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, SQL_REJECTED,
                    "The generated SQL was rejected by the read-only validator", null,
                    new GenerationErrorDetails(v.normalizedSql(), v.rejections(), truncate(raw), null, SafetyNote.TEXT));
        }
        return new Outcome(v, raw, null);
    }

    private GenerateResponse respond(GenerateRequest request, String dialect, ValidationResult v,
                                     List<ExampleRetriever.Example> examples, List<String> ragWarnings) {
        List<GenerateResponse.ExampleRef> used = examples.stream()
                .map(e -> new GenerateResponse.ExampleRef(e.id(), e.title())).toList();
        List<String> warnings = new ArrayList<>(ragWarnings);
        warnings.addAll(v.warnings());
        return new GenerateResponse(v.normalizedSql(), dialect, GenerateResponse.SOURCE_LLM, request.prompt(),
                used, List.copyOf(warnings), SafetyNote.TEXT, true, templates.version());
    }

    private static ApiException parseError(String sql, String raw) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, SQL_PARSE_ERROR, PARSE_ERROR_MESSAGE, null,
                new GenerationErrorDetails(sql, List.of(), truncate(raw), null, null));
    }

    private static String truncate(String raw) {
        return raw == null || raw.length() <= RAW_RESPONSE_LIMIT ? raw : raw.substring(0, RAW_RESPONSE_LIMIT);
    }

    private record Outcome(ValidationResult response, String raw, ApiException error) {
        static Outcome retryable(String raw, ApiException error) {
            return new Outcome(null, raw, error);
        }
    }
}
