package com.techcoder.sqlperf.queues;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import com.techcoder.sqlperf.common.Texts;
import com.techcoder.sqlperf.iteration.IterationService;
import com.techcoder.sqlperf.iteration.TuningIteration;
import com.techcoder.sqlperf.settings.WorkflowSettings;
import com.techcoder.sqlperf.tracker.TuningTracker;
import com.techcoder.sqlperf.tracker.TuningTracker.ResultStatus;
import com.techcoder.sqlperf.tracker.TuningTracker.WorkflowStatus;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The persona work queues (who has to act on what, per the tuning workflow):
 * <ul>
 *   <li><b>Client Development Team</b>: tuned SQL to promote for adoption (largest saving first), items still not
 *       adopted after the follow-up period (step 10), adopted SQL whose bad pattern came back (step 11);</li>
 *   <li><b>Business users</b> (self-service): tuned SQL to test and attest (steps 7-9), same list as "ready";</li>
 *   <li><b>Tuning team</b>: results below the threshold, failed results and automatic runs, rejections by users
 *       (step 12), tuning exceptions and items awaiting an architectural fix by theme (steps 13-15).</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class WorkQueueService {

    /** One item in a queue. {@code savingMinutesPerMonth}: per-run saving × bad runs in the last 30 days. */
    public record Item(Long trackerId, String diagnosticId, Long groupId, String sqlSnippet, WorkflowStatus stage,
                       ResultStatus resultStatus, Double gainPct, Integer daysWaiting, String theme, String owner,
                       String detail, Double savingMinutesPerMonth, LocalDateTime since) {
    }

    public record Queues(int followUpWeeks, double thresholdPct, List<Item> readyForAdoption, List<Item> followUp,
                         List<Item> reappeared, List<Item> needsReview, List<Item> failed, List<Item> rejected,
                         List<Item> exceptions, List<Item> awaitingFix) {
    }

    private static final Set<WorkflowStatus> CLOSED = EnumSet.of(WorkflowStatus.ADOPTED, WorkflowStatus.REJECTED,
            WorkflowStatus.TUNING_EXCEPTION, WorkflowStatus.AWAITING_FIX, WorkflowStatus.ON_HOLD);

    private final EntityManager em;
    private final WorkflowSettings settings;

    public WorkQueueService(EntityManager em, WorkflowSettings settings) {
        this.em = em;
        this.settings = settings;
    }

    public Queues queues() {
        LocalDateTime now = LocalDateTime.now();
        double bad = settings.badMinMinutes();
        List<TuningTracker> all = em.createQuery("select t from TuningTracker t join fetch t.group", TuningTracker.class)
                .getResultList();
        Map<Long, Long> recentRuns = new HashMap<>();
        for (Object[] r : em.createQuery("select l.groupId, count(l) from QueryLog l where l.groupId is not null and "
                        + "l.durationMinutes > :bad and coalesce(l.startTime, l.createdAt) >= :since group by l.groupId",
                        Object[].class)
                .setParameter("bad", bad).setParameter("since", now.minusDays(30)).getResultList()) {
            recentRuns.put((Long) r[0], (Long) r[1]);
        }
        // the best tested iteration's run time, for items not selected yet (below the threshold, failed, ...)
        Map<Long, Double> bestRun = new HashMap<>();
        em.createQuery("select i from TuningIteration i", TuningIteration.class).getResultList().stream()
                .collect(java.util.stream.Collectors.groupingBy(TuningIteration::getTrackerId))
                .forEach((id, its) -> IterationService.best(its)
                        .ifPresent(b -> bestRun.put(id, b.getRunDurationMinutes())));
        Set<Long> rejectedIterations = new HashSet<>(em.createQuery("select distinct i.trackerId from TuningIteration i "
                + "where i.status = :st", Long.class).setParameter("st", TuningIteration.Status.REJECTED).getResultList());

        int weeks = settings.followUpWeeks();
        List<Item> ready = list(all, t -> t.getWorkflowStatus() == WorkflowStatus.SME_VALIDATION, recentRuns, bestRun, now,
                t -> since(t), t -> "Waiting for the users' decision");
        List<Item> followUp = ready.stream().filter(i -> i.since() != null
                && i.since().isBefore(now.minusWeeks(weeks))).toList();

        List<Item> reappeared = new ArrayList<>();
        for (TuningTracker t : all) {
            if (t.getWorkflowStatus() != WorkflowStatus.ADOPTED || t.getAdoptedAt() == null) {
                continue;
            }
            Object[] r = em.createQuery("select count(l), max(coalesce(l.startTime, l.createdAt)), count(distinct l.userId) "
                            + "from QueryLog l where l.groupId = :g and l.durationMinutes > :bad and "
                            + "coalesce(l.startTime, l.createdAt) > :at", Object[].class)
                    .setParameter("g", t.getGroupId()).setParameter("bad", bad).setParameter("at", t.getAdoptedAt())
                    .getSingleResult();
            long n = (Long) r[0];
            if (n > 0) {
                reappeared.add(item(t, recentRuns, bestRun, now, t.getAdoptedAt(), n + " bad run" + (n == 1 ? "" : "s") + " since "
                        + "adoption by " + r[2] + " user(s), last " + String.valueOf(r[1]).replace('T', ' ').substring(0, 16)
                        + ": someone still runs the old SQL"));
            }
        }
        List<Item> needsReview = list(all, t -> t.getResultStatus() == ResultStatus.NEEDS_SME_REVIEW
                && !CLOSED.contains(t.getWorkflowStatus()) && t.getWorkflowStatus() != WorkflowStatus.SME_VALIDATION,
                recentRuns, bestRun, now, t -> t.getStageChangedAt(), t -> Texts.trimToNull(t.getAutoRunMessage()));
        List<Item> failed = list(all, t -> (t.getResultStatus() == ResultStatus.FAILED
                        || t.getAutoRunStatus() == TuningTracker.AutoRunStatus.FAILED)
                        && !CLOSED.contains(t.getWorkflowStatus()) && t.getWorkflowStatus() != WorkflowStatus.SME_VALIDATION,
                recentRuns, bestRun, now, t -> t.getStageChangedAt(), t -> Texts.trimToNull(t.getAutoRunMessage()));
        List<Item> rejected = list(all, t -> t.getWorkflowStatus() == WorkflowStatus.REJECTED
                        || (rejectedIterations.contains(t.getId()) && !CLOSED.contains(t.getWorkflowStatus())
                        && t.getWorkflowStatus() != WorkflowStatus.SME_VALIDATION),
                recentRuns, bestRun, now, t -> t.getStageChangedAt(), t -> "Users rejected the tuned SQL");
        List<Item> exceptions = list(all, t -> t.getWorkflowStatus() == WorkflowStatus.TUNING_EXCEPTION, recentRuns,
                bestRun, now, t -> t.getExceptionAt(), t -> t.getExceptionReason() + " (" + t.getExceptionBy() + ")");
        List<Item> awaitingFix = list(all, t -> t.getWorkflowStatus() == WorkflowStatus.AWAITING_FIX, recentRuns,
                bestRun, now, t -> t.getStageChangedAt(), t -> Texts.trimToNull(t.getRootCause()));
        return new Queues(weeks, settings.adoptionThresholdPct(), ready, followUp, reappeared, needsReview, failed,
                rejected, exceptions, awaitingFix);
    }

    private static LocalDateTime since(TuningTracker t) {
        return t.getNotifiedAt() != null ? t.getNotifiedAt() : t.getStageChangedAt();
    }

    private List<Item> list(List<TuningTracker> all, Predicate<TuningTracker> in, Map<Long, Long> recentRuns,
                            Map<Long, Double> bestRun, LocalDateTime now,
                            java.util.function.Function<TuningTracker, LocalDateTime> since,
                            java.util.function.Function<TuningTracker, String> detail) {
        return all.stream().filter(in).map(t -> item(t, recentRuns, bestRun, now, since.apply(t), detail.apply(t)))
                .sorted(Comparator.comparing((Item i) -> i.savingMinutesPerMonth() == null ? 0 : i.savingMinutesPerMonth())
                        .reversed().thenComparing(Item::trackerId))
                .toList();
    }

    private static Item item(TuningTracker t, Map<Long, Long> recentRuns, Map<Long, Double> bestRun,
                             LocalDateTime now, LocalDateTime since, String detail) {
        Double og = t.getOgRunDurationMinutes();
        Double post = t.getPostRunDurationMinutes() != null ? t.getPostRunDurationMinutes() : bestRun.get(t.getId());
        Double gain = og == null || post == null || og <= 0 ? null : Math.round((og - post) / og * 1000d) / 10d;
        Double saving = og == null || post == null ? null
                : Math.round((og - post) * recentRuns.getOrDefault(t.getGroupId(), 0L) * 10d) / 10d;
        String lead = t.getDevTeamLead() != null ? t.getDevTeamLead()
                : t.getSmeTeamLead() != null ? t.getSmeTeamLead() : t.getClouderaTeamLead();
        String sql = t.getGroup() == null ? null : t.getGroup().getSampleQuery();
        return new Item(t.getId(), t.getDiagnosticId(), t.getGroupId(),
                sql == null ? null : Texts.truncate(sql.replaceAll("\\s+", " ").trim(), 160), t.getWorkflowStatus(),
                t.getResultStatus(), gain, since == null ? null : (int) Duration.between(since, now).toDays(),
                t.getTheme(), lead, detail, saving, since);
    }
}
