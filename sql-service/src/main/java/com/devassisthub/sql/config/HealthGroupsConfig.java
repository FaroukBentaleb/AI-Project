package com.devassisthub.sql.config;

import java.util.Set;
import org.springframework.boot.actuate.endpoint.SecurityContext;
import org.springframework.boot.actuate.health.AdditionalHealthEndpointPath;
import org.springframework.boot.actuate.health.HealthEndpointGroup;
import org.springframework.boot.actuate.health.HealthEndpointGroups;
import org.springframework.boot.actuate.health.HealthEndpointGroupsPostProcessor;
import org.springframework.boot.actuate.health.HttpCodeStatusMapper;
import org.springframework.boot.actuate.health.StatusAggregator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Keeps external dependencies (members of the {@code dependencies} group: qdrant, later llm) out of the default
 * {@code /actuator/health}, so a Qdrant outage degrades RAG without marking the service DOWN (PRD §7).
 */
@Configuration
public class HealthGroupsConfig {

    static final String DEPENDENCIES = "dependencies";

    @Bean
    public static HealthEndpointGroupsPostProcessor excludeDependenciesFromRoot() {
        return HealthGroupsConfig::excludeDependencies;
    }

    static HealthEndpointGroups excludeDependencies(HealthEndpointGroups groups) {
        HealthEndpointGroup deps = groups.get(DEPENDENCIES);
        if (deps == null) {
            return groups;
        }
        HealthEndpointGroup primary = new ExcludingGroup(groups.getPrimary(), deps);
        return new HealthEndpointGroups() {
            @Override public HealthEndpointGroup getPrimary() { return primary; }
            @Override public Set<String> getNames() { return groups.getNames(); }
            @Override public HealthEndpointGroup get(String name) { return groups.get(name); }
        };
    }

    private record ExcludingGroup(HealthEndpointGroup delegate, HealthEndpointGroup excluded)
            implements HealthEndpointGroup {
        @Override public boolean isMember(String name) {
            return delegate.isMember(name) && !excluded.isMember(name);
        }
        @Override public boolean showComponents(SecurityContext ctx) { return delegate.showComponents(ctx); }
        @Override public boolean showDetails(SecurityContext ctx) { return delegate.showDetails(ctx); }
        @Override public StatusAggregator getStatusAggregator() { return delegate.getStatusAggregator(); }
        @Override public HttpCodeStatusMapper getHttpCodeStatusMapper() { return delegate.getHttpCodeStatusMapper(); }
        @Override public AdditionalHealthEndpointPath getAdditionalPath() { return delegate.getAdditionalPath(); }
    }
}
