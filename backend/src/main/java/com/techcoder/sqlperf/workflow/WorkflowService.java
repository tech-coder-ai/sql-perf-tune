package com.techcoder.sqlperf.workflow;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.techcoder.sqlperf.common.CurrentUser;
import com.techcoder.sqlperf.common.NotFoundException;
import com.techcoder.sqlperf.common.Texts;
import com.techcoder.sqlperf.group.QueryGroup;
import com.techcoder.sqlperf.group.QueryGroupRepository;
import com.techcoder.sqlperf.iteration.IterationService;
import com.techcoder.sqlperf.iteration.TuningIteration;
import com.techcoder.sqlperf.lookup.LookupService;
import com.techcoder.sqlperf.tracker.TrackerService;
import com.techcoder.sqlperf.tracker.TuningTracker;
import com.techcoder.sqlperf.tracker.TuningTracker.WorkflowStatus;
import com.techcoder.sqlperf.tracker.TuningTrackerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Orchestrates the tactical workflow for one query group:
 * diagnostics (4-6) -> prompt + agent (7-9) -> post-run diagnostics (10) -> feedback / adoption (12).
 * Every step also updates the group's tracker so the tracking screen reflects progress.
 */
@Service
public class WorkflowService {

    private final QueryGroupRepository groups;
    private final SqlDiagnosticRepository diagnostics;
    private final TableDdlRepository ddls;
    private final PromptTemplateRepository prompts;
    private final OptimizationRunRepository runs;
    private final FeedbackRepository feedback;
    private final TuningTrackerRepository trackers;
    private final TrackerService trackerService;
    private final ImpalaProfileParser profileParser;
    private final PromptRenderer renderer;
    private final OptimizationAgent agent;
    private final JsonMapper json;
    private final IterationService iterations;
    private final LookupService lookups;

    public WorkflowService(QueryGroupRepository groups, SqlDiagnosticRepository diagnostics, TableDdlRepository ddls,
                           PromptTemplateRepository prompts, OptimizationRunRepository runs,
                           FeedbackRepository feedback, TuningTrackerRepository trackers, TrackerService trackerService,
                           ImpalaProfileParser profileParser, PromptRenderer renderer, List<OptimizationAgent> agents,
                           JsonMapper json, IterationService iterations, LookupService lookups) {
        this.groups = groups;
        this.diagnostics = diagnostics;
        this.ddls = ddls;
        this.prompts = prompts;
        this.runs = runs;
        this.feedback = feedback;
        this.trackers = trackers;
        this.trackerService = trackerService;
        this.profileParser = profileParser;
        this.renderer = renderer;
        this.agent = agents.stream().filter(a -> !(a instanceof ManualOptimizationAgent)).findFirst()
                .orElseGet(() -> agents.getFirst());
        this.json = json;
        this.iterations = iterations;
        this.lookups = lookups;
    }

    // ------------------------------------------------------------------ diagnostics (steps 4-6, 10)

    /**
     * @param iterationId for phase OPTIMIZED: the iteration being tested (defaults to the latest one, or a new
     *                    manual iteration built from {@code sqlText})
     */
    public record DiagnosticRequest(SqlDiagnostic.Phase phase, Long optimizationRunId, Long iterationId, String queryId,
                                    String sqlText, Long rowCount, Double runDurationSeconds, String explainPlan,
                                    String profileRaw, String execSummary, SqlDiagnostic.Status status,
                                    String errorMessage, Double cpuSeconds, Long rowsScanned, Long bytesScanned,
                                    Integer tablesScanned, Double peakMemoryMb) {
    }

