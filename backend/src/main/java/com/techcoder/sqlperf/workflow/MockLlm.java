package com.techcoder.sqlperf.workflow;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.techcoder.sqlperf.diagnostics.MockImpalaMetadataClient;
import com.techcoder.sqlperf.fingerprint.SqlPrettyPrinter;
import org.springframework.stereotype.Component;

/**
 * The answers of the built-in mock LLM endpoint ({@link MockLlmController}), which stands in for the real LLM
 * endpoint so the whole loop (prompt → answer → iteration → test → best → adoption) works before the real model
 * is connected. The app calls it over HTTP exactly like the real endpoint.
 *
 * <p>It answers in the format the prompt asks for (change narrative + one SQL block). Each attempt for a group
 * applies a different set of typical Impala rewrites to the bad SQL, so
 * successive iterations differ slightly: a broadcast hint on the small side of a join, partition pruning on the
 * date filter, a fixed join order, NDV() for COUNT(DISTINCT). Rewrites that do not apply to the SQL are skipped.
 * The SQL is not guaranteed to be correct; it only exercises the workflow.
 */
@Component
public class MockLlm {

    enum Rewrite { BROADCAST_FIRST, BROADCAST_ALL, PARTITION, STRAIGHT_JOIN, NDV }

    /** What each attempt tries (attempts beyond the list cycle through it). */
    private static final List<List<Rewrite>> PLAN = List.of(
            List.of(Rewrite.BROADCAST_FIRST),
            List.of(Rewrite.BROADCAST_FIRST, Rewrite.PARTITION),
            List.of(Rewrite.STRAIGHT_JOIN, Rewrite.BROADCAST_ALL, Rewrite.NDV),
            List.of(Rewrite.BROADCAST_ALL, Rewrite.PARTITION, Rewrite.NDV, Rewrite.STRAIGHT_JOIN));

    private static final Pattern JOIN = Pattern.compile("(?i)\\bJOIN\\s+(?!/\\*)");
    private static final Pattern SELECT = Pattern.compile("(?i)^\\s*SELECT\\s+(?!STRAIGHT_JOIN)");
    private static final Pattern COUNT_DISTINCT = Pattern.compile("(?i)COUNT\\s*\\(\\s*DISTINCT\\s+([^)]+?)\\s*\\)");
    private static final Pattern DATE_LITERAL = Pattern.compile("'(\\d{4}-\\d{2})-\\d{2}'");
    private static final Pattern WHERE = Pattern.compile("(?i)\\bWHERE\\s+");
    private static final Pattern TAIL = Pattern.compile("(?i)\\b(GROUP\\s+BY|ORDER\\s+BY|HAVING|LIMIT)\\b");

    /** The "## Bad SQL" section of the default prompt template (used when the caller sends no SQL). */
    private static final Pattern PROMPT_SQL = Pattern.compile("(?s)##\\s*Bad SQL\\s*\\n(.*?)(?:\\n\\s*##|\\z)");

    private final SqlPrettyPrinter pretty;

    public MockLlm(SqlPrettyPrinter pretty) {
        this.pretty = pretty;
    }

