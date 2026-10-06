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

    public WorkflowService(QueryGroupRepository groups, SqlDiagnosticRepository diagnostics, TableDdlRepository ddls,
                           PromptTemplateRepository prompts, OptimizationRunRepository runs,
                           FeedbackRepository feedback, TuningTrackerRepository trackers, TrackerService trackerService,
                           ImpalaProfileParser profileParser, PromptRenderer renderer, List<OptimizationAgent> agents,
                           JsonMapper json) {
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
    }

    // ------------------------------------------------------------------ diagnostics (steps 4-6, 10)

    public record DiagnosticRequest(SqlDiagnostic.Phase phase, Long optimizationRunId, String queryId, String sqlText,
                                    Long rowCount, Double runDurationSeconds, String explainPlan, String profileRaw,
                                    String execSummary, SqlDiagnostic.Status status, String errorMessage) {
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
        d.setRunDurationSeconds(req.runDurationSeconds() != null ? req.runDurationSeconds() : parsed.totalSeconds());
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
            d.setProfileSummary(json.writerWithDefaultPrettyPrinter().writeValueAsString(compact));
        }
        d.setCapturedAt(LocalDateTime.now());
        d.setCapturedBy(CurrentUser.name());
        diagnostics.save(d);

        if (d.getStatus() == SqlDiagnostic.Status.CAPTURED) {
            applyMetricsToTracker(groupId, d, parsed);
        }
        return d;
    }

    private void applyMetricsToTracker(Long groupId, SqlDiagnostic d, ImpalaProfileParser.ParsedProfile p) {
        boolean original = d.getPhase() == SqlDiagnostic.Phase.ORIGINAL;
        trackerService.applyWorkflow(groupId,
                original ? null : WorkflowStatus.POST_RUN_VALIDATED, null);
        TuningTracker t = trackers.findByGroupId(groupId).orElseThrow();
        Double runMinutes = d.getRunDurationSeconds() == null ? null
                : Math.round(d.getRunDurationSeconds() / 60d * 10_000d) / 10_000d;
        if (original) {
            if (t.getWorkflowStatus() == WorkflowStatus.NEW) {
                trackerService.applyWorkflow(groupId, WorkflowStatus.DIAGNOSTICS_CAPTURED, null);
            }
            if (runMinutes != null) {
                t.setOgRunDurationMinutes(runMinutes);
            }
            setIfPresent(p.executionSeconds(), t::setOgExecutionTimeSeconds);
            setIfPresent(p.teardownSeconds(), t::setOgTeardownTimeSeconds);
            t.setOgTeardownPct(null);
        } else {
            if (runMinutes != null) {
                t.setPostRunDurationMinutes(runMinutes);
            }
            setIfPresent(p.executionSeconds(), t::setPostRunExecutionTimeSeconds);
            setIfPresent(p.teardownSeconds(), t::setPostRunTeardownTimeSeconds);
            t.setPostRunTeardownPct(null);
        }
        recomputePct(t);
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

    private static void setIfPresent(Double v, java.util.function.Consumer<Double> setter) {
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
            trackerService.applyWorkflow(run.getGroupId(), WorkflowStatus.OPTIMIZED, p.sql());
            TuningTracker t = trackers.findByGroupId(run.getGroupId()).orElseThrow();
            if (Texts.isBlank(t.getChanges())) {
                t.setChanges(p.narrative());
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

    public record FeedbackRequest(Long optimizationRunId, Feedback.SourceRole sourceRole, Feedback.Decision decision,
                                  String comments) {
    }

    @Transactional
    public Feedback addFeedback(Long groupId, FeedbackRequest req) {
        group(groupId);
        if (req.sourceRole() == null || req.decision() == null) {
            throw new IllegalArgumentException("sourceRole and decision are required");
        }
        if (req.decision() == Feedback.Decision.REJECTED && Texts.isBlank(req.comments())) {
            throw new IllegalArgumentException("Please explain why the optimization was rejected");
        }
        Feedback f = new Feedback();
        f.setGroupId(groupId);
        f.setOptimizationRunId(req.optimizationRunId());
        f.setSourceRole(req.sourceRole());
        f.setDecision(req.decision());
        f.setComments(req.comments());
        f.setCreatedAt(LocalDateTime.now());
        f.setCreatedBy(CurrentUser.name());
        feedback.save(f);

        Map<Feedback.Decision, WorkflowStatus> next = Map.of(
                Feedback.Decision.ADOPTED, WorkflowStatus.ADOPTED,
                Feedback.Decision.REJECTED, WorkflowStatus.REJECTED);
        if (next.containsKey(req.decision())) {
            trackerService.applyWorkflow(groupId, next.get(req.decision()), null);
            if (req.optimizationRunId() != null) {
                setOutcome(groupId, req.optimizationRunId(), req.decision() == Feedback.Decision.ADOPTED
                        ? OptimizationRun.Outcome.ACCEPTED : OptimizationRun.Outcome.REJECTED);
            }
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
