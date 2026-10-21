package com.devassisthub.sql.seed;

import java.util.List;

/** One Seed Corpus entry from {@code seed/seed-queries.json} (addendum §F). */
public record SeedEntry(String seedKey, String title, String description, String sqlContent, List<String> tags) {
}
