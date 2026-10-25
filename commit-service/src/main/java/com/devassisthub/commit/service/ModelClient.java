package com.devassisthub.commit.service;

import com.devassisthub.commit.config.ModelProperties;
import com.devassisthub.commit.exception.ApiException;
import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Dedicated client of the FastAPI model service: {@code POST {url}/generate {"diff": "..."}} answers
 * {@code {"message": "..."}}. Strict connect/read timeouts, a bounded retry for transport failures and 5xx, and the
 * reply is validated before it is used. Never logs the diff.
 */
@Component
public class ModelClient {

    static final int MAX_MESSAGE_CHARS = 2000;

    private static final Logger log = LoggerFactory.getLogger(ModelClient.class);

    record GenerateResponse(String message) {
    }

    private final RestClient rest;
    private final int maxAttempts;

    public ModelClient(ModelProperties props) {
        // HTTP/1.1 only: the default h2c upgrade makes uvicorn drop the POST body (the model would see an empty diff).
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(props.connectTimeout())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(props.readTimeout());
        this.rest = RestClient.builder().baseUrl(props.url()).requestFactory(factory).build();
        this.maxAttempts = Math.max(1, props.retryMaxAttempts());
    }

    /** Returns a validated, trimmed commit message, or throws an {@link ApiException} describing the failure. */
    public String generate(String diff) {
        ApiException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                GenerateResponse reply = rest.post()
                        .uri("/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .body(Map.of("diff", diff))
                        .retrieve()
                        .body(GenerateResponse.class);
                return validate(reply);
            } catch (HttpStatusCodeException e) {
                if (e.getStatusCode().is5xxServerError()) {
                    last = unavailable("The model service answered " + e.getStatusCode().value());
                } else {
                    // A 4xx means our request was rejected; repeating it cannot help.
                    throw unavailable("The model service rejected the request (" + e.getStatusCode().value() + ")");
                }
            } catch (ResourceAccessException e) {
                last = isTimeout(e)
                        ? new ApiException(HttpStatus.GATEWAY_TIMEOUT, "MODEL_TIMEOUT",
                                "The model service did not answer in time")
                        : unavailable("The model service is unreachable");
            } catch (ApiException e) {
                throw e;
            } catch (RuntimeException e) {
                throw invalid("The model service reply could not be read");
            }
            log.warn("Model call failed (attempt {}/{}): {}", attempt, maxAttempts, last.getError());
        }
        throw last;
    }

    private static String validate(GenerateResponse reply) {
        String message = reply == null || reply.message() == null ? "" : reply.message().replace("\r\n", "\n").strip();
        if (message.isEmpty()) {
            throw invalid("The model returned an empty commit message");
        }
        if (message.length() > MAX_MESSAGE_CHARS) {
            throw invalid("The model returned a commit message that is too long");
        }
        return message;
    }

    private static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof HttpTimeoutException || t instanceof SocketTimeoutException
                    || t instanceof TimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static ApiException unavailable(String message) {
        return new ApiException(HttpStatus.BAD_GATEWAY, "MODEL_UNAVAILABLE", message);
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_GATEWAY, "MODEL_INVALID_RESPONSE", message);
    }
}
