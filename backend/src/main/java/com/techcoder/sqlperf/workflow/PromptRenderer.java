package com.techcoder.sqlperf.workflow;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.techcoder.sqlperf.group.QueryGroup;
import org.springframework.stereotype.Component;

/** Fills {@code {{placeholder}}} tokens of a prompt template with a group's captured artefacts (step 7/8). */
@Component
public class PromptRenderer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([a-z_]+)\\s*}}");
    private static final String MISSING = "(not captured)";

    public String render(String template, QueryGroup group, SqlDiagnostic original, List<TableDdl> ddls) {
        Map<String, String> values = Map.of(
                "bad_sql", nz(group.getSampleQuery()),
                "ddl", ddls.isEmpty() ? MISSING : ddls.stream()
                        .sorted(Comparator.comparing(TableDdl::getTableName))
                        .map(d -> "-- " + d.getTableName() + "\n" + nz(d.getDdlText()))
                        .collect(Collectors.joining("\n\n")),
                "row_counts", ddls.isEmpty() ? MISSING : ddls.stream()
                        .sorted(Comparator.comparing(TableDdl::getTableName))
                        .map(d -> d.getTableName() + ": " + (d.getRowCount() == null ? "unknown" : d.getRowCount()))
                        .collect(Collectors.joining("\n")),
                "explain", original == null ? MISSING : nz(original.getExplainPlan()),
                "profile_summary", original == null ? MISSING : nz(original.getProfileSummary()),
                "exec_summary", original == null ? MISSING : nz(original.getExecSummary()),
                "result_row_count", original == null || original.getRowCount() == null ? MISSING
                        : String.valueOf(original.getRowCount()));
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(values.getOrDefault(m.group(1), m.group(0))));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String nz(String s) {
        return s == null || s.isBlank() ? MISSING : s;
    }
}
