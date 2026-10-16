package com.techcoder.sqlperf.notification;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;

import com.techcoder.sqlperf.common.Texts;
import com.techcoder.sqlperf.config.SptProperties;
import com.techcoder.sqlperf.group.QueryGroup;
import com.techcoder.sqlperf.iteration.TuningIteration;
import com.techcoder.sqlperf.settings.WorkflowSettings;
import com.techcoder.sqlperf.tracker.TuningTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.HtmlUtils;

/**
 * Workflow e-mails (step 6 "Notify user", and the hand-over to the tuning team). Every e-mail is written to the
 * outbox in the caller's transaction and delivered after commit by the configured {@link EmailTransport}, unless
 * e-mail is switched off or no recipients are configured (Administration → Workflow settings).
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository repo;
    private final WorkflowSettings settings;
    private final EmailTransport transport;
    private final Executor executor;
    private final TransactionTemplate tx;
    private final String from;

    public NotificationService(NotificationRepository repo, WorkflowSettings settings, EmailTransport transport,
                               @Qualifier("notificationExecutor") Executor executor, TransactionTemplate tx,
                               SptProperties props) {
        this.repo = repo;
        this.settings = settings;
        this.transport = transport;
        this.executor = executor;
        this.tx = tx;
        this.from = props.email().from();
    }

    // ------------------------------------------------------------------ messages

    /** The tuned SQL is ready: tell the users how much faster it is, how it was tuned, and to test and adopt it. */
    public Notification readyForAdoption(TuningTracker t, QueryGroup g, TuningIteration it) {
        String gain = pct(it.getRunDurationMinutes(), t.getOgRunDurationMinutes());
        String subject = "[SQL tuning] " + t.getDiagnosticId() + " · tuned SQL ready for adoption (" + gain + " faster)";
        String body = page("A tuned version of your SQL is ready",
                "<p>The SQL below has been tuned and tested: it runs <b>" + gain + " faster</b> and returns the same "
                        + "number of rows. Please test it in your process and adopt it, or reject it with a reason, on "
                        + "the tracker item.</p>"
                        + facts(t, g, it)
                        + section("How it was tuned", pre(it.getChangeNarrative()))
                        + section("Tuned SQL", code(it.getOptimizedSql()))
                        + section("Original SQL", code(g.getSampleQuery()))
                        + link(t, "Test and adopt / reject"));
        return queue(Notification.Kind.READY_FOR_ADOPTION, t.getId(), settings.adoptionRecipients(), subject, body);
    }

    /** The automatic round did not reach the threshold: the tuning team evaluates it (step 13). */
    public Notification needsReview(TuningTracker t, QueryGroup g, TuningIteration it, String reason) {
        String subject = "[SQL tuning] " + t.getDiagnosticId() + " · needs tuning-team review";
        String body = page("A SQL needs your review",
                "<p>The automatic tuning round did not produce a result to send to the users: <b>"
                        + esc(reason) + "</b>. Please evaluate it (root cause, prompt, theme) on the tracker item.</p>"
                        + facts(t, g, it)
                        + (it == null ? "" : section("Best attempt", pre(it.getChangeNarrative()))
                        + section("Best attempt SQL", code(it.getOptimizedSql())))
                        + section("Original SQL", code(g.getSampleQuery()))
                        + link(t, "Open the tracker item"));
        return queue(Notification.Kind.NEEDS_SME_REVIEW, t.getId(), settings.tuningRecipients(), subject, body);
    }

    private String facts(TuningTracker t, QueryGroup g, TuningIteration it) {
        StringBuilder b = new StringBuilder("<table style=\"border-collapse:collapse;font-size:13px\">");
        row(b, "Diagnostic ID", t.getDiagnosticId());
        row(b, "Tracker item", "T-" + t.getId() + " · pattern #" + g.getId() + " · " + g.getSqlEngine());
        row(b, "Runs / users", g.getGroupSize() + " runs by " + g.getDistinctUsers() + " users (" + nz(g.getUserIds()) + ")");
        row(b, "Run time", minutes(t.getOgRunDurationMinutes()) + (it == null ? "" : " → " + minutes(it.getRunDurationMinutes())
                + " (" + pct(it.getRunDurationMinutes(), t.getOgRunDurationMinutes()) + " faster)"));
        if (it != null) {
            row(b, "Impala CPU", seconds(t.getOgCpuSeconds()) + " → " + seconds(it.getCpuSeconds()));
            row(b, "Rows scanned", count(t.getOgRowsScanned()) + " → " + count(it.getRowsScanned()));
            row(b, "Peak memory", mb(t.getOgPeakMemoryMb()) + " → " + mb(it.getPeakMemoryMb()));
            row(b, "Same result", it.getResultMatches() == null ? "not checked" : it.getResultMatches() ? "yes" : "no");
            row(b, "Iteration", "#" + it.getIterationNo() + " (" + it.getSource() + ")");
        }
        if (!Texts.isBlank(t.getTheme())) {
            row(b, "Theme", t.getTheme());
        }
        return b.append("</table>").toString();
    }

    // ------------------------------------------------------------------ outbox

    /** Writes the e-mail to the outbox and, after the surrounding transaction commits, sends it. */
    Notification queue(Notification.Kind kind, Long trackerId, List<String> to, String subject, String html) {
        Notification n = new Notification();
        n.setTrackerId(trackerId);
        n.setKind(kind);
        n.setRecipients(String.join(", ", to));
        n.setSubject(Texts.truncate(subject, 500));
        n.setBody(html);
        n.setCreatedAt(LocalDateTime.now());
        n.setStatus(!settings.emailEnabled() ? Notification.Status.DISABLED
                : to.isEmpty() ? Notification.Status.NO_RECIPIENTS : Notification.Status.PENDING);
        repo.save(n);
        if (n.getStatus() == Notification.Status.PENDING) {
            Long id = n.getId();
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        executor.execute(() -> deliver(id));
                    }
                });
            } else {
                executor.execute(() -> deliver(id));
            }
        }
        return n;
    }

    void deliver(Long id) {
        Notification n = tx.execute(s -> repo.findById(id).orElse(null));
        if (n == null) {
            return;
        }
        Notification.Status status;
        String error = null;
        try {
            status = transport.send(from, WorkflowSettings.emailList(n.getRecipients()), n.getSubject(), n.getBody())
                    ? Notification.Status.SENT : Notification.Status.LOGGED;
        } catch (Exception e) {
            log.warn("E-mail {} failed", id, e);
            status = Notification.Status.FAILED;
            error = Texts.truncate(e.getMessage() == null ? e.toString() : e.getMessage(), 2000);
        }
        Notification.Status st = status;
        String err = error;
        tx.executeWithoutResult(s -> repo.findById(id).ifPresent(x -> {
            x.setStatus(st);
            x.setErrorMessage(err);
            x.setSentAt(st == Notification.Status.SENT ? LocalDateTime.now() : null);
        }));
    }

    public Page<Notification> list(int page, int size) {
        return repo.findAllByOrderByIdDesc(PageRequest.of(Math.max(0, page), Math.clamp(size, 1, 200)));
    }

    public List<Notification> forTracker(Long trackerId) {
        return repo.findByTrackerIdOrderByIdDesc(trackerId);
    }

    // ------------------------------------------------------------------ html helpers

    private String page(String title, String content) {
        return "<div style=\"font-family:Arial,Helvetica,sans-serif;color:#13243b;max-width:760px\">"
                + "<h2 style=\"color:#0b4f9c;margin:0 0 12px\">" + esc(title) + "</h2>" + content
                + "<p style=\"color:#5a6b82;font-size:12px;margin-top:24px\">Sent by the SQL Tuning Workbench. "
                + "Reply to the tuning team or comment on the tracker item.</p></div>";
    }

    private String link(TuningTracker t, String label) {
        String url = settings.appBaseUrl() + "/tracker/" + t.getId();
        return "<p><a href=\"" + esc(url) + "\" style=\"background:#1565c0;color:#fff;padding:8px 16px;"
                + "border-radius:16px;text-decoration:none\">" + esc(label) + "</a></p>";
    }

    private static String section(String title, String content) {
        return "<h3 style=\"font-size:14px;margin:18px 0 6px\">" + esc(title) + "</h3>" + content;
    }

    private static String code(String sql) {
        return "<pre style=\"background:#f5f8fc;border:1px solid #d6e2f1;border-radius:6px;padding:10px;"
                + "font-size:12px;white-space:pre-wrap\">" + esc(nz(sql)) + "</pre>";
    }

    private static String pre(String text) {
        return "<div style=\"white-space:pre-wrap;font-size:13px\">" + esc(nz(text)) + "</div>";
    }

    private static void row(StringBuilder b, String k, String v) {
        b.append("<tr><td style=\"padding:3px 16px 3px 0;color:#5a6b82\">").append(esc(k))
                .append("</td><td style=\"padding:3px 0\">").append(esc(v)).append("</td></tr>");
    }

    private static String esc(String s) {
        return s == null ? "" : HtmlUtils.htmlEscape(s);
    }

    private static String nz(String s) {
        return s == null ? "—" : s;
    }

    static String pct(Double after, Double before) {
        if (after == null || before == null || before <= 0) {
            return "—";
        }
        return String.format(Locale.ROOT, "%.1f%%", (before - after) / before * 100);
    }

    private static String minutes(Double m) {
        return m == null ? "—" : String.format(Locale.ROOT, "%.1f min", m);
    }

    private static String seconds(Double s) {
        return s == null ? "—" : String.format(Locale.ROOT, "%,.0f s", s);
    }

    private static String count(Long n) {
        return n == null ? "—" : String.format(Locale.ROOT, "%,d", n);
    }

    private static String mb(Double v) {
        return v == null ? "—" : String.format(Locale.ROOT, "%,.0f MB", v);
    }
}