    @Transactional
    public SqlDiagnostic captureDiagnostic(Long groupId, DiagnosticRequest req) {
        QueryGroup g = group(groupId);
        if (req.phase() == null) {
            throw new IllegalArgumentException("phase is required");
        }
        SqlDiagnostic d = new SqlDiagnostic();
        d.setGroupId(groupId);
        d.setPhase(req.phase());
        d.setOptimizationRunId(req.optimizationRunId());
        d.setSqlText(Texts.isBlank(req.sqlText()) && req.phase() == SqlDiagnostic.Phase.ORIGINAL
                ? g.getSampleQuery() : req.sqlText());
        d.setRowCount(req.rowCount());
        d.setExplainPlan(req.explainPlan());
        d.setProfileRaw(req.profileRaw());
        d.setStatus(req.status() == null ? SqlDiagnostic.Status.CAPTURED : req.status());
        d.setErrorMessage(req.errorMessage());

        ImpalaProfileParser.ParsedProfile parsed = profileParser.parse(req.profileRaw());
        d.setQueryId(Texts.isBlank(req.queryId()) ? parsed.queryId() : req.queryId());
        d.setExecSummary(Texts.isBlank(req.execSummary()) ? parsed.execSummary() : req.execSummary());
        if (Texts.isBlank(req.profileRaw()) && !Texts.isBlank(req.execSummary())) {
            ImpalaProfileParser.ScanStats fromSummary = ImpalaProfileParser.scanStats(req.execSummary());
            parsed = new ImpalaProfileParser.ParsedProfile(parsed.queryId(), parsed.summary(), parsed.warnings(),
                    parsed.timelineSeconds(), req.execSummary(), null, null, null, null, fromSummary.tables(),
                    fromSummary.rows(), fromSummary.peakMb());
        }
        d.setRunDurationSeconds(req.runDurationSeconds() != null ? req.runDurationSeconds() : parsed.totalSeconds());
        d.setExecutionTimeSeconds(parsed.executionSeconds());
        d.setTeardownTimeSeconds(parsed.teardownSeconds());
        d.setCpuSeconds(first(req.cpuSeconds(), parsed.cpuSeconds()));
        d.setRowsScanned(first(req.rowsScanned(), parsed.rowsScanned()));
        d.setBytesScanned(req.bytesScanned());
        d.setTablesScanned(first(req.tablesScanned(), parsed.tablesScanned()));
        d.setPeakMemoryMb(first(req.peakMemoryMb(), parsed.peakMemoryMb()));
        if (!Texts.isBlank(req.profileRaw())) {
            // exec summary is stored in its own column; leave it out of the compact LLM summary
            Map<String, Object> compact = new java.util.LinkedHashMap<>();
            compact.put("queryId", parsed.queryId());
            compact.put("summary", parsed.summary());
            compact.put("warnings", parsed.warnings());
            compact.put("timelineSeconds", parsed.timelineSeconds());
            compact.put("executionSeconds", parsed.executionSeconds());
            compact.put("teardownSeconds", parsed.teardownSeconds());
            compact.put("totalSeconds", parsed.totalSeconds());
            compact.put("cpuSeconds", parsed.cpuSeconds());
            compact.put("tablesScanned", parsed.tablesScanned());
            compact.put("rowsScanned", parsed.rowsScanned());
            compact.put("peakMemoryMb", parsed.peakMemoryMb());
            d.setProfileSummary(json.writerWithDefaultPrettyPrinter().writeValueAsString(compact));
        }
        d.setCapturedAt(LocalDateTime.now());
        d.setCapturedBy(CurrentUser.name());

        TuningTracker t = trackerService.applyWorkflow(groupId, null, null);
        if (d.getPhase() == SqlDiagnostic.Phase.OPTIMIZED) {
            TuningIteration it = req.iterationId() != null ? iterations.find(t.getId(), req.iterationId())
                    : iterations.latest(t.getId()).orElse(null);
            if (it == null) {
                it = iterations.newIteration(t.getId(), TuningIteration.Source.MANUAL, req.optimizationRunId(),
                        Texts.isBlank(d.getSqlText()) ? t.getOptimizedQuery() : d.getSqlText(), null);
            }
            d.setIterationId(it.getId());
            if (Texts.isBlank(d.getSqlText())) {
                d.setSqlText(it.getOptimizedSql());
            }
        }
        diagnostics.save(d);

        if (d.getStatus() == SqlDiagnostic.Status.CAPTURED) {
            Double runMinutes = d.getRunDurationSeconds() == null ? null
                    : Math.round(d.getRunDurationSeconds() / 60d * 10_000d) / 10_000d;
            if (d.getPhase() == SqlDiagnostic.Phase.ORIGINAL) {
                trackerService.advanceTo(t, WorkflowStatus.DIAGNOSTICS_CAPTURED);
                setIfPresent(runMinutes, t::setOgRunDurationMinutes);
                setIfPresent(d.getExecutionTimeSeconds(), t::setOgExecutionTimeSeconds);
                setIfPresent(d.getTeardownTimeSeconds(), t::setOgTeardownTimeSeconds);
                setIfPresent(d.getCpuSeconds(), t::setOgCpuSeconds);
                setIfPresent(d.getRowsScanned(), t::setOgRowsScanned);
                setIfPresent(d.getBytesScanned(), t::setOgBytesScanned);
                setIfPresent(d.getTablesScanned(), t::setOgTablesScanned);
                setIfPresent(d.getPeakMemoryMb(), t::setOgPeakMemoryMb);
                if (d.getTeardownTimeSeconds() != null) {
                    t.setOgTeardownPct(null);
                }
                recomputePct(t);
            } else {
                Long originalRows = diagnostics.findByGroupIdOrderByIdDesc(groupId).stream()
                        .filter(x -> x.getPhase() == SqlDiagnostic.Phase.ORIGINAL && x.getRowCount() != null)
                        .map(SqlDiagnostic::getRowCount).findFirst().orElse(null);
                TuningIteration it = iterations.find(t.getId(), d.getIterationId());
                iterations.recordTest(it, runMinutes, d.getExecutionTimeSeconds(), d.getTeardownTimeSeconds(),
                        d.getCpuSeconds(), d.getRowsScanned(), d.getBytesScanned(), d.getTablesScanned(),
                        d.getPeakMemoryMb(), d.getRowCount(), originalRows);
                recomputePct(t);
            }
        } else if (d.getPhase() == SqlDiagnostic.Phase.OPTIMIZED && d.getIterationId() != null) {
            TuningIteration it = iterations.find(t.getId(), d.getIterationId());
            if (it.getStatus() == TuningIteration.Status.PROPOSED) {
                it.setStatus(TuningIteration.Status.FAILED);
                it.setNotes((it.getNotes() == null ? "" : it.getNotes() + "\n") + "Test run " + d.getStatus()
                        + (Texts.isBlank(d.getErrorMessage()) ? "" : ": " + d.getErrorMessage()));
            }
        }
        return d;
    }

