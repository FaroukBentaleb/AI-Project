package com.devassisthub.sql.llm;

import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;

/**
 * Story 6.5: {@code llm} contributor for the {@code dependencies} health group. Reads configuration and the cached
 * outcome of the last real generation call; never calls the provider, so polling costs no quota.
 * No token: OUT_OF_SERVICE. Token, no call yet: UNKNOWN. Last call answered: UP. Last call failed: DOWN.
 */
public class LlmHealthIndicator extends AbstractHealthIndicator {

    private final LlmClient client;

    public LlmHealthIndicator(LlmClient client) {
        super("LLM health check failed");
        this.client = client;
    }

    @Override
    protected void doHealthCheck(Health.Builder b) {
        b.withDetail("model", client.model()).withDetail("configured", client.configured());
        if (!client.configured()) {
            b.outOfService().withDetail("reason", "HF_API_TOKEN is not set");
            return;
        }
        LlmClient.LastCall last = client.lastCall();
        if (last == null) {
            b.unknown().withDetail("last_call", "none since startup");
            return;
        }
        b.withDetail("last_call_at", last.at().toString());
        if (last.ok()) {
            b.up().withDetail("last_call", "ok");
        } else {
            b.down().withDetail("last_call", last.cause().name())
                    .withDetail("error", last.cause().errorCode());
        }
    }
}
