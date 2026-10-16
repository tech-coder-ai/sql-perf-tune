package com.techcoder.sqlperf.autorun;

import java.util.List;
import java.util.Optional;

import com.techcoder.sqlperf.common.CurrentUser;
import com.techcoder.sqlperf.diagnostics.DiagnosticsCaptureService;
import com.techcoder.sqlperf.diagnostics.IterationTestService;
import com.techcoder.sqlperf.group.QueryGroup;
import com.techcoder.sqlperf.group.QueryGroupRepository;
import com.techcoder.sqlperf.ingestion.IngestionService;
import com.techcoder.sqlperf.iteration.IterationService;
import com.techcoder.sqlperf.iteration.TuningIteration;
import com.techcoder.sqlperf.iteration.TuningIterationRepository;
import com.techcoder.sqlperf.notification.NotificationService;
import com.techcoder.sqlperf.settings.WorkflowSettings;
import com.techcoder.sqlperf.tracker.TrackerDto;
import com.techcoder.sqlperf.tracker.TrackerService;
import com.techcoder.sqlperf.tracker.TuningTracker;
import com.techcoder.sqlperf.tracker.TuningTracker.AutoRunStatus;
import com.techcoder.sqlperf.tracker.TuningTracker.CaptureStatus;
import com.techcoder.sqlperf.tracker.TuningTracker.ResultStatus;
import com.techcoder.sqlperf.tracker.TuningTrackerRepository;
import com.techcoder.sqlperf.workflow.OptimizationRun;
import com.techcoder.sqlperf.workflow.WorkflowService;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The automatic first tuning round (steps 2, 4, 5, 6 of the workflow), switched on and off in Administration:
 * <ol>
 *   <li>after every import (and daily), untracked patterns with enough bad runs are put on the tracker;</li>
 *   <li>their diagnostics are captured (explain plan, profile, DDL: {@code QueryMetadataClient});</li>
 *   <li>the optimization agent is asked for a rewrite ({@code OptimizationAgent}, the LLM endpoint);</li>
 *   <li>the rewrite is test-run ({@code QueryMetadataClient});</li>
 *   <li>the {@link ComparisonModule} decides: more than the threshold faster → selected and e-mailed to the users
 *       (ready for adoption); otherwise → e-mailed to the tuning team.</li>
 * </ol>
 * Each step reacts to the event of the previous one, so nobody has to click. Later rounds (asking the agent again,
 * testing more variants) are done by people. Both integrations are the same ports the manual flow uses, so
 * replacing the mock Impala client / mock LLM endpoint changes nothing here.
 */
@Service
public class AutoRunService {

    private static final Logger log = LoggerFactory.getLogger(AutoRunService.class);
    /** The user name automatic steps are recorded under. */
    static final String ACTOR = "automatic run";

    private final WorkflowSettings settings;
    private final TrackerService trackerService;
    private final TuningTrackerRepository trackers;
    private final QueryGroupRepository groups;
    private final TuningIterationRepository iterations;
    private final IterationService iterationService;
    private final WorkflowService workflow;
    private final IterationTestService tests;
    private final ComparisonModule comparison;
    private final NotificationService notifications;
    private final EntityManager em;
    private final TransactionTemplate tx;
    private final java.util.concurrent.Executor mailWorker;
    private final ThemeFixRepository themeFixes;
    private final DiagnosticsCaptureService capture;

    public AutoRunService(WorkflowSettings settings, TrackerService trackerService, TuningTrackerRepository trackers,
                          QueryGroupRepository groups, TuningIterationRepository iterations,
                          IterationService iterationService, WorkflowService workflow, IterationTestService tests,
                          ComparisonModule comparison, NotificationService notifications, EntityManager em,
                          PlatformTransactionManager txm,
                          @org.springframework.beans.factory.annotation.Qualifier("notificationExecutor")
                          java.util.concurrent.Executor mailWorker, ThemeFixRepository themeFixes,
                          @org.springframework.context.annotation.Lazy DiagnosticsCaptureService capture) {
        this.themeFixes = themeFixes;
        this.capture = capture;
        this.settings = settings;
        this.trackerService = trackerService;
        this.trackers = trackers;
        this.groups = groups;
        this.iterations = iterations;
        this.iterationService = iterationService;
        this.workflow = workflow;
        this.tests = tests;
        this.comparison = comparison;
        this.notifications = notifications;
        this.em = em;
        this.tx = new TransactionTemplate(txm);
        this.mailWorker = mailWorker;
    }

    // ------------------------------------------------------------------ 1. track candidates

    @EventListener
    public void onImport(IngestionService.ImportFinished e) {
        if (settings.autoEnabled()) {
            trackCandidates();
        }
    }

