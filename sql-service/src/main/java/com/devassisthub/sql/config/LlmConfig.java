package com.devassisthub.sql.config;

import com.devassisthub.sql.llm.LlmClient;
import com.devassisthub.sql.llm.LlmProperties;
import com.devassisthub.sql.llm.PromptTemplates;
import java.net.http.HttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Story 6.1: HF generation client ({@code hf.timeout} connect/read, one attempt) and versioned prompts. */
@Configuration
@EnableConfigurationProperties(LlmProperties.class)
public class LlmConfig {

    @Bean
    public LlmClient llmClient(LlmProperties props) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(props.timeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(props.timeout());
        RestClient rest = RestClient.builder().baseUrl(props.baseUrl()).requestFactory(factory).build();
        return new LlmClient(rest, props);
    }

    @Bean
    public PromptTemplates promptTemplates(@Value("${sql.prompt.version:v1}") String version) {
        return new PromptTemplates(version);
    }
}
