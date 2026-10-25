package com.devassisthub.sql.config;

import com.devassisthub.sql.validation.FunctionPolicy;
import com.devassisthub.sql.validation.ReadOnlyValidator;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes the pure {@link ReadOnlyValidator} as a bean. {@code sql.validator.blocked-functions} (comma-separated)
 * replaces the FR-10 default list when set and non-empty; absent or blank keeps the defaults (fail closed).
 */
@Configuration
public class ValidatorConfig {

    @Bean
    public FunctionPolicy functionPolicy(@Value("${sql.validator.blocked-functions:}") String blocked) {
        // Split here rather than relying on a ConversionService, so binding is identical in every context.
        List<String> entries = blocked == null ? List.of()
                : Arrays.stream(blocked.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        return entries.isEmpty() ? FunctionPolicy.defaults() : new FunctionPolicy(entries);
    }

    @Bean
    public ReadOnlyValidator readOnlyValidator(FunctionPolicy functionPolicy) {
        return new ReadOnlyValidator(functionPolicy);
    }
}
