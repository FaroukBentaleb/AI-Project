package com.devassisthub.sql.service;

import com.devassisthub.sql.dto.GenerateRequest;
import com.devassisthub.sql.dto.GenerateResponse;
import com.devassisthub.sql.dto.GenerationErrorDetails;
import com.devassisthub.sql.exception.ApiException;
import com.devassisthub.sql.llm.LlmClient;
import com.devassisthub.sql.llm.LlmResult;
import com.devassisthub.sql.llm.PromptTemplates;
import com.devassisthub.sql.llm.SqlExtractor;
import com.devassisthub.sql.validation.ReadOnlyValidator;
import com.devassisthub.sql.validation.RejectionCode;
import com.devassisthub.sql.validation.SafetyNote;
import com.devassisthub.sql.validation.ValidationResult;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Story 6.1: zero-shot generation. Renders the versioned prompt, makes one LLM call, extracts and validates the SQL.
 * Never saves and never executes anything; no retries (6.3) and no RAG (6.4) yet.
 */
@Service
public class GenerationService {

    public static final String SQL_REJECTED = "SQL_REJECTED";
    public static final String SQL_PARSE_ERROR = "SQL_PARSE_ERROR";
    static final int RAW_RESPONSE_LIMIT = 4000;
    static final String UNAVAILABLE_MESSAGE =
            "SQL generation is unavailable right now. You can still write the SQL and save it manually.";

    private final LlmClient llm;
    private final PromptTemplates templates;
    private final ReadOnlyValidator validator;

    public GenerationService(LlmClient llm, PromptTemplates templates, ReadOnlyValidator validator) {
        this.llm = llm;
        this.templates = templates;
        this.validator = validator;
    }

    public GenerateResponse generate(GenerateRequest request) {
        String dialect = request.dialectOrDefault();
        LlmResult result = llm.complete(templates.renderGenerate(request.prompt(), dialect));
        if (result instanceof LlmResult.Failed failed) {
            // Coarse FR-19 mapping; specific messages and Retry-After arrive with Story 6.2.
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, failed.cause().errorCode(), UNAVAILABLE_MESSAGE);
        }
        String raw = ((LlmResult.Completed) result).text();
        Optional<String> sql = SqlExtractor.extract(raw);
        if (sql.isEmpty()) {
            throw parseError(null, raw);
        }
        ValidationResult v = validator.validate(sql.get());
        if (!v.accepted()) {
            boolean parseOnly = v.rejections().stream().allMatch(r -> r.code() == RejectionCode.SQL_PARSE_ERROR);
            if (parseOnly) {
                throw parseError(v.normalizedSql(), raw);
            }
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, SQL_REJECTED,
                    "The generated SQL was rejected by the read-only validator", null,
                    new GenerationErrorDetails(v.normalizedSql(), v.rejections(), truncate(raw), null));
        }
        return new GenerateResponse(v.normalizedSql(), dialect, GenerateResponse.SOURCE_LLM, request.prompt(),
                List.of(), v.warnings(), SafetyNote.TEXT, true, templates.version());
    }

    private static ApiException parseError(String sql, String raw) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, SQL_PARSE_ERROR,
                "The model reply did not contain SQL that could be parsed", null,
                new GenerationErrorDetails(sql, List.of(), truncate(raw), null));
    }

    private static String truncate(String raw) {
        return raw == null || raw.length() <= RAW_RESPONSE_LIMIT ? raw : raw.substring(0, RAW_RESPONSE_LIMIT);
    }
}
