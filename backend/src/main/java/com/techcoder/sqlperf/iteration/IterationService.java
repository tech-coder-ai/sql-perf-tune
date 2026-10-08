package com.techcoder.sqlperf.iteration;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.techcoder.sqlperf.audit.AuditService;
import com.techcoder.sqlperf.common.CurrentUser;
import com.techcoder.sqlperf.common.NotFoundException;
import com.techcoder.sqlperf.common.Texts;
import com.techcoder.sqlperf.iteration.TuningIteration.Source;
import com.techcoder.sqlperf.iteration.TuningIteration.Status;
import com.techcoder.sqlperf.tracker.TrackerDto;
import com.techcoder.sqlperf.tracker.TrackerService;
import com.techcoder.sqlperf.tracker.TuningTracker;
import com.techcoder.sqlperf.tracker.TuningTracker.WorkflowStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tuning iterations of a tracker item. Each candidate SQL is tested (post-run metrics), the best one is
 * selected for adoption, and end users then adopt it (tracker ADOPTED) or reject it with a reason (tracker
 * goes back to tuning for the next iteration).
 */
@Service
public class IterationService {

    private static final Set<Status> COMPARABLE = Set.of(Status.TESTED, Status.SELECTED, Status.ADOPTED);

    private final TuningIterationRepository repo;
    private final TrackerService trackers;
    private final AuditService audit;

    public IterationService(TuningIterationRepository repo, TrackerService trackers, AuditService audit) {
        this.repo = repo;
        this.trackers = trackers;
        this.audit = audit;
    }

    /** Iteration plus comparison against the original (OG) run of its tracker item. */
    public record IterationDto(Long id, Long trackerId, int iterationNo, Source source, Long optimizationRunId,
                               String optimizedSql, String changeNarrative, Status status, Double runDurationMinutes,
                               Double executionTimeSeconds, Double teardownTimeSeconds, Double cpuSeconds,
                               Long rowsScanned, Long bytesScanned, Integer tablesScanned, Double peakMemoryMb,
                               Long resultRowCount, Boolean resultMatches, String notes, LocalDateTime testedAt,
                               String testedBy, LocalDateTime createdAt, String createdBy, int version,
                               boolean best, boolean selected, Double durationImprovementPct,
                               Double cpuImprovementPct, Double rowsScannedReductionPct, Integer tableScansAvoided) {
    }

    @Transactional(readOnly = true)
    public List<IterationDto> list(Long trackerId) {
        TuningTracker t = trackers.find(trackerId);
        List<TuningIteration> all = repo.findByTrackerIdOrderByIterationNoAsc(trackerId);
        Long bestId = best(all).map(TuningIteration::getId).orElse(null);
        return all.stream().map(i -> dto(i, t, bestId)).toList();
    }

    /** Best = fastest tested iteration whose results match the original (ties broken by CPU time). */
    public static Optional<TuningIteration> best(List<TuningIteration> all) {
        return all.stream()
                .filter(i -> COMPARABLE.contains(i.getStatus()))
                .filter(i -> !Boolean.FALSE.equals(i.getResultMatches()))
                .filter(i -> i.getRunDurationMinutes() != null)
                .min(Comparator.comparing(TuningIteration::getRunDurationMinutes)
                        .thenComparing(i -> i.getCpuSeconds() == null ? Double.MAX_VALUE : i.getCpuSeconds()));
    }

    public record IterationRequest(String optimizedSql, String changeNarrative, String notes) {
    }

    @Transactional
    public IterationDto create(Long trackerId, IterationRequest r) {
        TuningIteration i = newIteration(trackerId, Source.MANUAL, null, r.optimizedSql(), r.changeNarrative());
        i.setNotes(Texts.trimToNull(r.notes()));
        return dto(i, trackers.find(trackerId), null);
    }

