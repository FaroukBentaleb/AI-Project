package com.devassisthub.sql.support;

import com.devassisthub.sql.rag.ExampleRetriever;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Generation slice tests have no Qdrant or database: RAG is switched off (Story 6.4, FR-36). */
@TestConfiguration
public class RagOffTestConfig {

    @Bean
    public ExampleRetriever exampleRetriever() {
        return ExampleRetriever.disabled();
    }
}
