package com.devassisthub.bug.dto;

import com.devassisthub.bug.entity.BugCategory;
import com.devassisthub.bug.entity.Severity;

/**
 * A validated triage suggestion. {@code redactions} is how many pieces of private data (emails, tokens, IPs...) were
 * masked before the report was sent to the model.
 */
public record TriageResponse(Severity severity, BugCategory category, String suggestedTitle, String rationale,
                             String model, String promptVersion, int redactions) {
}
