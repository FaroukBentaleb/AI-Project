package com.devassisthub.review.service;

import com.devassisthub.review.exception.ReviewServiceException;
import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class ReviewAiClient {

    private final RestClient restClient;

    public ReviewAiClient(
            @Value("${review.ai.url}") String url,
            @Value("${review.ai.connect-timeout}") Duration connectTimeout,
            @Value("${review.ai.read-timeout}") Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        this.restClient = RestClient.builder()
                .baseUrl(url)
                .requestFactory(requestFactory)
                .build();
    }

    public String review(String codeSnippet, String language) {
        try {
            ReviewResponse response = restClient.post()
                    .uri("/review")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(Map.of("code_snippet", codeSnippet, "language", language))
                    .retrieve()
                    .body(ReviewResponse.class);

            String aiResponse = response == null || response.response() == null
                    ? ""
                    : response.response().strip();
            if (aiResponse.isEmpty()) {
                throw new ReviewServiceException(HttpStatus.BAD_GATEWAY,
                        "The review AI service returned an empty response");
            }
            return aiResponse;
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

    private record ReviewResponse(String response) {
    }
}