    /** Called when the optimization agent returns SQL (or a post-run test arrives without an iteration). */
    @Transactional
    public TuningIteration newIteration(Long trackerId, Source source, Long runId, String sql, String narrative) {
        if (Texts.isBlank(sql)) {
            throw new IllegalArgumentException("The iteration needs the optimized SQL");
        }
        TuningTracker t = trackers.find(trackerId);
        int next = repo.findFirstByTrackerIdOrderByIterationNoDesc(trackerId).map(x -> x.getIterationNo() + 1).orElse(1);
        TuningIteration i = new TuningIteration();
        i.setTrackerId(trackerId);
        i.setIterationNo(next);
        i.setSource(source);
        i.setOptimizationRunId(runId);
        i.setOptimizedSql(sql.trim());
        i.setChangeNarrative(Texts.trimToNull(narrative));
        i.setStatus(Status.PROPOSED);
        i.setCreatedAt(LocalDateTime.now());
        i.setCreatedBy(CurrentUser.name());
        repo.save(i);
        audit.changed("TRACKER", trackerId, "iteration", null, "Iteration " + next + " proposed (" + source + ")");
        if (t.getWorkflowStatus() == WorkflowStatus.NEW || t.getWorkflowStatus() == WorkflowStatus.DIAGNOSTICS_CAPTURED
                || t.getWorkflowStatus() == WorkflowStatus.OPTIMIZATION_REQUESTED) {
            trackers.changeStatus(t, WorkflowStatus.OPTIMIZED);
        }
        touch(t);
        return i;
    }

    /** Test results of an iteration (entered by hand or from a post-run diagnostic). */
    public record TestResult(Integer version, String optimizedSql, String changeNarrative, String notes,
                             Status status, Double runDurationMinutes, Double executionTimeSeconds,
                             Double teardownTimeSeconds, Double cpuSeconds, Long rowsScanned, Long bytesScanned,
                             Integer tablesScanned, Double peakMemoryMb, Long resultRowCount, Boolean resultMatches) {
    }

    @Transactional
    public IterationDto update(Long trackerId, Long id, TestResult r) {
        TuningIteration i = find(trackerId, id);
        if (r.version() != null && r.version() != i.getVersion()) {
            throw new org.springframework.orm.ObjectOptimisticLockingFailureException(TuningIteration.class, id);
        }
        if (i.getStatus() == Status.ADOPTED) {
            throw new IllegalArgumentException("Iteration " + i.getIterationNo() + " is adopted and can no longer change");
        }
        if (!Texts.isBlank(r.optimizedSql())) {
            i.setOptimizedSql(r.optimizedSql().trim());
        }
        if (r.changeNarrative() != null) {
            i.setChangeNarrative(Texts.trimToNull(r.changeNarrative()));
        }
        if (r.notes() != null) {
            i.setNotes(Texts.trimToNull(r.notes()));
        }
        applyMetrics(i, r.runDurationMinutes(), r.executionTimeSeconds(), r.teardownTimeSeconds(), r.cpuSeconds(),
                r.rowsScanned(), r.bytesScanned(), r.tablesScanned(), r.peakMemoryMb(), r.resultRowCount(),
                r.resultMatches());
        if (r.status() != null && r.status() != i.getStatus()) {
            if (r.status() == Status.SELECTED || r.status() == Status.ADOPTED || r.status() == Status.REJECTED) {
                throw new IllegalArgumentException("Use select / adopt / reject for status " + r.status());
            }
            i.setStatus(r.status());
        } else if (i.getStatus() == Status.PROPOSED && i.getRunDurationMinutes() != null) {
            i.setStatus(Boolean.FALSE.equals(i.getResultMatches()) ? Status.FAILED : Status.TESTED);
        }
        markTested(i);
        TuningTracker t = trackers.find(trackerId);
        if (i.getStatus() == Status.TESTED) {
            trackers.advanceTo(t, WorkflowStatus.POST_RUN_VALIDATED);
        }
        if (i.getStatus() == Status.SELECTED) {
            copyToTracker(t, i);
        }
        touch(t);
        return dto(i, t, best(repo.findByTrackerIdOrderByIterationNoAsc(trackerId)).map(TuningIteration::getId).orElse(null));
    }

