package com.techcoder.sqlperf.tracker;

import java.lang.reflect.RecordComponent;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.techcoder.sqlperf.audit.AuditService;
import com.techcoder.sqlperf.common.CurrentUser;
import com.techcoder.sqlperf.common.NotFoundException;
import com.techcoder.sqlperf.customfield.CustomField.EntityType;
import com.techcoder.sqlperf.customfield.CustomFieldService;
import com.techcoder.sqlperf.fingerprint.SqlFingerprinter;
import com.techcoder.sqlperf.fingerprint.SqlPrettyPrinter;
import com.techcoder.sqlperf.group.QueryGroup;
import com.techcoder.sqlperf.group.QueryGroupRepository;
import com.techcoder.sqlperf.log.QueryLogRepository;
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
    private static final Set<String> NOT_COPIED = Set.of("version", "customFields");

    private final TuningTrackerRepository trackers;
    private final QueryGroupRepository groups;
    private final QueryLogRepository logs;
    private final CustomFieldService customFields;
    private final AuditService audit;
    private final SqlPrettyPrinter pretty;
    private final SqlFingerprinter fingerprinter;

    public TrackerService(TuningTrackerRepository trackers, QueryGroupRepository groups, QueryLogRepository logs,
                          CustomFieldService customFields, AuditService audit, SqlPrettyPrinter pretty,
                          SqlFingerprinter fingerprinter) {
        this.trackers = trackers;
        this.groups = groups;
        this.logs = logs;
        this.customFields = customFields;
        this.audit = audit;
        this.pretty = pretty;
        this.fingerprinter = fingerprinter;
    }

    @Transactional(readOnly = true)
    public Page<TrackerDto> search(Specification<TuningTracker> spec, Pageable pageable) {
        Page<TuningTracker> page = trackers.findAll(spec, pageable);
        Map<Long, Map<String, String>> custom = customFields.valuesFor(EntityType.TRACKER,
                page.getContent().stream().map(TuningTracker::getId).toList());
        return page.map(t -> TrackerDto.of(t, t.getGroup(), custom.get(t.getId())));
    }

    @Transactional(readOnly = true)
    public List<TrackerDto> searchAll(Specification<TuningTracker> spec, Sort sort) {
        List<TuningTracker> all = trackers.findAll(spec, sort);
        Map<Long, Map<String, String>> custom = customFields.valuesFor(EntityType.TRACKER,
                all.stream().map(TuningTracker::getId).toList());
        return all.stream().map(t -> TrackerDto.of(t, t.getGroup(), custom.get(t.getId()))).toList();
    }

    @Transactional(readOnly = true)
    public TrackerDto get(Long id) {
        TuningTracker t = trackers.findById(id).orElseThrow(() -> new NotFoundException("Tracker", id));
        return dto(t);
    }

    /** Puts groups on the tracking screen (idempotent: an existing tracker is returned unchanged). */
    @Transactional
    public List<TrackerDto> createForGroups(Collection<Long> groupIds) {
        List<TrackerDto> out = new ArrayList<>();
        for (Long groupId : groupIds) {
            TuningTracker t = trackers.findByGroupId(groupId).orElseGet(() -> create(groupId));
            out.add(dto(t));
        }
        return out;
    }

    private TuningTracker create(Long groupId) {
        QueryGroup g = groups.findById(groupId).orElseThrow(() -> new NotFoundException("Query group", groupId));
        TuningTracker t = new TuningTracker();
        t.setGroupId(groupId);
        t.setGroup(g);
        t.setSampleQueryFormatted(pretty.format(g.getSampleQuery()));
        t.setCleansedQuery(fingerprinter.cleanse(g.getSampleQuery()));
        if (g.getSampleLogId() != null) {
            logs.findById(g.getSampleLogId()).ifPresent(l -> t.setOgRunDurationMinutes(l.getDurationMinutes()));
        }
        t.setCreatedAt(LocalDateTime.now());
        t.setCreatedBy(CurrentUser.name());
        TuningTracker saved = trackers.save(t);
        audit.created(AUDIT_TYPE, saved.getId());
        return saved;
    }

    @Transactional
    public TrackerDto update(Long id, TrackerUpdateRequest req) {
        TuningTracker t = trackers.findById(id).orElseThrow(() -> new NotFoundException("Tracker", id));
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
            if (rc.getName().equals("workflowStatus") || rc.getName().equals("priority")) {
                if (newValue == null) {
                    continue; // mandatory columns
                }
            }
            if (!Objects.equals(oldValue, newValue)) {
                audit.changed(AUDIT_TYPE, id, rc.getName(), oldValue, newValue);
                target.setPropertyValue(rc.getName(), newValue);
            }
        }
        deriveTeardownPct(t);
        t.setUpdatedAt(LocalDateTime.now());
        t.setUpdatedBy(CurrentUser.name());
        customFields.saveValues(EntityType.TRACKER, id, req.customFields());
        trackers.flush();
        return dto(t);
    }

    /** Called by the workflow when the agent output is accepted / a status changes. */
    @Transactional
    public void applyWorkflow(Long groupId, TuningTracker.WorkflowStatus status, String optimizedSql) {
        TuningTracker t = trackers.findByGroupId(groupId).orElseGet(() -> create(groupId));
        if (optimizedSql != null && !Objects.equals(t.getOptimizedQuery(), optimizedSql)) {
            audit.changed(AUDIT_TYPE, t.getId(), "optimizedQuery", t.getOptimizedQuery(), optimizedSql);
            t.setOptimizedQuery(optimizedSql);
        }
        if (status != null && status != t.getWorkflowStatus()) {
            audit.changed(AUDIT_TYPE, t.getId(), "workflowStatus", t.getWorkflowStatus(), status);
            t.setWorkflowStatus(status);
        }
        t.setUpdatedAt(LocalDateTime.now());
        t.setUpdatedBy(CurrentUser.name());
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

    private TrackerDto dto(TuningTracker t) {
        QueryGroup g = t.getGroup() != null ? t.getGroup()
                : groups.findById(t.getGroupId()).orElseThrow(() -> new NotFoundException("Query group", t.getGroupId()));
        return TrackerDto.of(t, g, customFields.valuesFor(EntityType.TRACKER, List.of(t.getId())).get(t.getId()));
    }

    private static Object read(RecordComponent rc, Object record) {
        try {
            return rc.getAccessor().invoke(record);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
