package com.techcoder.sqlperf.tracker;

import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.techcoder.sqlperf.audit.AuditEvent;
import com.techcoder.sqlperf.audit.AuditService;
import com.techcoder.sqlperf.common.CurrentUser;
import com.techcoder.sqlperf.common.NotFoundException;
import com.techcoder.sqlperf.common.SqlEngine;
import com.techcoder.sqlperf.common.Texts;
import com.techcoder.sqlperf.customfield.CustomField.EntityType;
import com.techcoder.sqlperf.customfield.CustomFieldService;
import com.techcoder.sqlperf.fingerprint.SqlFingerprinter;
import com.techcoder.sqlperf.fingerprint.SqlPrettyPrinter;
import com.techcoder.sqlperf.group.QueryGroup;
import com.techcoder.sqlperf.group.QueryGroupRepository;
import com.techcoder.sqlperf.iteration.TuningIterationRepository;
import com.techcoder.sqlperf.log.QueryLogRepository;
import com.techcoder.sqlperf.lookup.LookupService;
import com.techcoder.sqlperf.tracker.TuningTracker.RequestSource;
import com.techcoder.sqlperf.tracker.TuningTracker.WorkflowStatus;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TrackerService {

    static final String AUDIT_TYPE = "TRACKER";
    private static final Set<String> NOT_COPIED = Set.of("version", "customFields", "workflowStatus");

    /** The happy path a SQL travels until it is adopted (ON_HOLD / REJECTED are side exits). */
    public static final List<WorkflowStatus> STAGES = List.of(
            WorkflowStatus.NEW, WorkflowStatus.DIAGNOSTICS_CAPTURED, WorkflowStatus.OPTIMIZATION_REQUESTED,
            WorkflowStatus.OPTIMIZED, WorkflowStatus.POST_RUN_VALIDATED, WorkflowStatus.SME_VALIDATION,
            WorkflowStatus.ADOPTED);

    private final TuningTrackerRepository trackers;
    private final QueryGroupRepository groups;
    private final QueryLogRepository logs;
    private final TuningIterationRepository iterations;
    private final CustomFieldService customFields;
    private final LookupService lookups;
    private final AuditService audit;
    private final SqlPrettyPrinter pretty;
    private final SqlFingerprinter fingerprinter;

    public TrackerService(TuningTrackerRepository trackers, QueryGroupRepository groups, QueryLogRepository logs,
                          TuningIterationRepository iterations, CustomFieldService customFields, LookupService lookups,
                          AuditService audit, SqlPrettyPrinter pretty, SqlFingerprinter fingerprinter) {
        this.trackers = trackers;
        this.groups = groups;
        this.logs = logs;
        this.iterations = iterations;
        this.customFields = customFields;
        this.lookups = lookups;
        this.audit = audit;
        this.pretty = pretty;
        this.fingerprinter = fingerprinter;
    }

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public Page<TrackerDto> search(Specification<TuningTracker> spec, Pageable pageable) {
        Page<TuningTracker> page = trackers.findAll(spec, pageable);
        List<Long> ids = page.getContent().stream().map(TuningTracker::getId).toList();
        Map<Long, Map<String, String>> custom = customFields.valuesFor(EntityType.TRACKER, ids);
        Map<Long, Long> counts = iterationCounts(ids);
        return page.map(t -> TrackerDto.of(t, t.getGroup(), custom.get(t.getId()), counts.getOrDefault(t.getId(), 0L).intValue()));
    }

    @Transactional(readOnly = true)
    public List<TrackerDto> searchAll(Specification<TuningTracker> spec, Sort sort) {
        List<TuningTracker> all = trackers.findAll(spec, sort);
        List<Long> ids = all.stream().map(TuningTracker::getId).toList();
        Map<Long, Map<String, String>> custom = customFields.valuesFor(EntityType.TRACKER, ids);
        Map<Long, Long> counts = iterationCounts(ids);
        return all.stream()
                .map(t -> TrackerDto.of(t, t.getGroup(), custom.get(t.getId()), counts.getOrDefault(t.getId(), 0L).intValue()))
                .toList();
    }

    private Map<Long, Long> iterationCounts(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return iterations.countByTrackerIds(ids).stream()
                .collect(Collectors.toMap(TuningIterationRepository.TrackerCount::getTrackerId,
                        TuningIterationRepository.TrackerCount::getCount));
    }

    @Transactional(readOnly = true)
    public TrackerDto get(Long id) {
        return dto(find(id));
    }

    /** Compact cards for the pipeline board. */
    public record BoardCard(Long trackerId, Long groupId, WorkflowStatus status, TuningTracker.Priority priority,
                            String theme, String devTeamLead, RequestSource requestSource, String sqlSnippet,
                            int groupSize, Double totalDurationMinutes, Integer daysInStage, Integer daysOpen,
                            int iterationCount, Double improvementPct, LocalDateTime adoptedAt) {
    }

    @Transactional(readOnly = true)
    public List<BoardCard> board(Specification<TuningTracker> spec) {
        return searchAll(spec, Sort.by(Sort.Direction.DESC, "group.totalDurationMinutes")).stream()
                .map(d -> new BoardCard(d.trackerId(), d.groupId(), d.workflowStatus(), d.priority(), d.theme(),
                        d.devTeamLead(), d.requestSource(), Texts.truncate(oneLine(d.sampleQueryRaw()), 180),
                        d.groupSize(), d.totalDurationMinutes(), d.daysInStage(), d.daysOpen(), d.iterationCount(),
                        d.improvementPct(), d.adoptedAt()))
                .toList();
    }

    private static String oneLine(String s) {
        return s == null ? null : s.replaceAll("\\s+", " ").trim();
    }

    // ------------------------------------------------------------------ journey

    public record JourneyStep(WorkflowStatus stage, String state, LocalDateTime enteredAt, Double daysInStage) {
    }

    public record Journey(Long trackerId, LocalDateTime detectedAt, RequestSource requestSource,
                          WorkflowStatus current, LocalDateTime currentSince, List<JourneyStep> steps,
                          Double totalDays, Double daysSinceDetected) {
    }

    /**
     * Where a SQL is on its way to adoption. Stage entry times come from the field-level audit trail;
     * stages that were skipped are shown as such.
     */
    @Transactional(readOnly = true)
    public Journey journey(Long id) {
        TuningTracker t = find(id);
        QueryGroup g = t.getGroup();
        Map<WorkflowStatus, LocalDateTime> entered = new EnumMap<>(WorkflowStatus.class);
        entered.put(WorkflowStatus.NEW, t.getCreatedAt());
        List<AuditEvent> events = new ArrayList<>(audit.history(AUDIT_TYPE, id));
        events.sort((a, b) -> a.getChangedAt().compareTo(b.getChangedAt()));
        for (AuditEvent e : events) {
            if ("workflowStatus".equals(e.getFieldName()) && e.getNewValue() != null) {
                try {
                    entered.put(WorkflowStatus.valueOf(e.getNewValue()), e.getChangedAt());
                } catch (IllegalArgumentException ignored) {
                    // unknown legacy value
                }
            }
        }
        WorkflowStatus current = t.getWorkflowStatus();
        int currentIdx = STAGES.indexOf(current);
        if (currentIdx < 0) {
            // ON_HOLD / REJECTED: progress is the furthest happy-path stage reached
            for (int i = STAGES.size() - 1; i >= 0; i--) {
                if (entered.containsKey(STAGES.get(i)) && STAGES.get(i) != WorkflowStatus.ADOPTED) {
                    currentIdx = i;
                    break;
                }
            }
        }
        LocalDateTime now = LocalDateTime.now();
        List<JourneyStep> steps = new ArrayList<>();
        for (int i = 0; i < STAGES.size(); i++) {
            WorkflowStatus s = STAGES.get(i);
            LocalDateTime at = entered.get(s);
            String state;
            if (s == current) {
                state = current == WorkflowStatus.ADOPTED ? "done" : "current";
            } else if (i < currentIdx || (currentIdx < 0 && at != null)) {
                state = at == null ? "skipped" : "done";
            } else if (i == currentIdx) {
                state = "stopped"; // furthest stage before ON_HOLD / REJECTED
            } else {
                state = "pending";
            }
            Double days = null;
            if (at != null && !"pending".equals(state) && !"skipped".equals(state)) {
                LocalDateTime next = null;
                for (int j = i + 1; j < STAGES.size() && next == null; j++) {
                    next = entered.get(STAGES.get(j));
                }
                LocalDateTime end = "current".equals(state) ? now : next != null ? next
                        : t.getClosedAt() != null ? t.getClosedAt() : now;
                days = round1(Math.max(0, Duration.between(at, end).toMinutes()) / 1440d);
            }
            steps.add(new JourneyStep(s, state, at, days));
        }
        LocalDateTime detected = g.getFirstSeen() != null && g.getFirstSeen().isBefore(t.getCreatedAt())
                ? g.getFirstSeen() : t.getCreatedAt();
        LocalDateTime end = t.getClosedAt() != null ? t.getClosedAt() : now;
        return new Journey(id, detected, t.getRequestSource(), current, t.getStageChangedAt(), steps,
                round1(Duration.between(t.getCreatedAt(), end).toMinutes() / 1440d),
                round1(Duration.between(detected, end).toMinutes() / 1440d));
    }

    private static Double round1(double v) {
        return Math.round(v * 10d) / 10d;
    }

    // ------------------------------------------------------------------ create

    /** Puts groups on the tracking screen (idempotent: an existing tracker is returned unchanged). */
    @Transactional
    public List<TrackerDto> createForGroups(Collection<Long> groupIds) {
        List<TrackerDto> out = new ArrayList<>();
        for (Long groupId : groupIds) {
            TuningTracker t = trackers.findByGroupId(groupId).orElseGet(() -> create(groupId, RequestSource.LOG_DETECTED));
            out.add(dto(t));
        }
        return out;
    }

    public record TuningRequest(String sql, RequestSource requestSource, String requestedBy, String environment,
                                TuningTracker.Priority priority, String theme, String problem, String sqlEngine) {
    }

    /**
     * Proactive (e.g. UAT) or user-requested tuning of a SQL that may never have appeared in the logs. The SQL
     * is fingerprinted like log rows, so it joins an existing group when the pattern is already known.
     */
    @Transactional
    public TrackerDto createRequest(TuningRequest r) {
        if (Texts.isBlank(r.sql())) {
            throw new IllegalArgumentException("SQL is required");
        }
        String engine = Texts.isBlank(r.sqlEngine()) ? "IMPALA" : r.sqlEngine().trim().toUpperCase(Locale.ROOT);
        SqlEngine.valueOf(engine);
        lookups.validate("ENVIRONMENT", "environment", r.environment());
        lookups.validate("THEME", "theme", r.theme());
        SqlFingerprinter.Result fp = fingerprinter.fingerprint(r.sql());
        QueryGroup g = groups.findBySqlEngineAndFingerprint(engine, fp.fingerprint()).orElseGet(() -> {
            QueryGroup ng = new QueryGroup();
            ng.setSqlEngine(engine);
            ng.setFingerprint(fp.fingerprint());
            ng.setNormalizedQuery(fp.normalizedSql());
            ng.setSampleQuery(r.sql().trim());
            ng.setCreatedAt(LocalDateTime.now());
            ng.setUpdatedAt(LocalDateTime.now());
            return groups.save(ng);
        });
        RequestSource source = r.requestSource() == null ? RequestSource.USER_REQUEST : r.requestSource();
        TuningTracker t = trackers.findByGroupId(g.getId()).orElseGet(() -> create(g.getId(), source));
        if (t.getRequestedBy() == null) {
            t.setRequestedBy(Texts.trimToNull(r.requestedBy()));
        }
        if (t.getEnvironment() == null) {
            t.setEnvironment(Texts.trimToNull(r.environment()));
        }
        if (t.getTheme() == null) {
            t.setTheme(Texts.trimToNull(r.theme()));
        }
        if (t.getProblem() == null) {
            t.setProblem(Texts.trimToNull(r.problem()));
        }
        if (r.priority() != null) {
            t.setPriority(r.priority());
        }
        return dto(t);
    }

    private TuningTracker create(Long groupId, RequestSource source) {
        QueryGroup g = groups.findById(groupId).orElseThrow(() -> new NotFoundException("Query group", groupId));
        TuningTracker t = new TuningTracker();
        t.setGroupId(groupId);
        t.setGroup(g);
        t.setRequestSource(source);
        t.setSampleQueryFormatted(pretty.format(g.getSampleQuery()));
        t.setCleansedQuery(fingerprinter.cleanse(g.getSampleQuery()));
        if (g.getSampleLogId() != null) {
            logs.findById(g.getSampleLogId()).ifPresent(l -> t.setOgRunDurationMinutes(l.getDurationMinutes()));
        }
        LocalDateTime now = LocalDateTime.now();
        t.setCreatedAt(now);
        t.setStageChangedAt(now);
        t.setCreatedBy(CurrentUser.name());
        TuningTracker saved = trackers.save(t);
        audit.created(AUDIT_TYPE, saved.getId());
        return saved;
    }

    // ------------------------------------------------------------------ update

    @Transactional
    public TrackerDto update(Long id, TrackerUpdateRequest req) {
        TuningTracker t = find(id);
        if (!Objects.equals(req.version(), t.getVersion())) {
            throw new ObjectOptimisticLockingFailureException(TuningTracker.class, id);
        }
        BeanWrapper target = new BeanWrapperImpl(t);
        for (RecordComponent rc : TrackerUpdateRequest.class.getRecordComponents()) {
            if (NOT_COPIED.contains(rc.getName())) {
                continue;
            }
            Object newValue = read(rc, req);
            if (newValue instanceof String s && s.isBlank()) {
                newValue = null;
            }
            Object oldValue = target.getPropertyValue(rc.getName());
            if ((rc.getName().equals("priority") || rc.getName().equals("requestSource")) && newValue == null) {
                continue; // mandatory columns
            }
            String category = LookupService.TRACKER_FIELDS.get(rc.getName());
            if (category != null && !Objects.equals(oldValue, newValue)) {
                lookups.validate(category, rc.getName(), (String) newValue);
            }
            if (!Objects.equals(oldValue, newValue)) {
                audit.changed(AUDIT_TYPE, id, rc.getName(), oldValue, newValue);
                target.setPropertyValue(rc.getName(), newValue);
            }
        }
        if (req.workflowStatus() != null) {
            changeStatus(t, req.workflowStatus());
        }
        deriveTeardownPct(t);
        t.setUpdatedAt(LocalDateTime.now());
        t.setUpdatedBy(CurrentUser.name());
        customFields.saveValues(EntityType.TRACKER, id, req.customFields());
        trackers.flush();
        return dto(t);
    }

    /**
     * The only place the workflow status changes: audits it and maintains the stage / adoption / closure
     * timestamps used for turnaround reporting.
     */
    public void changeStatus(TuningTracker t, WorkflowStatus status) {
        if (status == null || status == t.getWorkflowStatus()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        audit.changed(AUDIT_TYPE, t.getId(), "workflowStatus", t.getWorkflowStatus(), status);
        t.setWorkflowStatus(status);
        t.setStageChangedAt(now);
        t.setAdoptedAt(status == WorkflowStatus.ADOPTED ? now : null);
        t.setClosedAt(status == WorkflowStatus.ADOPTED || status == WorkflowStatus.REJECTED ? now : null);
    }

    /** Workflow hook (diagnostics, agent output, feedback): creates the tracker on first use. */
    @Transactional
    public TuningTracker applyWorkflow(Long groupId, WorkflowStatus status, String optimizedSql) {
        TuningTracker t = trackers.findByGroupId(groupId).orElseGet(() -> create(groupId, RequestSource.LOG_DETECTED));
        if (optimizedSql != null && !Objects.equals(t.getOptimizedQuery(), optimizedSql)) {
            audit.changed(AUDIT_TYPE, t.getId(), "optimizedQuery", t.getOptimizedQuery(), optimizedSql);
            t.setOptimizedQuery(optimizedSql);
        }
        changeStatus(t, status);
        t.setUpdatedAt(LocalDateTime.now());
        t.setUpdatedBy(CurrentUser.name());
        return t;
    }

    /** Moves forward only (used by automatic workflow steps so they never pull an item backwards). */
    public void advanceTo(TuningTracker t, WorkflowStatus status) {
        int cur = STAGES.indexOf(t.getWorkflowStatus());
        int next = STAGES.indexOf(status);
        if (cur >= 0 && next > cur) {
            changeStatus(t, status);
        }
    }

    /** Teardown % = teardown seconds / run duration seconds, unless entered explicitly. */
    static void deriveTeardownPct(TuningTracker t) {
        if (t.getOgTeardownPct() == null) {
            t.setOgTeardownPct(pct(t.getOgTeardownTimeSeconds(), t.getOgRunDurationMinutes()));
        }
        if (t.getPostRunTeardownPct() == null) {
            t.setPostRunTeardownPct(pct(t.getPostRunTeardownTimeSeconds(), t.getPostRunDurationMinutes()));
        }
    }

    private static Double pct(Double teardownSeconds, Double runMinutes) {
        if (teardownSeconds == null || runMinutes == null || runMinutes <= 0) {
            return null;
        }
        return Math.round(teardownSeconds / (runMinutes * 60d) * 100_000d) / 1000d;
    }

    public TuningTracker find(Long id) {
        return trackers.findById(id).orElseThrow(() -> new NotFoundException("Tracker", id));
    }

    private TrackerDto dto(TuningTracker t) {
        QueryGroup g = t.getGroup() != null ? t.getGroup()
                : groups.findById(t.getGroupId()).orElseThrow(() -> new NotFoundException("Query group", t.getGroupId()));
        return TrackerDto.of(t, g, customFields.valuesFor(EntityType.TRACKER, List.of(t.getId())).get(t.getId()),
                (int) iterations.countByTrackerId(t.getId()));
    }

    private static Object read(RecordComponent rc, Object record) {
        try {
            return rc.getAccessor().invoke(record);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
