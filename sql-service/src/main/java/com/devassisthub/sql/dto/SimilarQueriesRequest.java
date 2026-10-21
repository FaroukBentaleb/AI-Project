package com.devassisthub.sql.dto;

/** Story 8.4 draft body; every field is optional (a partial draft still gets suggestions). */
public record SimilarQueriesRequest(String title, String description, String sqlContent) {
}
