package com.devassisthub.bug.service;

import com.devassisthub.bug.dto.BugResponse;
import com.devassisthub.bug.dto.TriageRequest;
import com.devassisthub.bug.dto.TriageResponse;
import com.devassisthub.bug.dto.TriageStatus;
import com.devassisthub.bug.exception.ApiException;
import com.devassisthub.bug.llm.HfChatClient;
import com.devassisthub.bug.llm.HfChatClient.Message;
import com.devassisthub.bug.llm.PiiRedactor;
import com.devassisthub.bug.llm.PromptLibrary;
import com.devassisthub.bug.llm.TriageParser;
import com.devassisthub.bug.llm.TriageParser.InvalidOutputException;
import com.devassisthub.bug.llm.TriageParser.Suggestion;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * The LLM feature: a bug report goes in, a validated severity, category and suggested title come out. The report is
 * redacted and trimmed before it is sent; an unusable answer gets one repair round trip, then a clear error. No DB
 * transaction is open while the model is called.
 */
@Service
public class TriageService {

    static final int MAX_DESCRIPTION = 4000;
    static final int MAX_STEPS = 2000;
    static final int MAX_RESULT = 1000;
    static final int MAX_STACK_LINES = 40;
    static final int MAX_STACK_CHARS = 3000;
    static final int MAX_SHORT = 200;
    /** How much of a rejected answer is echoed back in the repair round trip. */
    static final int MAX_ECHO = 1500;

    private static final Logger log = LoggerFactory.getLogger(TriageService.class);

    private final HfChatClient client;
    private final PromptLibrary prompts;
    private final PiiRedactor redactor;
    private final TriageParser parser;
    private final BugService bugs;

    public TriageService(HfChatClient client, PromptLibrary prompts, PiiRedactor redactor, TriageParser parser,
                         BugService bugs) {
        this.client = client;
        this.prompts = prompts;
        this.redactor = redactor;
        this.parser = parser;
        this.bugs = bugs;
    }

    public TriageStatus status() {
        return new TriageStatus(client.configured(), client.model(), client.provider(), prompts.version());
    }

    /** Triage of a report that is not saved yet. */
    public TriageResponse triage(TriageRequest r) {
        return run(new TriageInput(r.title(), r.description(), r.stepsToReproduce(), r.expectedResult(),
                r.actualResult(), r.stackTrace(), r.environment(), r.appVersion()));
    }

    /** Triage of a stored bug; the suggestion is saved on it next to (not over) the user's values. */
    public BugResponse triageStored(long id) {
        TriageResponse triage = run(bugs.triageInput(id));
        return bugs.recordTriage(id, triage);
    }

    TriageResponse run(TriageInput input) {
        int[] redactions = {0};
        Map<String, String> values = new LinkedHashMap<>();
        values.put("title", field(input.title(), MAX_SHORT, redactions));
        values.put("description", field(input.description(), MAX_DESCRIPTION, redactions));
        values.put("steps", field(input.stepsToReproduce(), MAX_STEPS, redactions));
        values.put("expected", field(input.expectedResult(), MAX_RESULT, redactions));
        values.put("actual", field(input.actualResult(), MAX_RESULT, redactions));
        values.put("stack_trace", field(stackHead(input.stackTrace()), MAX_STACK_CHARS, redactions));
        values.put("environment", field(input.environment(), MAX_SHORT, redactions));
        values.put("app_version", field(input.appVersion(), MAX_SHORT, redactions));

        List<Message> messages = new ArrayList<>(List.of(new Message("system", prompts.system()),
                new Message("user", prompts.user(values))));
        String answer = client.complete(messages);
        Suggestion suggestion;
        try {
            suggestion = parser.parse(answer);
        } catch (InvalidOutputException first) {
            log.warn("Triage answer rejected ({}); asking the model to repair it", first.getMessage());
            messages.add(new Message("assistant", truncate(answer, MAX_ECHO)));
            messages.add(new Message("user", prompts.repair(Map.of("reason", first.getMessage()))));
            String retry = client.complete(messages);
            try {
                suggestion = parser.parse(retry);
            } catch (InvalidOutputException second) {
                log.warn("Repaired triage answer rejected too ({})", second.getMessage());
                throw new ApiException(HttpStatus.BAD_GATEWAY, "LLM_INVALID_OUTPUT",
                        "The model did not return a usable triage (" + second.getMessage() + "); try again");
            }
        }
        return new TriageResponse(suggestion.severity(), suggestion.category(), suggestion.title(),
                suggestion.rationale(), client.model(), prompts.version(), redactions[0]);
    }

    /** Redacted, trimmed value, or "(not provided)". */
    private String field(String value, int max, int[] redactions) {
        if (value == null || value.isBlank()) {
            return "(not provided)";
        }
        PiiRedactor.Result result = redactor.redact(value.strip());
        redactions[0] += result.count();
        return truncate(result.text(), max);
    }

    /** The top of a stack trace carries the exception and the failing frames; the rest is mostly noise. */
    static String stackHead(String trace) {
        if (trace == null) {
            return null;
        }
        String[] lines = trace.replace("\r\n", "\n").split("\n");
        if (lines.length <= MAX_STACK_LINES) {
            return trace;
        }
        return String.join("\n", List.of(lines).subList(0, MAX_STACK_LINES))
                + "\n... (" + (lines.length - MAX_STACK_LINES) + " more lines)";
    }

    static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + " ... (truncated)";
    }
}
