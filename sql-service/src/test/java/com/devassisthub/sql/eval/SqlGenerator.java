package com.devassisthub.sql.eval;

/** Story 6.6: source of generated SQL for one prompt, with RAG on or off. */
@FunctionalInterface
public interface SqlGenerator {

    /** Either {@code sql} is set, or {@code error} holds the envelope code (or a transport failure). */
    record Generation(String sql, String error) {
        public static Generation ok(String sql) {
            return new Generation(sql, null);
        }

        public static Generation failed(String error) {
            return new Generation(null, error);
        }
    }

    Generation generate(String prompt, boolean rag);
}
