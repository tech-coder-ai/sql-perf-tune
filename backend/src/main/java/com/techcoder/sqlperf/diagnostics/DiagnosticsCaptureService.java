package com.techcoder.sqlperf.diagnostics;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import com.techcoder.sqlperf.common.CurrentUser;
import com.techcoder.sqlperf.common.NotFoundException;
import com.techcoder.sqlperf.common.Texts;
import com.techcoder.sqlperf.config.SptProperties;
import com.techcoder.sqlperf.group.QueryGroup;
import com.techcoder.sqlperf.group.QueryGroupRepository;
import com.techcoder.sqlperf.tracker.TrackerService;
import com.techcoder.sqlperf.tracker.TuningTracker;
import com.techcoder.sqlperf.tracker.TuningTracker.CaptureStatus;
import com.techcoder.sqlperf.tracker.TuningTrackerRepository;
import com.techcoder.sqlperf.workflow.SqlDiagnostic;
import com.techcoder.sqlperf.workflow.SqlDiagnosticRepository;
import com.techcoder.sqlperf.workflow.WorkflowService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Step 5 without the manual work: as soon as a group is put on the tracker, fetch its explain plan, profile and
 * table DDL in the background and store them as if captured on the Diagnostics / DDL tabs. The original-run metrics
 * fill in and the item moves to "Diagnostics captured". The tracker's capture status tells the screens whether the
 * fetch is still in progress.
 *
 * <p>The fetch itself runs outside any transaction (it can take a while, and SQLite has a single connection);
 * only the bookkeeping and the final store are short transactions.
 */
@Service
public class DiagnosticsCaptureService {

    private static final Logger log = LoggerFactory.getLogger(DiagnosticsCaptureService.class);

    private final TuningTrackerRepository trackers;
    private final QueryGroupRepository groups;
    private final SqlDiagnosticRepository diagnostics;
    private final WorkflowService workflow;
    private final QueryMetadataClient client;
    private final Executor executor;
    private final TransactionTemplate tx;
    private final boolean autoCapture;
    private final org.springframework.context.ApplicationEventPublisher events;

    public DiagnosticsCaptureService(TuningTrackerRepository trackers, QueryGroupRepository groups,
                                     SqlDiagnosticRepository diagnostics, WorkflowService workflow,
                                     QueryMetadataClient client, @Qualifier("diagnosticsExecutor") Executor executor,
                                     TransactionTemplate tx, SptProperties props,
                                     org.springframework.context.ApplicationEventPublisher events) {
        this.events = events;
        this.trackers = trackers;
        this.groups = groups;
        this.diagnostics = diagnostics;
        this.workflow = workflow;
        this.client = client;
        this.executor = executor;
        this.tx = tx;
        this.autoCapture = props.diagnostics().autoCapture();
    }

    /** New tracker item: mark the capture as queued in the same transaction, so the first read already shows it. */
    @EventListener
    public void markQueued(TrackerService.TrackerCreated e) {
        if (autoCapture) {
            TuningTracker t = e.tracker();
            t.setCaptureStatus(CaptureStatus.PENDING);
            t.setCaptureRequestedAt(LocalDateTime.now());
        }
    }

    /** ... and start it once the item is committed. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void startQueued(TrackerService.TrackerCreated e) {
        if (autoCapture) {
            submit(e.tracker().getId());
        }
    }

    /** Captures cut short by a restart are started again. */
    @EventListener(ApplicationReadyEvent.class)
    public void resumeInterrupted() {
        List<TuningTracker> open = trackers.findByCaptureStatusIn(Set.of(CaptureStatus.PENDING, CaptureStatus.RUNNING));
        if (!open.isEmpty()) {
            log.info("Resuming {} diagnostics captures interrupted by a restart", open.size());
            open.forEach(t -> submit(t.getId()));
        }
    }

