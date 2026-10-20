package com.devassisthub.sql.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.devassisthub.sql.llm.LlmClient;
import com.devassisthub.sql.llm.LlmProperties;
import com.devassisthub.sql.llm.LlmResult;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

/**
 * Story 8.2: the real application.yml / application-planb.yml / application-demo.yml bind as the PRD §7 budgets
 * and addendum §G demand. No auto-configuration: only the config files and the property binding are exercised.
 */
class DemoProfilesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(LlmProperties.class)
    static class Props {
    }

    private static ConfigurableApplicationContext start(String... profiles) {
        SpringApplication app = new SpringApplication(Props.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setAdditionalProfiles(profiles);
        return app.run("--spring.main.banner-mode=off", "--hf.api-token=t");
    }

    private static Duration deadline(ConfigurableApplicationContext ctx) {
        return ctx.getEnvironment().getProperty("sql.generation.deadline", Duration.class);
    }

    @Test
    void defaultBudgetsAre5sLlmAnd13sDeadline() {
        try (var ctx = start()) {
            assertThat(ctx.getBean(LlmProperties.class).timeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(deadline(ctx)).isEqualTo(Duration.ofSeconds(13));
        }
    }

    @Test
    void planBRaisesLlmTimeoutTo8sAndDeadlineTo20s() {
        try (var ctx = start("planb")) {
            assertThat(ctx.getBean(LlmProperties.class).timeout()).isEqualTo(Duration.ofSeconds(8));
            assertThat(deadline(ctx)).isEqualTo(Duration.ofSeconds(20));
            // Two full LLM calls still fit inside the deadline (FR-17 corrective retry).
            assertThat(deadline(ctx)).isGreaterThan(Duration.ofSeconds(16));
        }
    }

    @Test
    void planBTogetherWithDemoKeepsPlanBBudgets() {
        try (var ctx = start("demo", "planb")) {
            assertThat(ctx.getBean(LlmProperties.class).timeout()).isEqualTo(Duration.ofSeconds(8));
            assertThat(deadline(ctx)).isEqualTo(Duration.ofSeconds(20));
        }
    }

    @Test
    void demoProfileLogsAtInfoAndDeadHostIsOffByDefault() {
        try (var ctx = start("demo")) {
            var env = ctx.getEnvironment();
            assertThat(env.getProperty("logging.level.root")).isEqualTo("INFO");
            assertThat(env.getProperty("logging.level.sql.generation")).isEqualTo("INFO");
            assertThat(env.getProperty("sql.demo.llm-dead-host", Boolean.class)).isFalse();
            assertThat(ctx.getBean(LlmProperties.class).timeout()).isEqualTo(Duration.ofSeconds(5));
        }
    }

    @Test
    void deadHostSwitchReplacesOnlyTheGenerationBaseUrl() {
        LlmProperties props = new LlmProperties("t", null, Duration.ofSeconds(1), "https://router.example", 0);
        assertThat(LlmConfig.baseUrl(props, false)).isEqualTo("https://router.example");
        assertThat(LlmConfig.baseUrl(props, true)).isEqualTo(LlmConfig.DEAD_HOST_URL);
    }

    @Test
    void deadHostGivesLlmUnavailableNotNotConfigured() {
        LlmProperties props = new LlmProperties("t", null, Duration.ofSeconds(2), "https://router.example", 0);
        LlmClient client = new LlmConfig().llmClient(props, true);
        assertThat(client.configured()).isTrue();
        LlmResult result = client.complete("[QUESTION]orders[/QUESTION]");
        assertThat(result).isInstanceOf(LlmResult.Failed.class);
        assertThat(((LlmResult.Failed) result).cause().errorCode()).isEqualTo("LLM_UNAVAILABLE");
    }
}
