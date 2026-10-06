package com.techcoder.sqlperf.workflow;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Splits an LLM answer into change narrative and optimized SQL (format requested by the prompt). */
final class AgentResponseParser {

    private static final Pattern SQL_BLOCK = Pattern.compile("```(?:sql)?\\s*\\n(.*?)```", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern NARRATIVE = Pattern.compile("#+\\s*CHANGE NARRATIVE\\s*\\n(.*?)(?=#+\\s*OPTIMIZED SQL|```|\\z)",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    record Parsed(String narrative, String sql) {
    }

    private AgentResponseParser() {
    }

    static Parsed parse(String response) {
        if (response == null) {
            return new Parsed(null, null);
        }
        String sql = null;
        int sqlSection = indexOfIgnoreCase(response, "OPTIMIZED SQL");
        Matcher m = SQL_BLOCK.matcher(response);
        int from = Math.max(0, sqlSection);
        if (m.find(from) || m.find(0)) {
            sql = m.group(1).strip();
        }
        String narrative = null;
        Matcher n = NARRATIVE.matcher(response);
        if (n.find()) {
            narrative = n.group(1).strip();
        } else if (sql != null) {
            narrative = response.substring(0, response.indexOf("```")).strip();
        } else {
            narrative = response.strip();
        }
        return new Parsed(narrative == null || narrative.isEmpty() ? null : narrative, sql);
    }

    private static int indexOfIgnoreCase(String s, String what) {
        return s.toUpperCase().indexOf(what);
    }
}
