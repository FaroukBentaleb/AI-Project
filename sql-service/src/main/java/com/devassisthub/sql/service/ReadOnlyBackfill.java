package com.devassisthub.sql.service;

import com.devassisthub.sql.validation.ReadOnlyValidator;
import com.devassisthub.sql.validation.ValidationResult;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * One-time startup backfill (Story 4.4): validates every row whose read_only is NULL (saved before V6) and
 * stores the flag and reasons. It only touches read_only/read_only_reasons: sql_content, version and
 * updated_at stay as they were, so a backfill is not an edit. Idempotent; a no-op once every row is set.
 */
@Component
public class ReadOnlyBackfill implements ApplicationRunner {

    static final int BATCH = 500;
    private static final Logger log = LoggerFactory.getLogger(ReadOnlyBackfill.class);

    private final JdbcTemplate jdbc;
    private final ReadOnlyValidator validator;

    public ReadOnlyBackfill(JdbcTemplate jdbc, ReadOnlyValidator validator) {
        this.jdbc = jdbc;
        this.validator = validator;
    }

    @Override
    public void run(ApplicationArguments args) {
        int total = backfill();
        if (total > 0) {
            log.info("Read-only backfill set the flag on {} saved queries", total);
        }
    }

    /** Returns the number of rows updated. */
    public int backfill() {
        int total = 0;
        while (true) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT id, sql_content FROM saved_query WHERE read_only IS NULL ORDER BY id LIMIT ?", BATCH);
            if (rows.isEmpty()) {
                return total;
            }
            for (Map<String, Object> row : rows) {
                ValidationResult r = validator.validate((String) row.get("sql_content"));
                String[] reasons = SavedQueryService.reasons(r).toArray(String[]::new);
                total += jdbc.update(con -> {
                    var ps = con.prepareStatement(
                            "UPDATE saved_query SET read_only = ?, read_only_reasons = ? WHERE id = ? AND read_only IS NULL");
                    ps.setBoolean(1, r.accepted());
                    ps.setArray(2, con.createArrayOf("text", reasons));
                    ps.setLong(3, ((Number) row.get("id")).longValue());
                    return ps;
                });
            }
        }
    }
}
