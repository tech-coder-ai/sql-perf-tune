package com.techcoder.sqlperf.workflow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Step 6 "Parse Profile": shrinks a raw Impala query profile (often megabytes) into the few facts the
 * optimization prompt needs - summary attributes, warnings, query timeline, execution summary table and
 * derived execution / teardown times.
 */
@Component
public class ImpalaProfileParser {

    private static final Set<String> SUMMARY_KEYS = Set.of(
            "query type", "query state", "query status", "impala version", "default db", "coordinator",
            "estimated per-host mem", "per-host resource reservation", "request pool", "admission result",
            "tables missing stats", "tables with corrupt table stats", "per host min memory reservation",
            "per-host memory reservation", "cluster memory admitted", "executor group", "remote fragments started");

    private static final Pattern KV = Pattern.compile("^\\s*([A-Za-z][A-Za-z0-9 ._()/-]{1,60}):\\s*(.*)$");
    private static final Pattern TIMELINE_EVENT = Pattern.compile("^\\s*-\\s*(.+?):\\s*([0-9][0-9a-zμ.]*)\\s*\\(.*\\)\\s*$");
    private static final Pattern DURATION_PART = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)(h|ms|us|μs|ns|m|s)");
    private static final Pattern QUERY_ID = Pattern.compile("Query \\(id=([0-9a-f:]+)\\)");

    public record ParsedProfile(String queryId, Map<String, String> summary, List<String> warnings,
                                Map<String, Double> timelineSeconds, String execSummary,
                                Double executionSeconds, Double teardownSeconds, Double totalSeconds,
                                Double cpuSeconds, Integer tablesScanned, Long rowsScanned, Double peakMemoryMb) {
    }

    private static final Pattern PER_NODE_TIME = Pattern.compile("^\\s*Per Node (User|System) Time:\\s*(.*)$");
    private static final Pattern PAREN_DURATION = Pattern.compile("\\(([0-9][0-9a-zμ.]*)\\)");
    private static final Pattern COUNT = Pattern.compile("^([0-9]+(?:\\.[0-9]+)?)([KMB]?)$");
    private static final Pattern MEMORY = Pattern.compile("^([0-9]+(?:\\.[0-9]+)?)\\s*(B|KB|MB|GB|TB)$");

    public ParsedProfile parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ParsedProfile(null, Map.of(), List.of(), Map.of(), null, null, null, null, null, null, null, null);
        }
        String[] lines = raw.split("\\R");
        Map<String, String> summary = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        Map<String, Double> timeline = new LinkedHashMap<>();
        StringBuilder exec = new StringBuilder();
        String queryId = null;

        Matcher qm = QUERY_ID.matcher(raw);
        if (qm.find()) {
            queryId = qm.group(1);
        }

        boolean inTimeline = false;
        boolean inExec = false;
        double cpu = 0;
        boolean cpuSeen = false;
        for (String line : lines) {
            String trimmed = line.trim();
            Matcher pn = PER_NODE_TIME.matcher(line);
            if (pn.matches()) {
                Matcher d = PAREN_DURATION.matcher(pn.group(2));
                while (d.find()) {
                    Double secs = toSeconds(d.group(1));
                    if (secs != null) {
                        cpu += secs;
                        cpuSeen = true;
                    }
                }
                continue;
            }
            if (trimmed.startsWith("ExecSummary:")) {
                inExec = true;
                inTimeline = false;
                continue;
            }
            if (inExec) {
                if (trimmed.isEmpty() || (trimmed.endsWith(":") && !trimmed.contains(" "))) {
                    inExec = exec.isEmpty() && trimmed.isEmpty();
                    if (!inExec) {
                        continue;
                    }
                } else {
                    exec.append(line.stripTrailing()).append('\n');
                    continue;
                }
            }
            if (trimmed.startsWith("Query Timeline:")) {
                inTimeline = true;
                continue;
            }
            if (inTimeline) {
                Matcher m = TIMELINE_EVENT.matcher(line);
                if (m.matches()) {
                    Double secs = toSeconds(m.group(2));
                    if (secs != null) {
                        timeline.put(m.group(1).trim(), secs);
                    }
                    continue;
                }
                if (!trimmed.startsWith("-")) {
                    inTimeline = false;
                }
            }
            if (trimmed.toUpperCase(Locale.ROOT).startsWith("WARNING") && warnings.size() < 20) {
                warnings.add(trimmed);
                continue;
            }
            Matcher kv = KV.matcher(line);
            if (kv.matches()) {
                String key = kv.group(1).trim().toLowerCase(Locale.ROOT);
                if (SUMMARY_KEYS.contains(key) && !summary.containsKey(key) && !kv.group(2).isBlank()) {
                    summary.put(key, kv.group(2).trim());
                }
            }
        }

        Double execution = first(timeline, "Last row fetched", "Rows available", "First row fetched");
        Double total = first(timeline, "Unregister query", "Released admission control resources");
        if (total == null && !timeline.isEmpty()) {
            total = timeline.values().stream().max(Double::compare).orElse(null);
        }
        Double teardown = execution != null && total != null && total >= execution
                ? Math.round((total - execution) * 1000d) / 1000d : null;
        String execText = exec.isEmpty() ? null : exec.toString().stripTrailing();
        ScanStats scans = scanStats(execText);
        return new ParsedProfile(queryId, summary, warnings, timeline, execText, execution, teardown, total,
                cpuSeen ? Math.round(cpu * 1000d) / 1000d : null, scans.tables(), scans.rows(), scans.peakMb());
    }

    record ScanStats(Integer tables, Long rows, Double peakMb) {
    }

    /**
     * From the ExecSummary table: number of scan operators (tables scanned), rows they returned and the
     * largest per-operator peak memory. Columns are located by header name, cells split on 2+ spaces.
     */
    static ScanStats scanStats(String execSummary) {
        if (execSummary == null) {
            return new ScanStats(null, null, null);
        }
        String[] lines = execSummary.split("\\R");
        List<String> header = null;
        int tables = 0;
        long rows = 0;
        boolean anyRows = false;
        Double peak = null;
        for (String line : lines) {
            if (line.isBlank() || line.trim().startsWith("---")) {
                continue;
            }
            List<String> cells = List.of(line.trim().split("\\s{2,}"));
            if (header == null) {
                if (line.contains("Operator") && line.contains("#Rows")) {
                    header = cells;
                }
                continue;
            }
            String op = cells.getFirst();
            int rowsIdx = header.indexOf("#Rows");
            int memIdx = header.indexOf("Peak Mem");
            if (memIdx >= 0 && memIdx < cells.size()) {
                Double mb = toMegabytes(cells.get(memIdx));
                if (mb != null && (peak == null || mb > peak)) {
                    peak = mb;
                }
            }
            if (op.toUpperCase(Locale.ROOT).contains("SCAN")) {
                tables++;
                if (rowsIdx >= 0 && rowsIdx < cells.size()) {
                    Long n = toCount(cells.get(rowsIdx));
                    if (n != null) {
                        rows += n;
                        anyRows = true;
                    }
                }
            }
        }
        return new ScanStats(header == null ? null : tables, anyRows ? rows : null,
                peak == null ? null : Math.round(peak * 100d) / 100d);
    }

    /** "1.2B" / "3.50K" / "42" -> count. */
    static Long toCount(String v) {
        Matcher m = COUNT.matcher(v.trim());
        if (!m.matches()) {
            return null;
        }
        double n = Double.parseDouble(m.group(1));
        double mult = switch (m.group(2)) {
            case "K" -> 1e3;
            case "M" -> 1e6;
            case "B" -> 1e9;
            default -> 1;
        };
        return Math.round(n * mult);
    }

    /** "128.0 MB" / "4.1 GB" -> megabytes. */
    static Double toMegabytes(String v) {
        Matcher m = MEMORY.matcher(v.trim());
        if (!m.matches()) {
            return null;
        }
        double n = Double.parseDouble(m.group(1));
        return switch (m.group(2)) {
            case "B" -> n / (1024 * 1024);
            case "KB" -> n / 1024;
            case "MB" -> n;
            case "GB" -> n * 1024;
            default -> n * 1024 * 1024;
        };
    }

    private static Double first(Map<String, Double> timeline, String... keys) {
        for (String k : keys) {
            for (Map.Entry<String, Double> e : timeline.entrySet()) {
                if (e.getKey().equalsIgnoreCase(k)) {
                    return e.getValue();
                }
            }
        }
        return null;
    }

    /** Parses Impala durations such as {@code 1h2m3s}, {@code 4s123ms}, {@code 12.345ms}, {@code 567us}. */
    static Double toSeconds(String text) {
        Matcher m = DURATION_PART.matcher(text);
        double total = 0;
        boolean any = false;
        int end = 0;
        while (m.find()) {
            if (m.start() != end) {
                return null;
            }
            end = m.end();
            any = true;
            double v = Double.parseDouble(m.group(1));
            total += switch (m.group(2)) {
                case "h" -> v * 3600;
                case "m" -> v * 60;
                case "s" -> v;
                case "ms" -> v / 1_000;
                case "us", "μs" -> v / 1_000_000;
                case "ns" -> v / 1_000_000_000;
                default -> 0;
            };
        }
        return any && end == text.length() ? Math.round(total * 1_000_000d) / 1_000_000d : null;
    }
}
