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
                                Double executionSeconds, Double teardownSeconds, Double totalSeconds) {
    }

    public ParsedProfile parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ParsedProfile(null, Map.of(), List.of(), Map.of(), null, null, null, null);
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
        for (String line : lines) {
            String trimmed = line.trim();
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
        return new ParsedProfile(queryId, summary, warnings, timeline,
                exec.isEmpty() ? null : exec.toString().stripTrailing(), execution, teardown, total);
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
