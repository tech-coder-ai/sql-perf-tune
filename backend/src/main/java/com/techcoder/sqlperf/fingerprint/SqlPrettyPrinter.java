package com.techcoder.sqlperf.fingerprint;

import com.github.vertical_blank.sqlformatter.SqlFormatter;
import com.github.vertical_blank.sqlformatter.languages.Dialect;
import org.springframework.stereotype.Component;

/** Formats SQL for display ({@code sample_query_formatted}). Never fails: returns the input on error. */
@Component
public class SqlPrettyPrinter {

    public String format(String sql) {
        if (sql == null || sql.isBlank()) {
            return sql;
        }
        try {
            return SqlFormatter.of(Dialect.StandardSql).format(sql);
        } catch (RuntimeException e) {
            return sql;
        }
    }
}
