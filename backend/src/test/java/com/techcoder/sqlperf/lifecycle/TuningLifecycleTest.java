package com.techcoder.sqlperf.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.techcoder.sqlperf.group.QueryGroup;
import com.techcoder.sqlperf.group.QueryGroupRepository;
import com.techcoder.sqlperf.ingestion.IngestionRecovery;
import com.techcoder.sqlperf.ingestion.IngestionService;
import com.techcoder.sqlperf.insights.InsightsService;
import com.techcoder.sqlperf.iteration.IterationService;
import com.techcoder.sqlperf.iteration.TuningIteration;
import com.techcoder.sqlperf.log.QueryLog;
import com.techcoder.sqlperf.log.QueryLogRepository;
import com.techcoder.sqlperf.tracker.TrackerDto;
import com.techcoder.sqlperf.tracker.TrackerService;
import com.techcoder.sqlperf.tracker.TrackerUpdateRequest;
import com.techcoder.sqlperf.tracker.TuningTracker;
import com.techcoder.sqlperf.tracker.TuningTracker.WorkflowStatus;
import com.techcoder.sqlperf.workflow.Feedback;
import com.techcoder.sqlperf.workflow.SqlDiagnostic;
import com.techcoder.sqlperf.workflow.WorkflowService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Detected -> tracked -> diagnosed -> two iterations -> best selected -> one rejected by users -> adopted,
 * then every analytics endpoint over that data.
 */
@SpringBootTest
class TuningLifecycleTest {

