package com.techcoder.sqlperf.queues;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import com.techcoder.sqlperf.autorun.AutoRunService;
import com.techcoder.sqlperf.group.QueryGroup;
import com.techcoder.sqlperf.group.QueryGroupRepository;
import com.techcoder.sqlperf.ingestion.IngestionService;
import com.techcoder.sqlperf.insights.InsightsService;
import com.techcoder.sqlperf.tracker.TrackerDto;
import com.techcoder.sqlperf.tracker.TrackerService;
import com.techcoder.sqlperf.tracker.TuningTracker.WorkflowStatus;
import com.techcoder.sqlperf.workflow.Feedback;
import com.techcoder.sqlperf.workflow.WorkflowService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Evaluate error (exception, awaiting fix → theme fix → retry), persona queues, one-and-done and reappearing SQL. */
@SpringBootTest(properties = {"spt.diagnostics.auto-capture=false", "spt.agent.mode=MANUAL"})
class WorkflowQueuesTest {

    @TempDir
    static Path tmp;

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        r.add("SPT_SQLITE_PATH", () -> tmp.resolve("queues.db").toString());
    }

    @Autowired IngestionService ingestion;
    @Autowired QueryGroupRepository groups;
    @Autowired TrackerService trackers;
    @Autowired WorkflowService workflow;
    @Autowired AutoRunService autoRun;
    @Autowired WorkQueueService queues;
    @Autowired InsightsService insights;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Test
    void evaluateErrorQueuesAndReappearance() {
        LocalDate today = LocalDate.now();
        String y = today.minusDays(1) + " 09:00:00";
        load("q.csv", """
                seq_id,executed_query,user_id,start_time,duration_minutes
                1,select a from t1 where x = 1,u1,%s,9
                2,select a from t1 where x = 2,u2,%s,9
                3,select b from t2 where x = 1,u1,%s,9
                4,select b from t2 where x = 2,u2,%s,9
                5,select c from t3 where x = 1,u1,%s,9
                6,select c from t3 where x = 2,u2,%s,9
                """.formatted(y, y, y, y, y, y));
        TrackerDto exc = track("t1");
        TrackerDto fix = track("t2");
        TrackerDto adopted = track("t3");
        assertThat(exc.diagnosticId()).startsWith("DX-");

        // RCA exception: out of the queues, never sent to the agent again
        assertThat(trackers.markException(exc.trackerId(), "Table design; nothing to gain from SQL").workflowStatus())
                .isEqualTo(WorkflowStatus.TUNING_EXCEPTION);
        assertThatThrownBy(() -> workflow.startOptimization(exc.groupId(), null))
                .hasMessageContaining("tuning exception");

        // waits for an architectural fix; releasing the theme's fix retries it
        trackers.markAwaitingFix(fix.trackerId(), "Trade all", "trade_all view scans every partition");
        WorkQueueService.Queues q = queues.queues();
        assertThat(q.exceptions()).extracting(WorkQueueService.Item::trackerId).containsExactly(exc.trackerId());
        assertThat(q.awaitingFix()).extracting(WorkQueueService.Item::trackerId).containsExactly(fix.trackerId());
        assertThat(autoRun.releaseThemeFix("Trade all", "view rebuilt").getItemsRetried()).isEqualTo(1);
        TrackerDto retried = trackers.get(fix.trackerId());
        assertThat(retried.workflowStatus()).isEqualTo(WorkflowStatus.OPTIMIZATION_REQUESTED);
        assertThat(retried.autoRunMessage()).contains("agent is manual"); // round continued as far as it can

        // adopted, then the old SQL shows up again: reappearing (CDT follow-up)
        workflow.addFeedback(adopted.groupId(), new WorkflowService.FeedbackRequest(null, null,
                Feedback.SourceRole.BUSINESS_USER, Feedback.Decision.ADOPTED, null, "using it"));
        String later = LocalDateTime.now().plusMinutes(5).format(TS);
        load("again.csv", "seq_id,executed_query,user_id,start_time,duration_minutes\n"
                + "7,select c from t3 where x = 3,u9," + later + ",9\n");
        assertThat(queues.queues().reappeared()).extracting(WorkQueueService.Item::trackerId)
                .containsExactly(adopted.trackerId());
        InsightsService.WorkflowInsight w = insights.workflow(today.plusDays(1));
        assertThat(w.reappeared()).singleElement().satisfies(r -> {
            assertThat(r.trackerId()).isEqualTo(adopted.trackerId());
            assertThat(r.runsSince()).isEqualTo(1);
        });

        // one and done: a pattern that only ran on one day
        load("once.csv", "seq_id,executed_query,user_id,start_time,duration_minutes\n"
                + "8,select d from t4 where x = 1,u1," + y + ",9\n");
        assertThat(insights.workflow(today.minusDays(1)).oneAndDoneOnDay()).isGreaterThanOrEqualTo(1);
    }

    private void load(String name, String csv) {
        ingestion.importFile(name, new ByteArrayResource(csv.getBytes(StandardCharsets.UTF_8)), null);
    }

    private TrackerDto track(String table) {
        QueryGroup g = groups.findAll().stream().filter(x -> x.getSampleQuery().contains(table)).findFirst().orElseThrow();
        return trackers.createForGroups(List.of(g.getId())).getFirst();
    }
}