    /**
     * @param badSql  the SQL to rewrite; null = taken from the prompt
     * @param attempt how many answers the group had before (picks the rewrites)
     */
    public String answer(String prompt, String badSql, int attempt) {
        String sql = badSql;
        if (sql == null && prompt != null) {
            Matcher m = PROMPT_SQL.matcher(prompt);
            sql = m.find() ? m.group(1) : "";
        }
        sql = sql == null ? "" : sql.strip();
        List<String> notes = new ArrayList<>();
        int expected = 0;
        for (Rewrite w : PLAN.get(attempt % PLAN.size())) {
            String next = apply(w, sql);
            if (!next.equals(sql)) {
                sql = next;
                notes.add(note(w));
                expected += gain(w);
            }
        }
        if (notes.isEmpty()) {
            Map<String, String> tables = MockImpalaMetadataClient.tables(sql);
            String first = tables.isEmpty() ? "the tables" : tables.keySet().iterator().next();
            notes.add("No rewrite applies to this SQL. Its plan suffers from missing statistics: run COMPUTE STATS on "
                    + first + " so the planner can choose join order and strategy; the SQL itself is unchanged.");
            expected = 15;
        }
        int pct = Math.min(85, expected + (int) (Math.abs((long) sql.hashCode()) % 9) - 4);
        StringBuilder answer = new StringBuilder("### CHANGE NARRATIVE\n");
        for (int i = 0; i < notes.size(); i++) {
            answer.append(i + 1).append(". ").append(notes.get(i)).append('\n');
        }
        answer.append("\nExpected impact: about ").append(Math.max(5, pct))
                .append("% less run time; the result set is unchanged. (Simulated answer, attempt ")
                .append(attempt + 1).append(".)\n\n### OPTIMIZED SQL\n```sql\n")
                .append(pretty.format(sql)).append("\n```\n");
        return answer.toString();
    }


    static String apply(Rewrite w, String sql) {
        return switch (w) {
            case BROADCAST_FIRST -> JOIN.matcher(sql).replaceFirst("JOIN /* +BROADCAST */ ");
            case BROADCAST_ALL -> JOIN.matcher(sql).replaceAll("JOIN /* +BROADCAST */ ");
            case STRAIGHT_JOIN -> JOIN.matcher(sql).find() ? SELECT.matcher(sql).replaceFirst("SELECT STRAIGHT_JOIN ") : sql;
            case NDV -> COUNT_DISTINCT.matcher(sql).replaceAll("NDV($1)");
            case PARTITION -> partition(sql);
        };
    }

    /** Adds a filter on the partition column matching the query's first date literal. */
    private static String partition(String sql) {
        Matcher d = DATE_LITERAL.matcher(sql);
        if (!d.find() || sql.toLowerCase(Locale.ROOT).contains("load_month")) {
            return sql;
        }
        Map<String, String> tables = MockImpalaMetadataClient.tables(sql);
        if (tables.isEmpty()) {
            return sql;
        }
        String predicate = tables.values().iterator().next() + ".load_month >= '" + d.group(1) + "'";
        Matcher where = WHERE.matcher(sql);
        if (where.find()) {
            return sql.substring(0, where.end()) + predicate + " AND " + sql.substring(where.end());
        }
        Matcher tail = TAIL.matcher(sql);
        int at = tail.find() ? tail.start() : sql.length();
        return sql.substring(0, at).stripTrailing() + " WHERE " + predicate + " " + sql.substring(at);
    }

    private static String note(Rewrite w) {
        return switch (w) {
            case BROADCAST_FIRST -> "Broadcast the smaller side of the first join (/* +BROADCAST */): it is small enough "
                    + "to send to every node, which avoids shuffling the large table across the network.";
            case BROADCAST_ALL -> "Broadcast every dimension join (/* +BROADCAST */) so only the fact table is scanned "
                    + "in parallel and nothing is re-partitioned.";
            case PARTITION -> "Filter on the partition column (load_month) for the same date range as the existing "
                    + "date filter, so Impala prunes partitions instead of scanning the whole table. Check that "
                    + "load_month is derived from the filtered date.";
            case STRAIGHT_JOIN -> "Fix the join order (SELECT STRAIGHT_JOIN): the largest table first, then the "
                    + "smaller ones, because the table statistics are stale and the planner picks a poor order.";
            case NDV -> "Replace COUNT(DISTINCT ...) with NDV(...), Impala's approximate distinct count: one pass "
                    + "instead of a second aggregation; accurate within about 1%, confirm this is acceptable.";
        };
    }

    private static int gain(Rewrite w) {
        return switch (w) {
            case BROADCAST_FIRST -> 25;
            case BROADCAST_ALL -> 30;
            case PARTITION -> 35;
            case STRAIGHT_JOIN -> 10;
            case NDV -> 12;
        };
    }
}
