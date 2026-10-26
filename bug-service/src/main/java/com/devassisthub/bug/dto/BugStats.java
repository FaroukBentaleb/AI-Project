package com.devassisthub.bug.dto;

import java.util.Map;

/**
 * Dashboard counters. Every enum value is present (0 when unused); {@code openBySeverity} counts only OPEN and
 * IN_PROGRESS bugs.
 */
public record BugStats(long total, long open, Map<String, Long> byStatus, Map<String, Long> openBySeverity,
                       Map<String, Long> byCategory) {
}
