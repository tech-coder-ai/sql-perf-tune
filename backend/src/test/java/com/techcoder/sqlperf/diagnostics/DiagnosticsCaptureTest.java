package com.techcoder.sqlperf.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import com.techcoder.sqlperf.group.QueryGroup;
import com.techcoder.sqlperf.group.QueryGroupRepository;
import com.techcoder.sqlperf.ingestion.IngestionService;
import com.techcoder.sqlperf.tracker.TrackerDto;
import com.techcoder.sqlperf.tracker.TrackerService;
import com.techcoder.sqlperf.tracker.TuningTracker.CaptureStatus;
import com.techcoder.sqlperf.tracker.TuningTracker.WorkflowStatus;
import com.techcoder.sqlperf.workflow.SqlDiagnostic;
import com.techcoder.sqlperf.workflow.WorkflowService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Tracking a group starts the background explain / profile / DDL capture, which fills the original-run metrics. */
@SpringBootTest(properties = {"spt.diagnostics.auto-capture=true", "spt.diagnostics.mock-delay-millis=300"})
class DiagnosticsCaptureTest {

    @TempDir
    static Path tmp;

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        r.add("SPT_SQLITE_PATH", () -> tmp.resolve("capture.db").toString());
    }

    @Autowired IngestionService ingestion;
    @Autowired QueryGroupRepository groups;
    @Autowired TrackerService trackers;
    @Autowired WorkflowService workflow;
    @Autowired DiagnosticsCaptureService capture;

    @Test
    void trackingCapturesDiagnosticsInTheBackground() throws InterruptedException {
        ingestion.importFile("cap.csv", new ByteArrayResource("""
                seq_id,executed_query,user_id,start_time,duration_minutes
                1,"select f.amount, d.region from sales.fact f join sales.dim_store d on f.store_id = d.id where f.dt = '1'",alice,2026-10-01 09:00:00,12
                2,"select f.amount, d.region from sales.fact f join sales.dim_store d on f.store_id = d.id where f.dt = '2'",bob,2026-10-01 10:00:00,14
                """.getBytes(StandardCharsets.UTF_8)), null);
        QueryGroup g = groups.findAll().getFirst();

        TrackerDto created = trackers.createForGroups(List.of(g.getId())).getFirst();
        assertThat(created.captureStatus()).isEqualTo(CaptureStatus.PENDING);
        assertThat(created.captureRequestedAt()).isNotNull();

        TrackerDto done = await(created.trackerId());
        assertThat(done.captureStatus()).isEqualTo(CaptureStatus.DONE);
        assertThat(done.captureMessage()).contains("Explain plan and profile captured").contains("2 table DDLs");
        assertThat(done.workflowStatus()).isEqualTo(WorkflowStatus.DIAGNOSTICS_CAPTURED);
        assertThat(done.ogCpuSeconds()).isPositive();
        assertThat(done.ogRowsScanned()).isPositive();
        assertThat(done.ogTablesScanned()).isEqualTo(2);
        assertThat(done.ogTeardownTimeSeconds()).isNotNull();

        List<SqlDiagnostic> diags = workflow.diagnostics(g.getId());
        assertThat(diags).hasSize(1);
        assertThat(diags.getFirst().getExplainPlan()).contains("SCAN HDFS [sales.fact]");
        assertThat(diags.getFirst().getCapturedBy()).startsWith("impala");
        assertThat(workflow.ddls(g.getId())).extracting("tableName").containsExactlyInAnyOrder("sales.fact", "sales.dim_store");

        // running it again keeps what is there instead of duplicating it
        capture.request(created.trackerId());
        TrackerDto again = await(created.trackerId());
        assertThat(again.captureStatus()).isEqualTo(CaptureStatus.DONE);
        assertThat(again.captureMessage()).startsWith("Kept");
        assertThat(workflow.diagnostics(g.getId())).hasSize(1);
        assertThat(workflow.ddls(g.getId())).hasSize(2);
    }

    @Test
    void findsTablesAndAliases() {
        assertThat(MockImpalaMetadataClient.tables("select * from a.x t join y on t.k = y.k left join (select 1) s where 1=1"))
                .containsEntry("a.x", "t").containsEntry("y", "y").hasSize(2);
        assertThat(MockImpalaMetadataClient.dur(272.3)).isEqualTo("4m32s300ms");
    }

    private TrackerDto await(Long id) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            TrackerDto t = trackers.get(id);
            if (t.captureStatus() == CaptureStatus.DONE || t.captureStatus() == CaptureStatus.FAILED) {
                return t;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("capture did not finish");
    }
}