    private static <T> T first(T a, T b) {
        return a != null ? a : b;
    }

    private static void recomputePct(TuningTracker t) {
        if (t.getOgTeardownPct() == null && t.getOgTeardownTimeSeconds() != null && t.getOgRunDurationMinutes() != null
                && t.getOgRunDurationMinutes() > 0) {
            t.setOgTeardownPct(Math.round(t.getOgTeardownTimeSeconds() / (t.getOgRunDurationMinutes() * 60) * 100_000d) / 1000d);
        }
        if (t.getPostRunTeardownPct() == null && t.getPostRunTeardownTimeSeconds() != null
                && t.getPostRunDurationMinutes() != null && t.getPostRunDurationMinutes() > 0) {
            t.setPostRunTeardownPct(Math.round(t.getPostRunTeardownTimeSeconds() / (t.getPostRunDurationMinutes() * 60) * 100_000d) / 1000d);
        }
    }

    private static <T> void setIfPresent(T v, java.util.function.Consumer<T> setter) {
        if (v != null) {
            setter.accept(v);
        }
    }

    @Transactional(readOnly = true)
    public List<SqlDiagnostic> diagnostics(Long groupId) {
        return diagnostics.findByGroupIdOrderByIdDesc(groupId);
    }

    // ------------------------------------------------------------------ DDL (step 8 inputs)

    public record DdlRequest(String tableName, String ddlText, Long rowCount) {
    }