    /** Runs the capture again (button on the tracker item, or after a failure). */
    @Transactional
    public void request(Long trackerId) {
        TuningTracker t = trackers.findById(trackerId).orElseThrow(() -> new NotFoundException("Tracker", trackerId));
        if (t.getCaptureStatus() == CaptureStatus.PENDING || t.getCaptureStatus() == CaptureStatus.RUNNING) {
            return;
        }
        trackers.requestCapture(trackerId, CaptureStatus.PENDING, LocalDateTime.now());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                submit(trackerId);
            }
        });
    }

    private void submit(Long trackerId) {
        executor.execute(() -> run(trackerId));
    }

    /** One capture: RUNNING -> fetch -> store -> DONE, or FAILED with the reason. Package-visible for tests. */
    void run(Long trackerId) {
        try {
            QueryMetadataClient.Request req = tx.execute(s -> {
                TuningTracker t = trackers.findById(trackerId).orElse(null);
                if (t == null) {
                    return null;
                }
                trackers.setCapture(trackerId, CaptureStatus.RUNNING, null, null);
                QueryGroup g = groups.findById(t.getGroupId()).orElseThrow(() -> new NotFoundException("Query group", t.getGroupId()));
                return new QueryMetadataClient.Request(g.getId(), g.getSqlEngine(), g.getSampleQuery(),
                        t.getOgRunDurationMinutes() != null ? t.getOgRunDurationMinutes() : g.getAvgDurationMinutes());
            });
            if (req == null) {
                return;
            }
            if (!"IMPALA".equals(req.sqlEngine())) {
                finish(trackerId, CaptureStatus.FAILED, "Automatic capture supports Impala only; capture "
                        + req.sqlEngine() + " diagnostics on the group page.");
                return;
            }
            QueryMetadataClient.Result result = client.fetch(req);
            String message = tx.execute(s -> CurrentUser.runAs(client.source(), () -> store(req.groupId(), result)));
            finish(trackerId, CaptureStatus.DONE, message);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("Diagnostics capture for tracker {} failed", trackerId, e);
            finish(trackerId, CaptureStatus.FAILED, Texts.truncate(e.getMessage() == null ? e.toString() : e.getMessage(), 900));
        }
    }

    /** Stores the fetched diagnostics; anything already captured by hand is kept and not duplicated. */
    private String store(Long groupId, QueryMetadataClient.Result r) {
        boolean haveOriginal = diagnostics.findByGroupIdOrderByIdDesc(groupId).stream()
                .anyMatch(d -> d.getPhase() == SqlDiagnostic.Phase.ORIGINAL && d.getStatus() == SqlDiagnostic.Status.CAPTURED);
        if (!haveOriginal) {
            workflow.captureDiagnostic(groupId, new WorkflowService.DiagnosticRequest(SqlDiagnostic.Phase.ORIGINAL,
                    null, null, r.queryId(), null, r.rowCount(), null, r.explainPlan(), r.profileRaw(), null,
                    SqlDiagnostic.Status.CAPTURED, null, null, null, null, null, null));
        }
        Set<String> known = workflow.ddls(groupId).stream().map(d -> d.getTableName().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        int added = 0;
        for (QueryMetadataClient.TableInfo t : r.tables()) {
            if (known.add(t.tableName().toLowerCase(Locale.ROOT))) {
                workflow.addDdl(groupId, new WorkflowService.DdlRequest(t.tableName(), t.ddl(), t.rowCount()));
                added++;
            }
        }
        return (haveOriginal ? "Kept the diagnostics captured by hand" : "Explain plan and profile captured")
                + "; " + added + " table DDL" + (added == 1 ? "" : "s") + " added from " + client.source() + ".";
    }

    private void finish(Long trackerId, CaptureStatus status, String message) {
        tx.executeWithoutResult(s -> trackers.setCapture(trackerId, status, LocalDateTime.now(), message));
        events.publishEvent(new CaptureFinished(trackerId, status, message));
    }

    /** Published (outside any transaction) when an automatic capture ends. */
    public record CaptureFinished(Long trackerId, CaptureStatus status, String message) {
    }
}
