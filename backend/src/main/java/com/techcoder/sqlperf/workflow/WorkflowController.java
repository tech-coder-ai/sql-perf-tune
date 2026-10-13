package com.techcoder.sqlperf.workflow;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Per-group workflow artefacts (diagram steps 4-12). */
@RestController
@RequestMapping("/api/groups/{groupId}")
public class WorkflowController {

    private final WorkflowService service;

    public WorkflowController(WorkflowService service) {
        this.service = service;
    }

    @GetMapping("/diagnostics")
    public List<SqlDiagnostic> diagnostics(@PathVariable Long groupId) {
        return service.diagnostics(groupId);
    }

    /** Store SQL Diagnostic Tool output (explain, profile, exec summary). The profile is parsed automatically. */
    @PostMapping("/diagnostics")
    @ResponseStatus(HttpStatus.CREATED)
    public SqlDiagnostic capture(@PathVariable Long groupId, @RequestBody WorkflowService.DiagnosticRequest req) {
        return service.captureDiagnostic(groupId, req);
    }

    @GetMapping("/ddls")
    public List<TableDdl> ddls(@PathVariable Long groupId) {
        return service.ddls(groupId);
    }

    @PostMapping("/ddls")
    @ResponseStatus(HttpStatus.CREATED)
    public TableDdl addDdl(@PathVariable Long groupId, @RequestBody WorkflowService.DdlRequest req) {
        return service.addDdl(groupId, req);
    }

    @DeleteMapping("/ddls/{ddlId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDdl(@PathVariable Long groupId, @PathVariable Long ddlId) {
        service.deleteDdl(groupId, ddlId);
    }

    @GetMapping("/optimization-runs")
    public List<OptimizationRun> runs(@PathVariable Long groupId) {
        return service.runs(groupId);
    }

    /** The rendered prompt for a template (default: the active one) without starting a run. */
    @GetMapping("/prompt-preview")
    public WorkflowService.PromptPreview promptPreview(@PathVariable Long groupId,
                                                       @RequestParam(required = false) Long promptTemplateId) {
        return service.previewPrompt(groupId, promptTemplateId);
    }

    public record StartRunRequest(Long promptTemplateId) {
    }

    /** Renders the prompt from captured artefacts and invokes the configured agent. */
    @PostMapping("/optimization-runs")
    @ResponseStatus(HttpStatus.CREATED)
    public OptimizationRun start(@PathVariable Long groupId, @RequestBody(required = false) StartRunRequest req) {
        return service.startOptimization(groupId, req == null ? null : req.promptTemplateId());
    }

    public record ResponseRequest(String responseRaw, String modelName) {
    }

    /** Paste the LLM answer for a PENDING (manual) run. */
    @PostMapping("/optimization-runs/{runId}/response")
    public OptimizationRun response(@PathVariable Long groupId, @PathVariable Long runId,
                                    @RequestBody ResponseRequest req) {
        return service.submitResponse(groupId, runId, req.responseRaw(), req.modelName());
    }

    public record OutcomeRequest(OptimizationRun.Outcome outcome) {
    }

    @PostMapping("/optimization-runs/{runId}/outcome")
    public OptimizationRun outcome(@PathVariable Long groupId, @PathVariable Long runId,
                                   @RequestBody OutcomeRequest req) {
        return service.setOutcome(groupId, runId, req.outcome());
    }

    @GetMapping("/feedback")
    public List<Feedback> feedback(@PathVariable Long groupId) {
        return service.feedback(groupId);
    }

    @PostMapping("/feedback")
    @ResponseStatus(HttpStatus.CREATED)
    public Feedback addFeedback(@PathVariable Long groupId, @RequestBody WorkflowService.FeedbackRequest req) {
        return service.addFeedback(groupId, req);
    }
}
