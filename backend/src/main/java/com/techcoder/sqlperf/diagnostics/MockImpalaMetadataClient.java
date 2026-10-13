package com.techcoder.sqlperf.diagnostics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.techcoder.sqlperf.config.SptProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Stand-in for the Impala client: builds an explain plan, a text profile and table DDL from the SQL itself.
 * Output is deterministic per group (same numbers on every capture) and in Impala's formats, so the profile
 * parser, the prompt and the screens behave as they will with real data.
 */
@Component
@ConditionalOnProperty(prefix = "spt.diagnostics", name = "client", havingValue = "MOCK", matchIfMissing = true)
public class MockImpalaMetadataClient implements QueryMetadataClient {

    /** FROM / JOIN followed by a (db.)table name and an optional alias. */
    private static final Pattern TABLE = Pattern.compile(
            "(?i)\\b(?:from|join)\\s+([a-z_][\\w$]*(?:\\.[a-z_][\\w$]*)?)(?:\\s+(?:as\\s+)?([a-z_]\\w*))?");
    private static final Pattern QUALIFIED_COLUMN = Pattern.compile("(?i)\\b([a-z_]\\w*)\\.([a-z_]\\w*)\\b");
    private static final Set<String> NOT_ALIAS = Set.of("where", "join", "inner", "left", "right", "full", "outer",
            "cross", "on", "group", "order", "limit", "union", "having", "select", "lateral", "using", "semi", "anti");

    private final long delayMillis;

    public MockImpalaMetadataClient(SptProperties props) {
        this.delayMillis = props.diagnostics().mockDelayMillis();
    }

    @Override
    public String source() {
        return "impala (automatic, mock)";
    }

    @Override
    public Result fetch(Request r) throws InterruptedException {
        if (delayMillis > 0) {
            Thread.sleep(delayMillis);
        }
        Random rnd = new Random(r.groupId() == null ? 7 : r.groupId() * 7919);
        Map<String, String> tables = tables(r.sql());
        if (tables.isEmpty()) {
            tables.put("default.unknown_table", "t");
        }
        List<TableInfo> infos = new ArrayList<>();
        List<Long> rows = new ArrayList<>();
        for (Map.Entry<String, String> t : tables.entrySet()) {
            long n = (long) Math.pow(10, 5 + rnd.nextDouble() * 4);
            rows.add(n);
            infos.add(new TableInfo(t.getKey(), ddl(t.getKey(), columns(r.sql(), t.getValue()), rnd), n));
        }
        double totalSeconds = r.runMinutes() != null && r.runMinutes() > 0
                ? r.runMinutes() * 60 : 120 + rnd.nextInt(1800);
        String queryId = hex(rnd, 16) + ":" + hex(rnd, 8) + "00000000";
        long resultRows = 1 + rnd.nextInt(50_000);
        return new Result(queryId, explain(infos, rnd), profile(queryId, r.sql(), infos, rows, totalSeconds, resultRows, rnd),
                resultRows, infos);
    }

    /** table -> alias (the table's own short name when there is no alias). */
    static Map<String, String> tables(String sql) {
        Map<String, String> out = new LinkedHashMap<>();
        if (sql == null) {
            return out;
        }
        Matcher m = TABLE.matcher(sql);
        while (m.find()) {
            String table = m.group(1).toLowerCase(Locale.ROOT);
            String alias = m.group(2) == null || NOT_ALIAS.contains(m.group(2).toLowerCase(Locale.ROOT))
                    ? table.substring(table.lastIndexOf('.') + 1) : m.group(2).toLowerCase(Locale.ROOT);
            out.putIfAbsent(table, alias);
        }
        return out;
    }

    private static List<String> columns(String sql, String alias) {
        Set<String> cols = new LinkedHashSet<>();
        cols.add("id");
        Matcher m = QUALIFIED_COLUMN.matcher(sql);
        while (m.find() && cols.size() < 12) {
            if (m.group(1).equalsIgnoreCase(alias)) {
                cols.add(m.group(2).toLowerCase(Locale.ROOT));
            }
        }
        if (cols.size() < 4) {
            cols.add("created_at");
            cols.add("updated_by");
        }
        return new ArrayList<>(cols);
    }

