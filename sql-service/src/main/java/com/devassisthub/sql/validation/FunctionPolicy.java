package com.devassisthub.sql.validation;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * FR-10 function blocklist. Entries are function names; an entry ending in {@code *} matches any name with that
 * prefix. Matching is case-insensitive on the bare name (schema prefix and identifier quotes stripped).
 * The list is inherently incomplete: user-defined functions with side effects cannot be detected here.
 */
public final class FunctionPolicy {

    /** PRD FR-10 default list; 4.3 binds overrides from {@code sql.validator.blocked-functions}. */
    public static final List<String> DEFAULT_BLOCKED_FUNCTIONS = List.of(
            "pg_read_file", "pg_read_binary_file", "pg_ls_dir", "pg_stat_file",
            "lo_import", "lo_export", "lo_unlink", "lo_from_bytea",
            "dblink", "dblink_*",
            "pg_terminate_backend", "pg_cancel_backend", "pg_reload_conf",
            "set_config", "nextval", "setval", "pg_advisory_lock*",
            "query_to_xml*");

    private final List<String> exact;
    private final List<String> prefixes;

    public FunctionPolicy(List<String> blockedFunctions) {
        List<String> entries = Objects.requireNonNull(blockedFunctions).stream()
                .filter(Objects::nonNull)
                .map(s -> s.strip().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .toList();
        this.exact = entries.stream().filter(s -> !s.endsWith("*")).toList();
        this.prefixes = entries.stream().filter(s -> s.endsWith("*"))
                .map(s -> s.substring(0, s.length() - 1)).filter(s -> !s.isEmpty()).toList();
    }

    public static FunctionPolicy defaults() {
        return new FunctionPolicy(DEFAULT_BLOCKED_FUNCTIONS);
    }

    public boolean isBlocked(String bareName) {
        String name = bareName.toLowerCase(Locale.ROOT);
        return exact.contains(name) || prefixes.stream().anyMatch(name::startsWith);
    }

    /** Last name segment, unquoted, lower-cased: {@code pg_catalog."PG_READ_FILE"} becomes {@code pg_read_file}. */
    static String bareName(List<String> multipartName, String fallback) {
        String last = multipartName != null && !multipartName.isEmpty()
                ? multipartName.get(multipartName.size() - 1) : fallback;
        if (last == null) {
            return "";
        }
        last = last.strip();
        if (!last.endsWith("\"")) {
            int dot = last.lastIndexOf('.');
            if (dot >= 0) {
                last = last.substring(dot + 1);
            }
        } else {
            int open = last.lastIndexOf('"', last.length() - 2);
            if (open >= 0) {
                last = last.substring(open);
            }
        }
        if (last.length() >= 2 && last.startsWith("\"") && last.endsWith("\"")) {
            last = last.substring(1, last.length() - 1);
        }
        return last.toLowerCase(Locale.ROOT);
    }
}
