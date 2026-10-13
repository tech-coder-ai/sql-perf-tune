package com.techcoder.sqlperf.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import com.techcoder.sqlperf.group.QueryGroup;
import com.techcoder.sqlperf.group.QueryGroupRepository;
import com.techcoder.sqlperf.ingestion.IngestionService;
import com.techcoder.sqlperf.iteration.IterationService;
import com.techcoder.sqlperf.iteration.TuningIteration;
import com.techcoder.sqlperf.tracker.TrackerDto;
import com.techcoder.sqlperf.tracker.TrackerService;
import com.techcoder.sqlperf.tracker.TuningTracker.CaptureStatus;
import com.techcoder.sqlperf.workflow.OptimizationRun;
import com.techcoder.sqlperf.workflow.WorkflowService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Track → automatic diagnostics → three LLM answers (different rewrites) → test runs → best. The agent calls the
 * built-in mock endpoint over real HTTP, exactly as it would call the real LLM endpoint.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spt.diagnostics.mock-delay-millis=100", "spt.agent.mode=HTTP", "spt.agent.mock-delay-millis=200"})
class MockLlmFlowTest {

    @TempDir
    static Path tmp;

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        r.add("SPT_SQLITE_PATH", () -> tmp.resolve("llm.db").toString());
    }

    @Autowired IngestionService ingestion;
    @Autowired QueryGroupRepository groups;
    @Autowired TrackerService trackers;
    @Autowired WorkflowService workflow;
    @Autowired IterationService iterations;
    @Autowired IterationTestService tests;

    @Test
    void simulatedAgentProposesDifferentRewritesThatTestFaster() throws Exception {
        ingestion.importFile("llm.csv", new ByteArrayResource("""
                seq_id,executed_query,user_id,start_time,duration_minutes
                1,"select s.region, count(distinct t.customer_id) from sales.transactions t join sales.stores s on t.store_id = s.id where t.txn_date >= '2026-09-01' group by s.region",alice,2026-10-01 09:00:00,20
                """.getBytes(StandardCharsets.UTF_8)), null);
        QueryGroup g = groups.findAll().getFirst();
        TrackerDto t = trackers.createForGroups(List.of(g.getId())).getFirst();
        await(() -> trackers.get(t.trackerId()).captureStatus() == CaptureStatus.DONE);
        double og = trackers.get(t.trackerId()).ogRunDurationMinutes();

        assertThat(workflow.agentInfo().automatic()).isTrue();
        for (int attempt = 0; attempt < 3; attempt++) {
            OptimizationRun run = workflow.startOptimization(g.getId(), null);
            assertThat(run.getStatus()).isEqualTo(OptimizationRun.Status.PENDING);
            await(() -> workflow.runs(g.getId()).stream().noneMatch(r -> r.getStatus() == OptimizationRun.Status.PENDING));
        }
        assertThat(workflow.runs(g.getId())).allSatisfy(r -> {
            assertThat(r.getStatus()).isEqualTo(OptimizationRun.Status.COMPLETED);
            assertThat(r.getModelName()).isEqualTo("mock-llm");
            assertThat(r.getChangeNarrative()).contains("Expected impact");
        });

        List<IterationService.IterationDto> its = iterations.list(t.trackerId());
        assertThat(its).hasSize(3);
        assertThat(its).extracting(IterationService.IterationDto::optimizedSql).doesNotHaveDuplicates()
                .allSatisfy(sql -> assertThat(sql).containsIgnoringCase("+BROADCAST"));
        assertThat(its.stream().filter(i -> i.optimizedSql().contains("load_month")).count()).isEqualTo(1);
        assertThat(its.stream().filter(i -> i.optimizedSql().contains("NDV(")).count()).isEqualTo(1);

        for (IterationService.IterationDto i : its) {
            tests.test(t.trackerId(), i.id());
        }
        List<IterationService.IterationDto> tested = iterations.list(t.trackerId());
        assertThat(tested).allSatisfy(i -> {
            assertThat(i.status()).isEqualTo(TuningIteration.Status.TESTED);
            assertThat(i.runDurationMinutes()).isLessThan(og);
            assertThat(i.resultMatches()).isTrue();
        });
        assertThat(tested).extracting(IterationService.IterationDto::runDurationMinutes).doesNotHaveDuplicates();
        IterationService.IterationDto best = tested.stream().filter(IterationService.IterationDto::best).findFirst().orElseThrow();
        assertThat(best.runDurationMinutes()).isEqualTo(
                tested.stream().mapToDouble(IterationService.IterationDto::runDurationMinutes).min().orElseThrow());
    }

    private static void await(java.util.function.BooleanSupplier done) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (done.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("timed out");
    }
}