    private static String ddl(String table, List<String> cols, Random rnd) {
        StringBuilder b = new StringBuilder("CREATE EXTERNAL TABLE ").append(table).append(" (\n");
        for (int i = 0; i < cols.size(); i++) {
            String c = cols.get(i);
            String type = c.equals("id") || c.endsWith("_id") ? "BIGINT"
                    : c.endsWith("_at") || c.endsWith("_ts") || c.endsWith("time") ? "TIMESTAMP"
                    : c.endsWith("_dt") || c.endsWith("date") ? "STRING"
                    : c.contains("amount") || c.contains("price") || c.contains("qty") ? "DECIMAL(18,2)" : "STRING";
            b.append("  ").append(c).append(' ').append(type).append(i < cols.size() - 1 ? ",\n" : "\n");
        }
        boolean partitioned = rnd.nextBoolean();
        b.append(")\n");
        if (partitioned) {
            b.append("PARTITIONED BY (load_month STRING)\n");
        }
        b.append("STORED AS PARQUET\nLOCATION 'hdfs://nameservice1/warehouse/").append(table.replace('.', '/')).append("'\n")
                .append("TBLPROPERTIES ('numFiles'='").append(20 + rnd.nextInt(3000)).append("')");
        return b.toString();
    }

    private static String explain(List<TableInfo> tables, Random rnd) {
        StringBuilder b = new StringBuilder();
        b.append("Max Per-Host Resource Reservation: Memory=").append(8 + rnd.nextInt(120)).append(".00MB Threads=")
                .append(2 + tables.size() * 2).append('\n');
        b.append("Per-Host Resource Estimates: Memory=").append(String.format(Locale.ROOT, "%.2f", 0.2 + rnd.nextDouble() * 6))
                .append("GB\n");
        if (rnd.nextBoolean()) {
            b.append("WARNING: The following tables are missing relevant table and/or column statistics.\n")
                    .append(tables.getLast().tableName()).append("\n");
        }
        b.append("\nPLAN-ROOT SINK\n|\n");
        int node = tables.size() * 2;
        b.append(String.format("%02d:EXCHANGE [UNPARTITIONED]%n|%n", node));
        for (int i = tables.size() - 1; i >= 1; i--) {
            node--;
            b.append(String.format("%02d:HASH JOIN [INNER JOIN, %s]%n|  hash predicates: ...%n|%n", node,
                    tables.get(i).rowCount() < 5_000_000 ? "BROADCAST" : "PARTITIONED"));
            b.append(String.format("|--%02d:SCAN HDFS [%s]%n|     partitions=%d/%d files=%d size=%.1fGB%n|%n", i,
                    tables.get(i).tableName(), 1 + rnd.nextInt(24), 24, 4 + rnd.nextInt(400), 0.1 + rnd.nextDouble() * 40));
        }
        int parts = 365 + rnd.nextInt(400);
        b.append(String.format("00:SCAN HDFS [%s]%n   partitions=%d/%d files=%d size=%.1fGB%n   row-size=%dB cardinality=%s",
                tables.getFirst().tableName(), parts, parts, parts * 3, 10 + rnd.nextDouble() * 200, 40 + rnd.nextInt(400),
                compact(tables.getFirst().rowCount())));
        return b.toString();
    }

