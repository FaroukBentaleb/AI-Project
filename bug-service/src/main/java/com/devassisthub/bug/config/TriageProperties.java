package com.devassisthub.bug.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Which prompt version the triage feature loads from {@code classpath:prompts/<version>/}. */
@ConfigurationProperties("bug.triage")
public record TriageProperties(String promptVersion) {
    public TriageProperties {
        promptVersion = promptVersion == null || promptVersion.isBlank() ? "v1" : promptVersion.strip();
    }
}
