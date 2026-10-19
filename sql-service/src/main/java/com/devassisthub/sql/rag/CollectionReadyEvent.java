package com.devassisthub.sql.rag;

/** Published once when {@code sql_embeddings} first becomes ready (startup or delayed bootstrap); triggers 5.4. */
public record CollectionReadyEvent(String collection) { }