    /** Applies a post-run diagnostic to the iteration and marks it tested. */
    @Transactional
    public void recordTest(TuningIteration i, Double runDurationMinutes, Double executionSeconds, Double teardownSeconds,
                           Double cpuSeconds, Long rowsScanned, Long bytesScanned, Integer tablesScanned,
                           Double peakMemoryMb, Long resultRowCount, Long originalRowCount) {
        Boolean matches = resultRowCount != null && originalRowCount != null
                ? Objects.equals(resultRowCount, originalRowCount) : i.getResultMatches();
        applyMetrics(i, runDurationMinutes, executionSeconds, teardownSeconds, cpuSeconds, rowsScanned, bytesScanned,
                tablesScanned, peakMemoryMb, resultRowCount, matches);
        if (i.getStatus() == Status.PROPOSED || i.getStatus() == Status.TESTED || i.getStatus() == Status.FAILED) {
            i.setStatus(Boolean.FALSE.equals(i.getResultMatches()) ? Status.FAILED : Status.TESTED);
        }
        markTested(i);
        TuningTracker t = trackers.find(i.getTrackerId());
        if (i.getStatus() == Status.TESTED) {
            trackers.advanceTo(t, WorkflowStatus.POST_RUN_VALIDATED);
        }
        if (i.getStatus() == Status.SELECTED) {
            copyToTracker(t, i);
        }
        touch(t);
    }

    /** Chooses the iteration for adoption: its SQL and metrics become the tracker's optimized / post-run values. */
    @Transactional
    public IterationDto select(Long trackerId, Long id) {
        TuningIteration i = find(trackerId, id);
        if (i.getStatus() == Status.FAILED || i.getStatus() == Status.REJECTED) {
            throw new IllegalArgumentException("Iteration " + i.getIterationNo() + " is " + i.getStatus()
                    + " and cannot be selected");
        }
        List<TuningIteration> all = repo.findByTrackerIdOrderByIterationNoAsc(trackerId);
        for (TuningIteration other : all) {
            if (!other.getId().equals(id) && other.getStatus() == Status.SELECTED) {
                other.setStatus(other.getRunDurationMinutes() != null ? Status.TESTED : Status.PROPOSED);
            }
        }
        i.setStatus(Status.SELECTED);
        TuningTracker t = trackers.find(trackerId);
        copyToTracker(t, i);
        audit.changed("TRACKER", trackerId, "selectedIteration", null, "Iteration " + i.getIterationNo());
        if (t.getWorkflowStatus() != WorkflowStatus.ADOPTED) {
            trackers.changeStatus(t, WorkflowStatus.SME_VALIDATION);
        }
        touch(t);
        return dto(i, t, best(all).map(TuningIteration::getId).orElse(null));
    }

    /** End users adopted the iteration: the item is done. */
    @Transactional
    public void adopt(Long trackerId, Long id) {
        TuningIteration i = find(trackerId, id);
        if (i.getStatus() != Status.SELECTED) {
            select(trackerId, id);
        }
        i.setStatus(Status.ADOPTED);
        TuningTracker t = trackers.find(trackerId);
        copyToTracker(t, i);
        trackers.changeStatus(t, WorkflowStatus.ADOPTED);
        touch(t);
    }

    /** End users rejected the iteration (e.g. inaccurate results): back to tuning for the next iteration. */
    @Transactional
    public void reject(Long trackerId, Long id, String reason) {
        TuningIteration i = find(trackerId, id);
        boolean wasSelected = i.getStatus() == Status.SELECTED || i.getStatus() == Status.ADOPTED;
        i.setStatus(Status.REJECTED);
        i.setNotes(Texts.isBlank(reason) ? i.getNotes()
                : (i.getNotes() == null ? "" : i.getNotes() + "\n") + "Rejected: " + reason);
        TuningTracker t = trackers.find(trackerId);
        if (wasSelected && Objects.equals(t.getSelectedIterationId(), id)) {
            t.setSelectedIterationId(null);
            trackers.changeStatus(t, WorkflowStatus.OPTIMIZATION_REQUESTED);
        }
        audit.changed("TRACKER", trackerId, "iteration", null,
                "Iteration " + i.getIterationNo() + " rejected" + (Texts.isBlank(reason) ? "" : ": " + reason));
        touch(t);
    }

    public Optional<TuningIteration> selectedOf(TuningTracker t) {
        return t.getSelectedIterationId() == null ? Optional.empty() : repo.findById(t.getSelectedIterationId());
    }

    public Optional<TuningIteration> byRun(Long runId) {
        return repo.findByOptimizationRunId(runId);
    }

    public Optional<TuningIteration> latest(Long trackerId) {
        return repo.findFirstByTrackerIdOrderByIterationNoDesc(trackerId);
    }

    public TuningIteration find(Long trackerId, Long id) {
        return repo.findById(id).filter(i -> i.getTrackerId().equals(trackerId))
                .orElseThrow(() -> new NotFoundException("Iteration", id));
    }

