package com.techcoder.sqlperf.tracker;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import com.techcoder.sqlperf.audit.AuditEvent;
import com.techcoder.sqlperf.audit.AuditService;
import com.techcoder.sqlperf.common.PageResponse;
import com.techcoder.sqlperf.common.Paging;
import com.techcoder.sqlperf.common.Specs;
import com.techcoder.sqlperf.common.Texts;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Tracking screen (top drill level). */
@RestController
@RequestMapping("/api/tracker")
public class TrackerController {

    private static final Set<String> SORTABLE = Set.of("id", "groupId", "workflowStatus", "priority", "theme",
            "devTeamLead", "devTeamStatus", "clouderaTeamLead", "smeTeamLead", "optimizedSqlStatus",
            "ogRunDurationMinutes", "postRunDurationMinutes", "updatedAt", "createdAt", "requestSource", "adoptedAt",
            "stageChangedAt", "environment", "requestedBy",
            "group.groupSize", "group.distinctUsers", "group.avgDurationMinutes", "group.maxDurationMinutes",
            "group.minDurationMinutes", "group.totalDurationMinutes");
    private static final int EXPORT_LIMIT = 100_000;

    private final TrackerService service;
    private final TrackerExcelExporter exporter;
    private final AuditService audit;

    public TrackerController(TrackerService service, TrackerExcelExporter exporter, AuditService audit) {
        this.service = service;
        this.exporter = exporter;
        this.audit = audit;
    }

    @GetMapping
    public PageResponse<TrackerDto> search(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) List<TuningTracker.WorkflowStatus> status,
            @RequestParam(required = false) TuningTracker.Priority priority,
            @RequestParam(required = false) String theme,
            @RequestParam(required = false) String lead,
            @RequestParam(required = false) Long groupId,
            @RequestParam(required = false) TuningTracker.RequestSource requestSource) {
        var spec = Specs.all(spec(q, status, priority, theme, lead, groupId), Specs.eq("requestSource", requestSource));
        var pageable = Paging.of(page, size, sort, SORTABLE,
                Sort.by(Sort.Direction.DESC, "group.totalDurationMinutes").and(Sort.by("id")));
        var result = service.search(spec, pageable);
        return new PageResponse<>(result.getContent(), result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    @GetMapping("/{id}")
    public TrackerDto get(@PathVariable Long id) {
        return service.get(id);
    }

    /** Stage-by-stage journey of one SQL until adoption. */
    @GetMapping("/{id}/journey")
    public TrackerService.Journey journey(@PathVariable Long id) {
        return service.journey(id);
    }

    /** Pipeline board: every tracker item as a compact card (grouped by stage in the UI). */
    @GetMapping("/board")
    public List<TrackerService.BoardCard> board(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) TuningTracker.Priority priority,
            @RequestParam(required = false) String theme,
            @RequestParam(required = false) String lead,
            @RequestParam(required = false) TuningTracker.RequestSource requestSource) {
        return service.board(Specs.all(spec(q, null, priority, theme, lead, null),
                Specs.eq("requestSource", requestSource)));
    }

    /** New proactive (UAT) or user-requested tuning item from a pasted SQL. */
    @PostMapping("/requests")
    public TrackerDto request(@RequestBody TrackerService.TuningRequest req) {
        return service.createRequest(req);
    }

    public record CreateRequest(@NotEmpty List<Long> groupIds) {
    }

    /** Adds one or more groups to the tracker (drill-up from the grouping screen). */
    @PostMapping
    public List<TrackerDto> create(@Valid @RequestBody CreateRequest req) {
        return service.createForGroups(req.groupIds());
    }

    @PutMapping("/{id}")
    public TrackerDto update(@PathVariable Long id, @Valid @RequestBody TrackerUpdateRequest req) {
        return service.update(id, req);
    }

    @GetMapping("/{id}/history")
    public List<AuditEvent> history(@PathVariable Long id) {
        return audit.history(TrackerService.AUDIT_TYPE, id);
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) List<TuningTracker.WorkflowStatus> status,
            @RequestParam(required = false) TuningTracker.Priority priority,
            @RequestParam(required = false) String theme,
            @RequestParam(required = false) String lead) throws IOException {
        List<TrackerDto> rows = service.searchAll(spec(q, status, priority, theme, lead, null),
                Sort.by(Sort.Direction.DESC, "group.totalDurationMinutes").and(Sort.by("id")));
        if (rows.size() > EXPORT_LIMIT) {
            throw new IllegalArgumentException("Export limited to " + EXPORT_LIMIT + " rows; narrow the filter");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        exporter.write(rows, out);
        String name = "sql-tuning-tracker-" + LocalDate.now() + ".xlsx";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(out.toByteArray());
    }

    private static Specification<TuningTracker> spec(String q, List<TuningTracker.WorkflowStatus> status,
                                                     TuningTracker.Priority priority, String theme, String lead,
                                                     Long groupId) {
        Specification<TuningTracker> text = Texts.isBlank(q) ? null : (root, cq, cb) -> {
            String p = Specs.containsPattern(q);
            return cb.or(cb.like(Specs.lower(cb, root.get("group").get("sampleQuery")), p, '\\'),
                    cb.like(Specs.lower(cb, root.get("problem")), p, '\\'),
                    cb.like(Specs.lower(cb, root.get("theme")), p, '\\'),
                    cb.like(Specs.lower(cb, root.get("group").get("fingerprint")), p, '\\'));
        };
        Specification<TuningTracker> leadSpec = Texts.isBlank(lead) ? null : (root, cq, cb) -> {
            String p = Specs.containsPattern(lead);
            return cb.or(cb.like(Specs.lower(cb, root.get("devTeamLead")), p, '\\'),
                    cb.like(Specs.lower(cb, root.get("clouderaTeamLead")), p, '\\'),
                    cb.like(Specs.lower(cb, root.get("smeTeamLead")), p, '\\'));
        };
        Specification<TuningTracker> statusSpec = status == null || status.isEmpty() ? null
                : (root, cq, cb) -> root.get("workflowStatus").in(status);
        return Specs.all(text, leadSpec, statusSpec, Specs.eq("priority", priority), Specs.eq("theme", Texts.trimToNull(theme)),
                Specs.eq("groupId", groupId));
    }
}