    @TempDir
    static Path tmp;

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        r.add("SPT_SQLITE_PATH", () -> tmp.resolve("life.db").toString());
    }

    @Autowired IngestionService ingestion;
    @Autowired QueryGroupRepository groups;
    @Autowired QueryLogRepository logs;
    @Autowired TrackerService trackers;
    @Autowired IterationService iterations;
    @Autowired WorkflowService workflow;
    @Autowired InsightsService insights;
    @Autowired IngestionRecovery recovery;

    private static final String PROFILE = """
            Query (id=aa:bb):
              Summary:
                Per Node User Time: n1:22000(40s) n2:22000(20s)
                Per Node System Time: n1:22000(5s) n2:22000(5s)
                ExecSummary:
            Operator          #Hosts  Avg Time  Max Time  #Rows  Est. #Rows   Peak Mem  Est. Peak Mem  Detail
            ---------------------------------------------------------------------------------------------------
            04:AGGREGATE           1  10.0ms    10.0ms        5         -1    1.0 MB      10.0 MB  FINALIZE
            02:HASH JOIN          10  2m3s      3m1s      1.20B         -1    4.0 GB       2.0 GB  INNER JOIN
            01:SCAN HDFS          10  1m2s      1m5s      2.00M         -1   64.0 MB     176.0 MB  db.dim
            00:SCAN HDFS          10  1m2s      1m5s      1.00B         -1  128.0 MB     176.0 MB  db.fact

                Query Timeline:
                  - Query submitted: 50us (50us)
                  - Last row fetched: 9m (9m)
                  - Unregister query: 10m (1m)
            """;

    @Test
    void fullLifecycleAndInsights() {
        LocalDate today = LocalDate.now();
        String y = today.minusDays(1) + " 09:00:00";
        String t = today + " 10:00:00";
        ingestion.importFile("life.csv", new ByteArrayResource(("""
                seq_id,executed_query,user_id,start_time,duration_minutes
                1,"select f.a, d.b from db.fact f join db.dim d on f.k = d.k where f.dt = '1'",alice,%s,10
                2,"select f.a, d.b from db.fact f join db.dim d on f.k = d.k where f.dt = '2'",bob,%s,12
                3,select x from db.other where y = 1,alice,%s,3
                """.formatted(y, t, t)).getBytes(StandardCharsets.UTF_8)), null);
        QueryGroup g = groups.findAll().stream().filter(x -> x.getGroupSize() == 2).findFirst().orElseThrow();

        // Q1-Q3: today 2 bad queries, the join pattern recurs from yesterday
        InsightsService.Daily daily = insights.daily(today);
        assertThat(daily.badQueries()).isEqualTo(2);
        assertThat(daily.badQueriesPreviousDay()).isEqualTo(1);
        assertThat(daily.recurringPatterns()).isEqualTo(1);
        assertThat(daily.newPatterns()).isEqualTo(1);
        assertThat(daily.byHour().get(10).count()).isEqualTo(2);
        assertThat(daily.peakHour()).isEqualTo(10);
        // per-day buckets land on the right calendar day (guards against dialect day() quirks)
        assertThat(daily.last14Days().getLast().count()).isEqualTo(2);
        assertThat(daily.last14Days().get(12).count()).isEqualTo(1);

        TrackerDto tr = trackers.createForGroups(List.of(g.getId())).getFirst();
        assertThat(tr.workflowStatus()).isEqualTo(WorkflowStatus.NEW);

        // dropdown fields only take configured values
        assertThatThrownBy(() -> trackers.update(tr.trackerId(), update(tr, "Not a theme")))
                .isInstanceOf(IllegalArgumentException.class);
        TrackerDto themed = trackers.update(tr.trackerId(), update(tr, "Trade all"));
        assertThat(themed.theme()).isEqualTo("Trade all");

        // original diagnostics: profile gives run time, CPU, tables and rows scanned
        workflow.captureDiagnostic(g.getId(), new WorkflowService.DiagnosticRequest(SqlDiagnostic.Phase.ORIGINAL, null,
                null, null, null, 100L, null, null, PROFILE, null, null, null, null, null, null, null, null));
        TrackerDto diag = trackers.get(tr.trackerId());
        assertThat(diag.workflowStatus()).isEqualTo(WorkflowStatus.DIAGNOSTICS_CAPTURED);
        assertThat(diag.ogRunDurationMinutes()).isEqualTo(10.0);
        assertThat(diag.ogCpuSeconds()).isEqualTo(70.0);
        assertThat(diag.ogTablesScanned()).isEqualTo(2);
        assertThat(diag.ogRowsScanned()).isEqualTo(1_002_000_000L);

        // two iterations; #2 is faster -> best
        var it1 = iterations.create(tr.trackerId(), new IterationService.IterationRequest("select 1 /* v1 */", "Broadcast join", null));
        var it2 = iterations.create(tr.trackerId(), new IterationService.IterationRequest("select 1 /* v2 */", "Partition filter", null));
        iterations.update(tr.trackerId(), it1.id(), result(6.0, 50.0, 2, 100L));
        iterations.update(tr.trackerId(), it2.id(), result(2.0, 20.0, 1, 100L));
        List<IterationService.IterationDto> list = iterations.list(tr.trackerId());
        assertThat(list).filteredOn(IterationService.IterationDto::best).extracting(IterationService.IterationDto::id)
                .containsExactly(it2.id());
        assertThat(list.get(1).durationImprovementPct()).isEqualTo(80.0);
        assertThat(trackers.get(tr.trackerId()).workflowStatus()).isEqualTo(WorkflowStatus.POST_RUN_VALIDATED);

        // select #1 first, users reject it as inaccurate -> back to tuning; then #2 is selected and adopted
        iterations.select(tr.trackerId(), it1.id());
        assertThat(trackers.get(tr.trackerId()).workflowStatus()).isEqualTo(WorkflowStatus.SME_VALIDATION);
        workflow.addFeedback(g.getId(), new WorkflowService.FeedbackRequest(null, it1.id(), Feedback.SourceRole.BUSINESS_USER,
                Feedback.Decision.REJECTED, "Inaccurate results", "Totals differ for EMEA"));
        assertThat(trackers.get(tr.trackerId()).workflowStatus()).isEqualTo(WorkflowStatus.OPTIMIZATION_REQUESTED);
        iterations.select(tr.trackerId(), it2.id());
        workflow.addFeedback(g.getId(), new WorkflowService.FeedbackRequest(null, null, Feedback.SourceRole.BUSINESS_USER,
                Feedback.Decision.ADOPTED, null, "Looks good"));
        TrackerDto adopted = trackers.get(tr.trackerId());
        assertThat(adopted.workflowStatus()).isEqualTo(WorkflowStatus.ADOPTED);
        assertThat(adopted.adoptedAt()).isNotNull();
        assertThat(adopted.optimizedQuery()).isEqualTo("select 1 /* v2 */");
        assertThat(adopted.postRunDurationMinutes()).isEqualTo(2.0);
        assertThat(adopted.iterationCount()).isEqualTo(2);
        assertThat(iterations.list(tr.trackerId())).extracting(IterationService.IterationDto::status)
                .containsExactly(TuningIteration.Status.REJECTED, TuningIteration.Status.ADOPTED);

        // journey: every happy-path stage up to adoption is done or skipped
        TrackerService.Journey journey = trackers.journey(tr.trackerId());
        assertThat(journey.current()).isEqualTo(WorkflowStatus.ADOPTED);
        assertThat(journey.steps()).extracting(TrackerService.JourneyStep::state).doesNotContain("pending", "current");

        // Q4/Q6/Q9/Q15
        InsightsService.Pipeline p = insights.pipeline(today.minusDays(1), today.minusDays(30), today);
        assertThat(p.priorDay().optimizedPatterns()).isEqualTo(1);
        assertThat(p.outstanding().patterns()).isEqualTo(1); // the untracked "other" pattern
        assertThat(p.outstanding().untrackedPatterns()).isEqualTo(1);
        assertThat(p.rejections().inaccurate()).isEqualTo(1);
        assertThat(p.turnaround().adoptedItems()).isEqualTo(1);

        // Q5/Q11
        InsightsService.Savings s = insights.savings(today.withDayOfMonth(1), today);
        assertThat(s.adoptedInPeriod()).isEqualTo(1);
        assertThat(s.items().getFirst().perRunMinutesSaved()).isEqualTo(8.0);
        assertThat(s.items().getFirst().perRunTablesAvoided()).isEqualTo(1);
        assertThat(s.themes().getFirst().theme()).isEqualTo("Trade all");

        // Q12/Q7/Q13/Q10/Q14 run and see the data
        assertThat(insights.themePriorities(30)).extracting(InsightsService.ThemePriority::theme).contains("Not tracked");
        InsightsService.Users users = insights.users(today.minusDays(6), today);
        assertThat(users.users()).extracting(InsightsService.UserStat::userId).containsExactly("alice", "bob");
        InsightsService.Trends trends = insights.trends(3);
        assertThat(trends.months().getLast().badQueries()).isGreaterThanOrEqualTo(2);
        assertThat(trends.requestsTotal().get(TuningTracker.RequestSource.LOG_DETECTED)).isEqualTo(1);

        // Q14: proactive UAT request joins nothing, creates its own tracker item
        TrackerDto uat = trackers.createRequest(new TrackerService.TuningRequest("select z from db.uat_only where k = 9",
                TuningTracker.RequestSource.PROACTIVE_UAT, "carol", "UAT", null, null, "Slow in UAT", null));
        assertThat(uat.requestSource()).isEqualTo(TuningTracker.RequestSource.PROACTIVE_UAT);
        assertThat(insights.trends(3).requestsTotal().get(TuningTracker.RequestSource.PROACTIVE_UAT)).isEqualTo(1);
    }

    @Test
    void orphanedRowsAreGroupedOnRecovery() {
        QueryLog orphan = new QueryLog();
        orphan.setBatchId(ingestion.importFile("seed.csv", new ByteArrayResource(
                "seq_id,executed_query\n900,select 1 from seed\n".getBytes(StandardCharsets.UTF_8)), null).getId());
        orphan.setSeqId(901L);
        orphan.setExecutedQuery("select q from orphaned_table where a = 1");
        orphan.setSqlEngine("IMPALA");
        orphan.setFingerprint("deadbeef");
        orphan.setCreatedAt(LocalDateTime.now());
        logs.save(orphan);
        recovery.run(null);
        assertThat(logs.findById(orphan.getId()).orElseThrow().getGroupId()).isNotNull();
    }

    private static TrackerUpdateRequest update(TrackerDto d, String theme) {
        return new TrackerUpdateRequest(d.version(), d.workflowStatus(), d.priority(), d.requestSource(), d.requestedBy(),
                d.environment(), d.sampleQueryFormatted(), d.cleansedQuery(), d.optimizedQuery(), d.devTeamLead(),
                "In progress", d.clouderaTeamLead(), d.smeTeamLead(), d.optimizedSqlStatus(),
                d.clouderaPostRunValidation(), d.ogRunDurationMinutes(), d.postRunDurationMinutes(),
                d.ogExecutionTimeSeconds(), d.postRunExecutionTimeSeconds(), d.ogTeardownTimeSeconds(),
                d.postRunTeardownTimeSeconds(), d.ogTeardownPct(), d.postRunTeardownPct(), d.ogCpuSeconds(),
                d.postRunCpuSeconds(), d.ogRowsScanned(), d.postRunRowsScanned(), d.ogTablesScanned(),
                d.postRunTablesScanned(), d.ogBytesScanned(), d.postRunBytesScanned(), d.ogPeakMemoryMb(),
                d.postRunPeakMemoryMb(), theme, d.smeValidation(), d.installStatus(), d.executeStatus(),
                d.validationStatus(), d.changes(), d.problem(), d.recommendations(), null);
    }

    private static IterationService.TestResult result(double minutes, double cpu, int tables, long rows) {
        return new IterationService.TestResult(null, null, null, null, null, minutes, null, null, cpu, 1_000L, null,
                tables, null, rows, true);
    }
}
