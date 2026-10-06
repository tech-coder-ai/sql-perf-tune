package com.techcoder.sqlperf.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ImpalaProfileParserTest {

    private static final String PROFILE = """
            Query (id=7a4c1f2e3b5d6a7f:9e8d7c6b00000000):
              Summary:
                Query Type: QUERY
                Query State: FINISHED
                Tables Missing Stats: fin.ledger
            WARNING: The following tables are missing relevant table and/or column statistics.
                ExecSummary:
            Operator          #Hosts  Avg Time  Max Time  #Rows
            ---------------------------------------------------
            00:SCAN HDFS          10   1m2s      1m5s    1.2B

                Query Timeline:
                  - Query submitted: 50.123us (50.123us)
                  - Rows available: 4m10s (4m9s)
                  - Last row fetched: 4m20s (10s)
                  - Unregister query: 5m (40s)
            """;

    @Test
    void extractsSummaryTimelineAndTeardown() {
        var p = new ImpalaProfileParser().parse(PROFILE);
        assertThat(p.queryId()).isEqualTo("7a4c1f2e3b5d6a7f:9e8d7c6b00000000");
        assertThat(p.summary()).containsEntry("tables missing stats", "fin.ledger");
        assertThat(p.warnings()).hasSize(1);
        assertThat(p.execSummary()).contains("00:SCAN HDFS");
        assertThat(p.executionSeconds()).isEqualTo(260.0);
        assertThat(p.totalSeconds()).isEqualTo(300.0);
        assertThat(p.teardownSeconds()).isEqualTo(40.0);
    }

    @Test
    void parsesImpalaDurations() {
        assertThat(ImpalaProfileParser.toSeconds("1h2m3s")).isEqualTo(3723.0);
        assertThat(ImpalaProfileParser.toSeconds("4s500ms")).isEqualTo(4.5);
        assertThat(ImpalaProfileParser.toSeconds("250us")).isEqualTo(0.00025);
        assertThat(ImpalaProfileParser.toSeconds("abc")).isNull();
    }

    @Test
    void emptyProfileIsSafe() {
        assertThat(new ImpalaProfileParser().parse(null).timelineSeconds()).isEmpty();
    }
}
