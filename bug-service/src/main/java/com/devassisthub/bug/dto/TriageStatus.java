package com.devassisthub.bug.dto;

/** Whether the triage feature can call a model, so the UI can explain why it is off. Never contains the key. */
public record TriageStatus(boolean configured, String model, String provider, String promptVersion) {
}