    /** Daily run as well (the daily bad-query extract may arrive by a database pull). */
    @Scheduled(cron = "${spt.auto.cron:0 15 6 * * *}")
    public void daily() {
        if (settings.autoEnabled()) {
            trackCandidates();
        }
    }

    public record TrackResult(int tracked, List<Long> trackerIds) {
    }

    /**
     * Puts untracked tuning candidates on the tracker (patterns with at least {@code candidate.min-runs} runs longer
     * than the bad-query threshold), largest total run time first, at most {@code auto.max-per-run}.
     */
    public TrackResult trackCandidates() {
        List<Long> ids = tx.execute(s -> em.createQuery("select g.id from QueryGroup g where not exists ("
                        + "select t.id from TuningTracker t where t.groupId = g.id) and (select count(l) from QueryLog l "
                        + "where l.groupId = g.id and l.durationMinutes > :bad) >= :runs order by g.totalDurationMinutes desc",
                        Long.class)
                .setParameter("bad", settings.badMinMinutes()).setParameter("runs", (long) settings.candidateMinRuns())
                .setMaxResults(Math.max(0, settings.autoMaxPerRun())).getResultList());
        if (ids == null || ids.isEmpty()) {
            return new TrackResult(0, List.of());
        }
        List<TrackerDto> created = tx.execute(s -> CurrentUser.runAs(ACTOR, () -> {
            List<TrackerDto> out = trackerService.createForGroups(ids);
            out.forEach(d -> trackers.setAutoRun(d.trackerId(), AutoRunStatus.PENDING, "Waiting for the diagnostics"));
            return out;
        }));
        log.info("Automatic run: {} candidates put on the tracker", created.size());
        // without automatic capture there is nothing to wait for
        created.stream().filter(d -> d.captureStatus() == null).forEach(d -> askAgent(d.trackerId()));
        return new TrackResult(created.size(), created.stream().map(TrackerDto::trackerId).toList());
    }

    // ------------------------------------------------------------------ 2-3. diagnostics → agent

    @EventListener
    public void onCapture(DiagnosticsCaptureService.CaptureFinished e) {
        if (!isAuto(e.trackerId(), AutoRunStatus.PENDING)) {
            return;
        }
        if (e.status() == CaptureStatus.FAILED) {
            stop(e.trackerId(), "Diagnostics capture failed: " + e.message());
            return;
        }
        askAgent(e.trackerId());
    }