    private static String profile(String queryId, String sql, List<TableInfo> tables, List<Long> rows, double total,
                                  long resultRows, Random rnd) {
        double execution = Math.round(total * (0.72 + rnd.nextDouble() * 0.2) * 1000) / 1000d;
        double available = Math.round(execution * 0.9 * 1000) / 1000d;
        StringBuilder b = new StringBuilder();
        b.append("Query (id=").append(queryId).append("):\n");
        b.append("  Summary:\n");
        b.append("    Query Type: QUERY\n    Query State: FINISHED\n    Query Status: OK\n");
        b.append("    Impala Version: impalad version 4.4.0-RELEASE\n");
        b.append("    Default Db: default\n    Coordinator: impala-coord-01:22000\n");
        b.append("    Request Pool: root.etl\n    Admission result: Admitted immediately\n");
        b.append("    Sql Statement: ").append(sql.replaceAll("\\s+", " ").trim()).append('\n');
        b.append("    Per-Host Resource Reservation: Memory=").append(8 + rnd.nextInt(120)).append(".00MB\n");
        if (rnd.nextBoolean()) {
            b.append("    Tables Missing Stats: ").append(tables.getLast().tableName()).append('\n');
        }
        b.append("    ExecSummary:\n");
        b.append("Operator              #Hosts  Avg Time  Max Time  #Rows  Est. #Rows  Peak Mem  Est. Peak Mem  Detail\n");
        b.append("------------------------------------------------------------------------------------------------\n");
        int node = tables.size() * 2;
        b.append(String.format("%02d:EXCHANGE                 1  %s  %s  %s  %s  %s  %s  UNPARTITIONED%n", node,
                dur(total * 0.01), dur(total * 0.01), compact(resultRows), compact(resultRows), "16.00 KB", "16.00 KB"));
        for (int i = tables.size() - 1; i >= 0; i--) {
            double share = i == 0 ? 0.55 : 0.15;
            String peak = String.format(Locale.ROOT, "%.2f MB", 32 + rnd.nextDouble() * 900);
            b.append(String.format("%02d:SCAN HDFS               %d  %s  %s  %s  %s  %s  %s  %s%n", i, 4 + rnd.nextInt(16),
                    dur(execution * share), dur(execution * share * 1.3), compact(rows.get(i)), compact(rows.get(i)),
                    peak, "1.00 GB", tables.get(i).tableName()));
        }
        b.append('\n');
        double user = execution * (2 + rnd.nextDouble() * 10);
        double system = user * 0.08;
        b.append("    Per Node User Time: impala-exec-01:22000(").append(dur(user)).append(")\n");
        b.append("    Per Node System Time: impala-exec-01:22000(").append(dur(system)).append(")\n");
        b.append("    Query Timeline:\n");
        b.append("      - Query submitted: 52.300us (52.300us)\n");
        b.append("      - Planning finished: 1s210ms (1s210ms)\n");
        b.append("      - Ready to start on ").append(tables.size() * 4).append(" backends: 1s800ms (590ms)\n");
        b.append("      - Rows available: ").append(dur(available)).append(" (").append(dur(available - 1.8)).append(")\n");
        b.append("      - Last row fetched: ").append(dur(execution)).append(" (").append(dur(execution - available)).append(")\n");
        b.append("      - Unregister query: ").append(dur(total)).append(" (").append(dur(total - execution)).append(")\n");
        return b.toString();
    }

    /** Impala duration text, e.g. 4m12s300ms. */
    static String dur(double seconds) {
        long ms = Math.max(0, Math.round(seconds * 1000));
        long h = ms / 3_600_000;
        long m = ms / 60_000 % 60;
        long s = ms / 1000 % 60;
        long r = ms % 1000;
        StringBuilder b = new StringBuilder();
        if (h > 0) {
            b.append(h).append('h');
        }
        if (m > 0) {
            b.append(m).append('m');
        }
        if (s > 0) {
            b.append(s).append('s');
        }
        if (r > 0 || b.isEmpty()) {
            b.append(r).append("ms");
        }
        return b.toString();
    }

    private static String compact(long n) {
        if (n >= 1_000_000_000) {
            return String.format(Locale.ROOT, "%.2fB", n / 1e9);
        }
        if (n >= 1_000_000) {
            return String.format(Locale.ROOT, "%.2fM", n / 1e6);
        }
        if (n >= 1_000) {
            return String.format(Locale.ROOT, "%.2fK", n / 1e3);
        }
        return Long.toString(n);
    }

    private static String hex(Random rnd, int len) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < len; i++) {
            b.append(Character.forDigit(rnd.nextInt(16), 16));
        }
        return b.toString();
    }
}
