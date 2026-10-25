package com.devassisthub.sql.rag;

/** What sql-service needs to know about its collection; {@code vectorSize} is -1 when not a single unnamed vector. */
public record CollectionInfo(String name, int vectorSize, String distance, long pointsCount) { }
