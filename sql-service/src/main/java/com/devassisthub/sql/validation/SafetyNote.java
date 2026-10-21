package com.devassisthub.sql.validation;

/** PRD Safety Note: returned verbatim with every validation (and later generation) result, accepted or rejected. */
public final class SafetyNote {

    public static final String TEXT = "This check verifies only that the SQL is a single read-only SELECT structure. "
            + "It does not verify that the SQL is correct, fast, or safe on your schema. "
            + "Run generated SQL as a read-only database user.";

    private SafetyNote() {}
}
