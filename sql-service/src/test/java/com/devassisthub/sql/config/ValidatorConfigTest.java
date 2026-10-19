package com.devassisthub.sql.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.devassisthub.sql.validation.ReadOnlyValidator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Binding of {@code sql.validator.blocked-functions}: set replaces the defaults, absent or blank keeps them. */
class ValidatorConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ValidatorConfig.class);

    @Test
    void absentPropertyKeepsDefaults() {
        runner.run(ctx -> {
            ReadOnlyValidator v = ctx.getBean(ReadOnlyValidator.class);
            assertThat(v.validate("SELECT pg_read_file('/etc/passwd')").accepted()).isFalse();
        });
    }

    @Test
    void blankPropertyKeepsDefaults() {
        runner.withPropertyValues("sql.validator.blocked-functions= ").run(ctx ->
                assertThat(ctx.getBean(ReadOnlyValidator.class).validate("SELECT dblink('a','b')").accepted())
                        .isFalse());
    }

    @Test
    void configuredListReplacesDefaults() {
        runner.withPropertyValues("sql.validator.blocked-functions=evil_fn, risky_*").run(ctx -> {
            ReadOnlyValidator v = ctx.getBean(ReadOnlyValidator.class);
            assertThat(v.validate("SELECT evil_fn(1)").accepted()).isFalse();
            assertThat(v.validate("SELECT risky_thing(1)").accepted()).isFalse();
            assertThat(v.validate("SELECT pg_read_file('/etc/passwd')").accepted()).isTrue();
        });
    }
}
