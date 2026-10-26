package com.devassisthub.bug.entity;

/** Workflow state of a bug. */
public enum BugStatus {
    OPEN, IN_PROGRESS, RESOLVED, CLOSED;

    /** A resolved or closed bug carries a resolution date. */
    public boolean isDone() {
        return this == RESOLVED || this == CLOSED;
    }
}