    // ------------------------------------------------------------------ helpers

    private static void applyMetrics(TuningIteration i, Double run, Double exec, Double teardown, Double cpu, Long rows,
                                     Long bytes, Integer tables, Double mem, Long resultRows, Boolean matches) {
        if (run != null) {
            i.setRunDurationMinutes(run);
        }
        if (exec != null) {
            i.setExecutionTimeSeconds(exec);
        }
        if (teardown != null) {
            i.setTeardownTimeSeconds(teardown);
        }
        if (cpu != null) {
            i.setCpuSeconds(cpu);
        }
        if (rows != null) {
            i.setRowsScanned(rows);
        }
        if (bytes != null) {
            i.setBytesScanned(bytes);
        }
        if (tables != null) {
            i.setTablesScanned(tables);
        }
        if (mem != null) {
            i.setPeakMemoryMb(mem);
        }
        if (resultRows != null) {
            i.setResultRowCount(resultRows);
        }
        if (matches != null) {
            i.setResultMatches(matches);
        }
    }

    private static void markTested(TuningIteration i) {
        i.setUpdatedAt(LocalDateTime.now());
        if (i.getRunDurationMinutes() != null && i.getTestedAt() == null) {
            i.setTestedAt(LocalDateTime.now());
            i.setTestedBy(CurrentUser.name());
        }
    }

    private void copyToTracker(TuningTracker t, TuningIteration i) {
        t.setSelectedIterationId(i.getId());
        if (!Objects.equals(t.getOptimizedQuery(), i.getOptimizedSql())) {
            audit.changed("TRACKER", t.getId(), "optimizedQuery", t.getOptimizedQuery(), i.getOptimizedSql());
            t.setOptimizedQuery(i.getOptimizedSql());
        }
        t.setPostRunDurationMinutes(i.getRunDurationMinutes());
        t.setPostRunExecutionTimeSeconds(i.getExecutionTimeSeconds());
        t.setPostRunTeardownTimeSeconds(i.getTeardownTimeSeconds());
        t.setPostRunCpuSeconds(i.getCpuSeconds());
        t.setPostRunRowsScanned(i.getRowsScanned());
        t.setPostRunBytesScanned(i.getBytesScanned());
        t.setPostRunTablesScanned(i.getTablesScanned());
        t.setPostRunPeakMemoryMb(i.getPeakMemoryMb());
        t.setPostRunTeardownPct(null);
        if (Texts.isBlank(t.getChanges())) {
            t.setChanges(i.getChangeNarrative());
        }
    }

    private static void touch(TuningTracker t) {
        t.setUpdatedAt(LocalDateTime.now());
        t.setUpdatedBy(CurrentUser.name());
    }

    private static IterationDto dto(TuningIteration i, TuningTracker t, Long bestId) {
        Integer avoided = i.getTablesScanned() != null && t.getOgTablesScanned() != null
                ? t.getOgTablesScanned() - i.getTablesScanned() : null;
        return new IterationDto(i.getId(), i.getTrackerId(), i.getIterationNo(), i.getSource(), i.getOptimizationRunId(),
                i.getOptimizedSql(), i.getChangeNarrative(), i.getStatus(), i.getRunDurationMinutes(),
                i.getExecutionTimeSeconds(), i.getTeardownTimeSeconds(), i.getCpuSeconds(), i.getRowsScanned(),
                i.getBytesScanned(), i.getTablesScanned(), i.getPeakMemoryMb(), i.getResultRowCount(),
                i.getResultMatches(), i.getNotes(), i.getTestedAt(), i.getTestedBy(), i.getCreatedAt(),
                i.getCreatedBy(), i.getVersion(), i.getId().equals(bestId),
                i.getId().equals(t.getSelectedIterationId()),
                TrackerDto.improvement(t.getOgRunDurationMinutes(), i.getRunDurationMinutes()),
                TrackerDto.improvement(t.getOgCpuSeconds(), i.getCpuSeconds()),
                reduction(t.getOgRowsScanned(), i.getRowsScanned()), avoided);
    }

    private static Double reduction(Long before, Long after) {
        return before == null || after == null ? null : TrackerDto.improvement(before.doubleValue(), after.doubleValue());
    }
}
