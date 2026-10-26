package com.devassisthub.review.service;

import com.devassisthub.review.exception.ReviewServiceException;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class ReviewAiClient {

    public record ReviewResult(String response, String relevantSnippet, double relevanceScore) {
    }

    private final RestClient restClient;

    public ReviewAiClient(
            @NonNull @Value("${review.ai.url}") String url,
            @NonNull @Value("${review.ai.connect-timeout}") Duration connectTimeout,
            @NonNull @Value("${review.ai.read-timeout}") Duration readTimeout) {
        HttpClient httpClient = Objects.requireNonNull(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout)
                .build());
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                Objects.requireNonNull(httpClient));
        requestFactory.setReadTimeout(Objects.requireNonNull(readTimeout));
        this.restClient = RestClient.builder()
                .baseUrl(Objects.requireNonNull(url))
                .requestFactory(requestFactory)
                .build();
    }

    public ReviewResult review(String codeSnippet, String language) {
        try {
            MediaType json = Objects.requireNonNull(MediaType.APPLICATION_JSON);
            Map<String, String> requestBody = Objects.requireNonNull(Map.of(
                    "code_snippet", codeSnippet,
                    "language", language));
            ReviewResponse response = restClient.post()
                    .uri("/review")
                    .contentType(json)
                    .accept(json)
                    .body(requestBody)
                    .retrieve()
                    .body(ReviewResponse.class);

            if (response == null) {
                throw new ReviewServiceException(HttpStatus.BAD_GATEWAY,
                        "The review AI service returned an invalid response");
            }
            String aiResponse = response.response() == null ? "" : response.response().strip();
            String relevantSnippet = response.relevantSnippet() == null ? "" : response.relevantSnippet().strip();
            if (aiResponse.isEmpty() || relevantSnippet.isEmpty()
                    || response.relevanceScore() < 0 || response.relevanceScore() > 1) {
                throw new ReviewServiceException(HttpStatus.BAD_GATEWAY,
                        "The review AI service returned an invalid response");
            }
            return new ReviewResult(aiResponse, relevantSnippet, response.relevanceScore());
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            if (status == 429) {
                throw new ReviewServiceException(HttpStatus.BAD_GATEWAY,
                        "Groq quota or rate limit exceeded");
            }
            if (status == 503) {
                throw new ReviewServiceException(HttpStatus.SERVICE_UNAVAILABLE,
                        "The review AI service is unavailable; check that GROQ_API_KEY is configured");
            }
            throw new ReviewServiceException(HttpStatus.BAD_GATEWAY,
                    "The review AI service could not complete the request (HTTP " + status + ")");
        } catch (ResourceAccessException exception) {
            if (isTimeout(exception)) {
                throw new ReviewServiceException(HttpStatus.GATEWAY_TIMEOUT,
                        "The review AI service did not respond in time");
            }
            throw new ReviewServiceException(HttpStatus.BAD_GATEWAY,
                    "The review AI service is unreachable");
        } catch (RestClientException exception) {
            throw new ReviewServiceException(HttpStatus.BAD_GATEWAY,
                    "The review AI service returned an invalid response");
        }
    }

    private static boolean isTimeout(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException
                    || cause instanceof SocketTimeoutException
                    || cause instanceof TimeoutException) {
                return true;
            }
        }
        return false;
    }

    private record ReviewResponse(
            String response,
            @JsonProperty("relevant_snippet") String relevantSnippet,
            @JsonProperty("relevance_score") double relevanceScore) {
    }
}
