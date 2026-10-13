package com.techcoder.sqlperf.diagnostics;

import com.techcoder.sqlperf.common.CurrentUser;
import com.techcoder.sqlperf.common.NotFoundException;
import com.techcoder.sqlperf.common.Texts;
import com.techcoder.sqlperf.group.QueryGroup;
import com.techcoder.sqlperf.group.QueryGroupRepository;
import com.techcoder.sqlperf.iteration.IterationService;
import com.techcoder.sqlperf.iteration.TuningIteration;
import com.techcoder.sqlperf.tracker.TuningTracker;
import com.techcoder.sqlperf.tracker.TuningTrackerRepository;
import com.techcoder.sqlperf.workflow.SqlDiagnostic;
import com.techcoder.sqlperf.workflow.WorkflowService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Step 10 without the manual work: runs an iteration's SQL on the engine and stores its profile as the post-run
 * diagnostic of that iteration, which fills the iteration's test results (run time, CPU, rows, result check) and
 * lets the app pick the best one. Uses the same {@link QueryMetadataClient} as the automatic capture; with the mock
 * client the run is simulated, faster according to the rewrites the SQL contains.
 */
@Service
public class IterationTestService {

    private final TuningTrackerRepository trackers;
    private final QueryGroupRepository groups;
    private final IterationService iterations;
    private final WorkflowService workflow;
    private final QueryMetadataClient client;
    private final TransactionTemplate tx;

    public IterationTestService(TuningTrackerRepository trackers, QueryGroupRepository groups,
                                IterationService iterations, WorkflowService workflow, QueryMetadataClient client,
                                TransactionTemplate tx) {
        this.trackers = trackers;
        this.groups = groups;
        this.iterations = iterations;
        this.workflow = workflow;
        this.client = client;
        this.tx = tx;
    }

    /** Runs the iteration's SQL and records the result; returns the stored diagnostic. */
    public SqlDiagnostic test(Long trackerId, Long iterationId) throws Exception {
        String requester = CurrentUser.name();
        QueryMetadataClient.Request req = tx.execute(s -> {
            TuningTracker t = trackers.findById(trackerId).orElseThrow(() -> new NotFoundException("Tracker", trackerId));
            TuningIteration it = iterations.find(trackerId, iterationId);
            if (Texts.isBlank(it.getOptimizedSql())) {
                throw new IllegalArgumentException("Iteration #" + it.getIterationNo() + " has no SQL to run");
            }
            QueryGroup g = groups.findById(t.getGroupId()).orElseThrow(() -> new NotFoundException("Query group", t.getGroupId()));
            if (!"IMPALA".equals(g.getSqlEngine())) {
                throw new IllegalArgumentException("Test runs support Impala only; enter " + g.getSqlEngine()
                        + " results by hand");
            }
            Long originalRows = workflow.diagnostics(g.getId()).stream()
                    .filter(d -> d.getPhase() == SqlDiagnostic.Phase.ORIGINAL && d.getStatus() == SqlDiagnostic.Status.CAPTURED)
                    .map(SqlDiagnostic::getRowCount).filter(java.util.Objects::nonNull).findFirst().orElse(null);
            return new QueryMetadataClient.Request(g.getId(), g.getSqlEngine(), it.getOptimizedSql(),
                    t.getOgRunDurationMinutes() != null ? t.getOgRunDurationMinutes() : g.getAvgDurationMinutes(),
                    originalRows);
        });
        // the run itself holds no transaction (it can take as long as the query)
        QueryMetadataClient.Result r = client.fetch(req);
        return tx.execute(s -> CurrentUser.runAs(requester, () -> {
            SqlDiagnostic d = workflow.captureDiagnostic(req.groupId(), new WorkflowService.DiagnosticRequest(
                    SqlDiagnostic.Phase.OPTIMIZED, null, iterationId, r.queryId(), req.sql(), r.rowCount(), null,
                    r.explainPlan(), r.profileRaw(), null, SqlDiagnostic.Status.CAPTURED, null, null, null, null, null,
                    null));
            d.setCapturedBy(client.source());
            return d;
        }));
    }
}
