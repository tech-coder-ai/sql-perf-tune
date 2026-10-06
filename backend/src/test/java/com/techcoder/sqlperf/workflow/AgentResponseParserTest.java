package com.techcoder.sqlperf.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AgentResponseParserTest {

    @Test
    void splitsNarrativeAndSql() {
        var p = AgentResponseParser.parse("""
                ### CHANGE NARRATIVE
                1. Added partition filter.
                ### OPTIMIZED SQL
                ```sql
                select 1
                ```
                """);
        assertThat(p.narrative()).isEqualTo("1. Added partition filter.");
        assertThat(p.sql()).isEqualTo("select 1");
    }

    @Test
    void missingSqlBlockLeavesSqlNull() {
        var p = AgentResponseParser.parse("I cannot optimize this query.");
        assertThat(p.sql()).isNull();
        assertThat(p.narrative()).isEqualTo("I cannot optimize this query.");
    }
}