    @Transactional
    public TableDdl addDdl(Long groupId, DdlRequest req) {
        group(groupId);
        if (Texts.isBlank(req.tableName())) {
            throw new IllegalArgumentException("tableName is required");
        }
        TableDdl d = new TableDdl();
        d.setGroupId(groupId);
        d.setTableName(req.tableName().trim());
        d.setDdlText(req.ddlText());
        d.setRowCount(req.rowCount());
        d.setCapturedAt(LocalDateTime.now());
        d.setCapturedBy(CurrentUser.name());
        return ddls.save(d);
    }

    @Transactional
    public void deleteDdl(Long groupId, Long ddlId) {
        TableDdl d = ddls.findById(ddlId).filter(x -> x.getGroupId().equals(groupId))
                .orElseThrow(() -> new NotFoundException("DDL", ddlId));
        ddls.delete(d);
    }

    @Transactional(readOnly = true)
    public List<TableDdl> ddls(Long groupId) {
        return ddls.findByGroupIdOrderByIdDesc(groupId);
    }

    // ------------------------------------------------------------------ optimization (steps 7-9)

    @Transactional
    public OptimizationRun startOptimization(Long groupId, Long promptTemplateId) {
        QueryGroup g = group(groupId);
        PromptTemplate tpl = promptTemplateId != null
                ? prompts.findById(promptTemplateId).orElseThrow(() -> new NotFoundException("Prompt template", promptTemplateId))
                : prompts.findFirstBySqlEngineAndActiveTrueOrderByVersionNoDesc(g.getSqlEngine())
                        .orElseThrow(() -> new IllegalArgumentException("No active prompt template for " + g.getSqlEngine()));
        SqlDiagnostic original = diagnostics.findByGroupIdOrderByIdDesc(groupId).stream()
                .filter(d -> d.getPhase() == SqlDiagnostic.Phase.ORIGINAL && d.getStatus() == SqlDiagnostic.Status.CAPTURED)
                .findFirst().orElse(null);

        OptimizationRun run = new OptimizationRun();
        run.setGroupId(groupId);
        run.setPromptTemplateId(tpl.getId());
        run.setPromptText(renderer.render(tpl.getTemplateText(), g, original, ddls.findByGroupIdOrderByIdDesc(groupId)));
        run.setModelName(agent.modelName());
        run.setRequestedAt(LocalDateTime.now());
        run.setRequestedBy(CurrentUser.name());
        runs.save(run);
        trackerService.applyWorkflow(groupId, WorkflowStatus.OPTIMIZATION_REQUESTED, null);

        try {
            Optional<String> answer = agent.optimize(run.getPromptText());
            answer.ifPresent(a -> complete(run, a, agent.modelName()));
        } catch (RuntimeException e) {
            run.setStatus(OptimizationRun.Status.FAILED);
            run.setErrorMessage(e.getMessage());
            run.setCompletedAt(LocalDateTime.now());
        }
        return run;
    }

    @Transactional
    public OptimizationRun submitResponse(Long groupId, Long runId, String responseRaw, String modelName) {
        OptimizationRun run = run(groupId, runId);
        if (Texts.isBlank(responseRaw)) {
            throw new IllegalArgumentException("responseRaw is required");
        }
        complete(run, responseRaw, Texts.isBlank(modelName) ? run.getModelName() : modelName);
        return run;
    }

    private void complete(OptimizationRun run, String answer, String model) {
        AgentResponseParser.Parsed p = AgentResponseParser.parse(answer);
        run.setResponseRaw(answer);
        run.setModelName(model);
        run.setChangeNarrative(p.narrative());
        run.setOptimizedSql(p.sql());
        run.setStatus(p.sql() == null ? OptimizationRun.Status.FAILED : OptimizationRun.Status.COMPLETED);
        run.setErrorMessage(p.sql() == null ? "No ```sql block found in the response" : null);
        run.setCompletedAt(LocalDateTime.now());
        if (p.sql() != null) {
            // every agent answer becomes a tuning iteration to be tested and compared
            TuningTracker t = trackerService.applyWorkflow(run.getGroupId(), null, null);
            if (iterations.byRun(run.getId()).isEmpty()) {
                iterations.newIteration(t.getId(), TuningIteration.Source.AI_AGENT, run.getId(), p.sql(), p.narrative());
            }
        }
    }

