package com.techcoder.sqlperf.insights;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import com.techcoder.sqlperf.group.QueryGroup;
import com.techcoder.sqlperf.group.QueryGroupController;
import com.techcoder.sqlperf.group.QueryGroupRepository;
import com.techcoder.sqlperf.ingestion.IngestionService;
import com.techcoder.sqlperf.tracker.TrackerController;
import com.techcoder.sqlperf.tracker.TrackerService;
import com.techcoder.sqlperf.tracker.TuningTracker.WorkflowStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Tuning pipeline bars (Command center, Insights Q6) open lists whose row counts equal the bars. */
@SpringBootTest(properties = {"spt.diagnostics.auto-capture=false", "spt.agent.mode=MANUAL"})
class PipelineDrillDownTest {

    @TempDir
    static Path tmp;

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        r.add("SPT_SQLITE_PATH", () -> tmp.resolve("pipe.db").toString());
    }

    @Autowired IngestionService ingestion;
    @Autowired QueryGroupRepository groups;
    @Autowired QueryGroupController groupApi;
    @Autowired TrackerController trackerApi;
    @Autowired TrackerService trackers;
    @Autowired InsightsService insights;

    @Test
    void stageBarsMatchTheirLists() {
        LocalDate day = LocalDate.of(2026, 10, 8);
        String recent = day.minusDays(2) + " 10:00:00";
        String old = day.minusDays(90) + " 10:00:00";
        ingestion.importFile("pipe.csv", new ByteArrayResource(("""
                seq_id,executed_query,user_id,start_time,duration_minutes
                1,select a from t1 where x = 1,u1,%s,5
                2,select b from t2 where x = 1,u1,%s,5
                3,select c from t3 where x = 1,u1,%s,5
                4,select d from t4 where x = 1,u1,%s,5
                """.formatted(recent, recent, old, old)).getBytes(StandardCharsets.UTF_8)), null);
        // t2 (recent) and t4 (old) go on the tracker; t1 (recent) and t3 (old) stay untracked
        List<Long> tracked = groups.findAll().stream()
                .filter(g -> g.getSampleQuery().contains("t2") || g.getSampleQuery().contains("t4"))
                .map(QueryGroup::getId).toList();
        trackers.createForGroups(tracked);

        InsightsService.Pipeline p = insights.pipeline(day.minusDays(1), day.minusDays(29), day);
        LocalDate from = p.outstanding().asOf().minusDays(p.outstanding().activeDays() - 1);
        LocalDate to = p.outstanding().asOf();
        assertThat(to).isEqualTo(day);

        // "Not tracked yet": untracked patterns active in the window -> Query groups
        var untracked = groupApi.search(0, 50, null, null,
                new QueryGroupController.GroupFilter(null, null, null, null, null, false, from, to, null, null));
        assertThat(untracked.totalElements()).isEqualTo(p.outstanding().untrackedPatterns()).isEqualTo(1);
        // without the window the old untracked pattern is listed too (the all-time figure)
        var allUntracked = groupApi.search(0, 50, null, null,
                new QueryGroupController.GroupFilter(null, null, null, null, null, false, null, null, null, null));
        assertThat(allUntracked.totalElements()).isEqualTo(p.outstanding().allTimeUntrackedPatterns()).isEqualTo(2);

        // a stage bar (all items in that stage) -> Tuning tracker filtered by the stage
        long triage = p.stages().stream().filter(s -> s.status() == WorkflowStatus.NEW).findFirst().orElseThrow().count();
        assertThat(trackerApi.search(0, 50, null, null, null, List.of(WorkflowStatus.NEW), null, null, null, null, null,
                null, null).totalElements()).isEqualTo(triage).isEqualTo(2);
        // Q6 "In progress" (active window) -> the same stages, limited to the window
        assertThat(trackerApi.search(0, 50, null, null, null, List.of(WorkflowStatus.NEW), null, null, null, null, null,
                from, to).totalElements()).isEqualTo(p.outstanding().inProgressPatterns()).isEqualTo(1);
    }
}