    private void askAgent(Long trackerId) {
        TuningTracker t = tx.execute(s -> trackers.findById(trackerId).orElse(null));
        if (t == null) {
            return;
        }
        if (!workflow.agentInfo().automatic()) {
            tx.executeWithoutResult(s -> trackers.setAutoRun(trackerId, AutoRunStatus.DONE,
                    "Diagnostics captured; the agent is manual: run the prompt and paste the answer"));
            return;
        }
        try {
            tx.executeWithoutResult(s -> trackers.setAutoRun(trackerId, AutoRunStatus.RUNNING,
                    "Waiting for the optimization agent"));
            CurrentUser.runAs(ACTOR, () -> workflow.startOptimization(t.getGroupId(), null));
        } catch (RuntimeException ex) {
            stop(trackerId, "The optimization agent could not be asked: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------------ 4-5. answer → test → compare → route

    @EventListener
    public void onAnswer(WorkflowService.AgentAnswered e) {
        Long trackerId = tx.execute(s -> trackers.findByGroupId(e.groupId()).map(TuningTracker::getId).orElse(null));
        if (trackerId == null || !isAuto(trackerId, AutoRunStatus.RUNNING)) {
            return;
        }
        if (e.status() != OptimizationRun.Status.COMPLETED || e.iterationId() == null) {
            stop(trackerId, "The optimization agent gave no usable SQL (run #" + e.runId() + ")");
            return;
        }
        int no = tx.execute(s -> iterations.findById(e.iterationId()).map(TuningIteration::getIterationNo).orElse(0));
        tx.executeWithoutResult(s -> trackers.setAutoRun(trackerId, AutoRunStatus.RUNNING, "Testing iteration #" + no));
        try {
            CurrentUser.runAs(ACTOR, () -> {
                try {
                    return tests.test(trackerId, e.iterationId());
                } catch (Exception ex) {
                    throw new IllegalStateException(ex.getMessage(), ex);
                }
            });
        } catch (RuntimeException ex) {
            stop(trackerId, "The test run of iteration #" + no + " failed: " + ex.getMessage());
            return;
        }
        route(trackerId);
    }

    /** Every recorded test updates the comparison result (manual tests too; only the automatic run routes). */
    @EventListener
    public void onTested(IterationTestService.IterationTested e) {
        comparison.evaluate(e.trackerId());
    }

    private void route(Long trackerId) {
        Optional<ComparisonModule.Decision> d = comparison.evaluate(trackerId);
        if (d.isEmpty()) {
            stop(trackerId, "Nothing was tested");
            return;
        }
        ComparisonModule.Decision decision = d.get();
        if (decision.status() == ResultStatus.READY_FOR_ADOPTION) {
            CurrentUser.runAs(ACTOR, () -> iterationService.select(trackerId, decision.best().getId()));
            tx.executeWithoutResult(s -> trackers.setAutoRun(trackerId, AutoRunStatus.DONE,
                    "Iteration #" + decision.best().getIterationNo() + " is " + decision.reason()
                            + ": sent to the users for adoption"));
        } else {
            tx.executeWithoutResult(s -> {
                trackers.setAutoRun(trackerId, AutoRunStatus.DONE,
                        "Best result " + decision.reason() + ": sent to the tuning team");
                mailTuningTeam(trackerId, decision.best(), decision.reason());
            });
        }
    }

    /** Ends the automatic run and hands the item to the tuning team. */
    private void stop(Long trackerId, String reason) {
        log.info("Automatic run of tracker {} stopped: {}", trackerId, reason);
        tx.executeWithoutResult(s -> {
            trackers.setAutoRun(trackerId, AutoRunStatus.FAILED, reason);
            mailTuningTeam(trackerId, null, reason);
        });
    }

    private void mailTuningTeam(Long trackerId, TuningIteration best, String reason) {
        TuningTracker t = trackers.findById(trackerId).orElseThrow();
        QueryGroup g = groups.findById(t.getGroupId()).orElseThrow();
        notifications.needsReview(t, g, best, reason);
    }

    private boolean isAuto(Long trackerId, AutoRunStatus expected) {
        return Boolean.TRUE.equals(tx.execute(s -> trackers.findById(trackerId)
                .map(t -> t.getAutoRunStatus() == expected).orElse(false)));
    }

    // ------------------------------------------------------------------ 15. retry after a theme fix

    /**
     * The architectural fix of a theme is released: every item waiting for it (stage Awaiting fix) goes through the
     * automatic round again: diagnostics re-captured on the new model, agent, test, comparison, routing.
     */
    public ThemeFix releaseThemeFix(String theme, String notes) {
        if (theme == null || theme.isBlank()) {
            throw new IllegalArgumentException("theme is required");
        }
        List<Long> waiting = tx.execute(s -> em.createQuery("select t.id from TuningTracker t where t.theme = :theme "
                        + "and t.workflowStatus = :st", Long.class)
                .setParameter("theme", theme.trim()).setParameter("st", TuningTracker.WorkflowStatus.AWAITING_FIX)
                .getResultList());
        ThemeFix fix = tx.execute(s -> {
            ThemeFix f = new ThemeFix();
            f.setTheme(theme.trim());
            f.setNotes(notes == null || notes.isBlank() ? null : notes.trim());
            f.setItemsRetried(waiting.size());
            f.setReleasedAt(java.time.LocalDateTime.now());
            f.setReleasedBy(CurrentUser.name());
            themeFixes.save(f);
            for (Long id : waiting) {
                trackerService.reopen(id);
                trackers.setAutoRun(id, AutoRunStatus.PENDING, "Theme fix released: retrying");
            }
            return f;
        });
        for (Long id : waiting) {
            TuningTracker t = tx.execute(s -> trackers.findById(id).orElse(null));
            if (t != null && t.getCaptureStatus() != null && capture != null) {
                capture.request(id); // re-capture on the fixed model; the round continues from the capture event
            } else {
                askAgent(id);
            }
        }
        log.info("Theme fix for '{}' released: {} items retried", theme, waiting.size());
        return fix;
    }

    public List<ThemeFix> themeFixes() {
        return themeFixes.findTop50ByOrderByIdDesc();
    }

    // ------------------------------------------------------------------ 6. notify users

    /**
     * Any selection (automatic or by a person) sends the "ready for adoption" e-mail once committed. The e-mail is
     * written on the mail worker, after the selecting transaction has released its connection (SQLite has one).
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSelected(IterationService.IterationSelected e) {
        mailWorker.execute(() -> mailReady(e));
    }

    private void mailReady(IterationService.IterationSelected e) {
        tx.executeWithoutResult(s -> {
            TuningTracker t = trackers.findById(e.trackerId()).orElse(null);
            TuningIteration it = iterations.findById(e.iterationId()).orElse(null);
            if (t == null || it == null) {
                return;
            }
            QueryGroup g = groups.findById(t.getGroupId()).orElseThrow();
            notifications.readyForAdoption(t, g, it);
            trackers.setNotifiedAt(t.getId(), java.time.LocalDateTime.now());
        });
    }
}