    @Transactional
    public OptimizationRun setOutcome(Long groupId, Long runId, OptimizationRun.Outcome outcome) {
        OptimizationRun run = run(groupId, runId);
        run.setOutcome(outcome);
        return run;
    }

    @Transactional(readOnly = true)
    public List<OptimizationRun> runs(Long groupId) {
        return runs.findByGroupIdOrderByIdDesc(groupId);
    }

    // ------------------------------------------------------------------ feedback (step 12)

    /**
     * @param iterationId     the tuning iteration the decision is about (defaults to the selected one)
     * @param rejectionReason lookup REJECTION_REASON; required (or comments) for REJECTED
     */
    public record FeedbackRequest(Long optimizationRunId, Long iterationId, Feedback.SourceRole sourceRole,
                                  Feedback.Decision decision, String rejectionReason, String comments) {
    }

    @Transactional
    public Feedback addFeedback(Long groupId, FeedbackRequest req) {
        group(groupId);
        if (req.sourceRole() == null || req.decision() == null) {
            throw new IllegalArgumentException("sourceRole and decision are required");
        }
        if (req.decision() == Feedback.Decision.REJECTED && Texts.isBlank(req.comments())
                && Texts.isBlank(req.rejectionReason())) {
            throw new IllegalArgumentException("Please give a reason for the rejection");
        }
        lookups.validate("REJECTION_REASON", "rejection reason", req.rejectionReason());
        TuningTracker t = trackerService.applyWorkflow(groupId, null, null);
        Long iterationId = req.iterationId() != null ? req.iterationId() : t.getSelectedIterationId();

        Feedback f = new Feedback();
        f.setGroupId(groupId);
        f.setOptimizationRunId(req.optimizationRunId());
        f.setIterationId(iterationId);
        f.setSourceRole(req.sourceRole());
        f.setDecision(req.decision());
        f.setRejectionReason(req.decision() == Feedback.Decision.REJECTED ? Texts.trimToNull(req.rejectionReason()) : null);
        f.setComments(req.comments());
        f.setCreatedAt(LocalDateTime.now());
        f.setCreatedBy(CurrentUser.name());
        feedback.save(f);

        String reason = Texts.isBlank(req.rejectionReason()) ? req.comments() : req.rejectionReason();
        switch (req.decision()) {
            case ADOPTED -> {
                if (iterationId != null) {
                    iterations.adopt(t.getId(), iterationId);
                } else {
                    trackerService.changeStatus(t, WorkflowStatus.ADOPTED);
                }
            }
            case REJECTED -> {
                if (iterationId != null) {
                    // the tested optimization was rejected: record it and go back to tuning
                    iterations.reject(t.getId(), iterationId, reason);
                } else {
                    trackerService.changeStatus(t, WorkflowStatus.REJECTED);
                }
            }
            default -> {
            }
        }
        if (req.optimizationRunId() != null && req.decision() != Feedback.Decision.COMMENT) {
            setOutcome(groupId, req.optimizationRunId(), req.decision() == Feedback.Decision.ADOPTED
                    ? OptimizationRun.Outcome.ACCEPTED : OptimizationRun.Outcome.REJECTED);
        }
        return f;
    }

    @Transactional(readOnly = true)
    public List<Feedback> feedback(Long groupId) {
        return feedback.findByGroupIdOrderByIdDesc(groupId);
    }

    // ------------------------------------------------------------------ helpers

    private QueryGroup group(Long id) {
        return groups.findById(id).orElseThrow(() -> new NotFoundException("Query group", id));
    }

    private OptimizationRun run(Long groupId, Long runId) {
        return runs.findById(runId).filter(r -> r.getGroupId().equals(groupId))
                .orElseThrow(() -> new NotFoundException("Optimization run", runId));
    }
}
