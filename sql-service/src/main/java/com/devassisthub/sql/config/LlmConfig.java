package com.devassisthub.sql.config;

import com.devassisthub.sql.llm.LlmClient;
import com.devassisthub.sql.llm.LlmHealthIndicator;
import com.devassisthub.sql.llm.LlmProperties;
import com.devassisthub.sql.llm.PromptTemplates;
import java.net.http.HttpClient;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Story 6.1: HF generation client ({@code hf.timeout} connect/read, one attempt) and versioned prompts. */
@Configuration
@EnableConfigurationProperties({LlmProperties.class, GroqProperties.class})
public class LlmConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmConfig.class);

    /** Story 8.2: discard port on loopback; the connection is refused at once (LLM_UNAVAILABLE, no wait). */
    public static final String DEAD_HOST_URL = "http://127.0.0.1:9";

    @Bean
    public LlmClient llmClient(LlmProperties props,
                               GroqProperties groqProps,
                               @Value("${sql.demo.llm-dead-host:false}") boolean deadHost) {
        // Generation goes to Groq's chat-completions endpoint; the dead-host switch still overrides it.
        String baseUrl = deadHost ? baseUrl(props, true) : groqProps.getBaseUrl();
        Duration timeout = groqProps.getTimeout() != null ? groqProps.getTimeout() : props.timeout();
        HttpClient http = HttpClient.newBuilder().connectTimeout(timeout).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(timeout);
        RestClient rest = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        return new LlmClient(rest, props, groqProps);
    }

    /** Story 8.2 (addendum G 5.5): the LLM-down switch overrides {@code hf.base-url}, generation only. */
    static String baseUrl(LlmProperties props, boolean deadHost) {
        if (deadHost) {
            log.warn("sql.demo.llm-dead-host=true: generation calls go to {} (LLM-down demo scenario)", DEAD_HOST_URL);
            return DEAD_HOST_URL;
        }
        return props.baseUrl();
    }

    /** Story 6.5: bean name {@code llmHealthIndicator} gives the contributor name {@code llm}. */
    @Bean
    public LlmHealthIndicator llmHealthIndicator(LlmClient llmClient) {
        return new LlmHealthIndicator(llmClient);
    }

    @Bean
    public PromptTemplates promptTemplates(@Value("${sql.prompt.version:v1}") String version) {
        return new PromptTemplates(version);
    }
}
