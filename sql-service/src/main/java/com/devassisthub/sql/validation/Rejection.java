package com.devassisthub.sql.validation;

/** One Rejection Code with a human-readable reason. */
public record Rejection(RejectionCode code, String reason) {}
