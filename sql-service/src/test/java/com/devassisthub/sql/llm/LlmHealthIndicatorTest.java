package com.devassisthub.sql.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

/** Story 6.5: the indicator only reads config and the cached last call; it never calls the provider. */
class LlmHealthIndicatorTest {

    final LlmClient client = mock(LlmClient.class);
    final LlmHealthIndicator indicator = new LlmHealthIndicator(client);

    LlmHealthIndicatorTest() {
        when(client.model()).thenReturn("defog/sqlcoder-7b-2");
    }

    @AfterEach
    void noLiveCall() {
        verify(client, never()).complete(anyString());
    }

    @Test
    void notConfiguredIsOutOfService() {
        Health h = indicator.health();
        assertThat(h.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        assertThat(h.getDetails()).containsEntry("configured", false).containsEntry("model", "defog/sqlcoder-7b-2");
    }

    @Test
    void configuredWithoutCallIsUnknown() {
        when(client.configured()).thenReturn(true);
        assertThat(indicator.health().getStatus()).isEqualTo(Status.UNKNOWN);
    }

    @Test
    void lastCallOkIsUp() {
        when(client.configured()).thenReturn(true);
        when(client.lastCall()).thenReturn(new LlmClient.LastCall(Instant.now(), null));
        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void lastCallFailedIsDownWithCode() {
        when(client.configured()).thenReturn(true);
        when(client.lastCall()).thenReturn(new LlmClient.LastCall(Instant.now(), LlmResult.Cause.QUOTA_EXCEEDED));
        Health h = indicator.health();
        assertThat(h.getStatus()).isEqualTo(Status.DOWN);
        assertThat(h.getDetails()).containsEntry("error", "LLM_QUOTA_EXCEEDED")
                .containsEntry("last_call", "QUOTA_EXCEEDED");
    }
}
