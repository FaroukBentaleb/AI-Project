package com.devassisthub.sql.seed;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Loads and sanity-checks the Seed Corpus bundled at {@code classpath:seed/seed-queries.json}. */
public final class SeedCorpus {

    public static final String QUERIES_RESOURCE = "seed/seed-queries.json";
    public static final String SCHEMA_RESOURCE = "seed/schema.sql";
    /** Author of every seed query (FR-26). */
    public static final String AUTHOR = "seed";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private SeedCorpus() {
    }

    public static List<SeedEntry> load() {
        try (InputStream in = SeedCorpus.class.getClassLoader().getResourceAsStream(QUERIES_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(QUERIES_RESOURCE + " not found on the classpath");
            }
            List<SeedEntry> entries = List.of(MAPPER.readValue(in, SeedEntry[].class));
            check(entries);
            return entries;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + QUERIES_RESOURCE, e);
        }
    }

    /** Complete title/description/tags and unique keys (FR-26); a broken corpus is a build-time bug. */
    static void check(List<SeedEntry> entries) {
        Set<String> keys = new HashSet<>();
        for (SeedEntry e : entries) {
            if (blank(e.seedKey()) || e.seedKey().length() > 100 || !keys.add(e.seedKey())) {
                throw new IllegalStateException("Seed entry has a missing, too long or duplicate seed_key: " + e.seedKey());
            }
            if (blank(e.title()) || blank(e.description()) || blank(e.sqlContent())
                    || e.tags() == null || e.tags().isEmpty() || e.tags().stream().anyMatch(SeedCorpus::blank)) {
                throw new IllegalStateException("Seed entry " + e.seedKey() + " is incomplete");
            }
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
