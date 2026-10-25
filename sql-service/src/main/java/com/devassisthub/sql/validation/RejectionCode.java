package com.devassisthub.sql.validation;

/** Machine-readable reasons a statement is not read-only (FR-11). */
public enum RejectionCode {
    SQL_PARSE_ERROR,
    MULTIPLE_STATEMENTS,
    NON_SELECT_STATEMENT,
    SELECT_INTO,
    DATA_MODIFYING_CTE,
    LOCKING_CLAUSE,
    BLOCKED_FUNCTION
}
