package com.techcoder.sqlperf.insights;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.techcoder.sqlperf.common.Texts;
import com.techcoder.sqlperf.group.QueryGroup;
import com.techcoder.sqlperf.iteration.TuningIteration;
import com.techcoder.sqlperf.iteration.TuningIterationRepository;
import com.techcoder.sqlperf.tracker.TrackerDto;
import com.techcoder.sqlperf.tracker.TrackerService;
import com.techcoder.sqlperf.tracker.TuningTracker;
import com.techcoder.sqlperf.tracker.TuningTracker.RequestSource;
import com.techcoder.sqlperf.tracker.TuningTracker.WorkflowStatus;
import com.techcoder.sqlperf.users.UserDirectoryEntry;
import com.techcoder.sqlperf.users.UserDirectoryRepository;
import com.techcoder.sqlperf.workflow.Feedback;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers the operational questions of the tuning programme (numbered Q1-Q15 as in the business brief).
 *
 * <p>Definitions used throughout:
 * <ul>
 *   <li><b>Bad query</b>: one row of the injected query log, dated by its START_TIME (load time if missing).</li>
 *   <li><b>Pattern</b>: a query group (same SQL ignoring WHERE filters).</li>
 *   <li><b>Recurring</b>: the pattern had already been seen on an earlier day.</li>
 *   <li><b>Optimized</b>: the tracker item has a tested / selected / adopted iteration (status Candidate ready or later).</li>
 *   <li><b>Outstanding</b>: a pattern that is not adopted yet (untracked, in progress, on hold or rejected).
 *       <b>Active</b> outstanding: such a pattern that also had bad queries in the last {@value #ACTIVE_DAYS} days up to
 *       the reference day; patterns that stopped running long ago are only in the all-time numbers.</li>
 *   <li><b>Estimated savings</b>: per-run saving x the pattern's run rate in the 30 days before adoption
 *       x the days since adoption that fall in the period.</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class InsightsService {

    private static final String TS = "coalesce(l.startTime, l.createdAt)";
    /** Calendar day as 'yyyy-MM-dd' (see SptFunctionContributor). */
    private static final String DAY = "spt_date(" + TS + ")";
    private static final Set<WorkflowStatus> OPTIMIZED = Set.of(WorkflowStatus.OPTIMIZED,
            WorkflowStatus.POST_RUN_VALIDATED, WorkflowStatus.SME_VALIDATION, WorkflowStatus.ADOPTED);
    private static final double DEFAULT_IMPROVEMENT_PCT = 50d;
    /** window for "active" outstanding patterns (Q6 headline, Command center) */
    static final int ACTIVE_DAYS = 30;
    private static final String NOT_TRACKED = "Not tracked";
    private static final String UNCATEGORIZED = "Uncategorized";

    private final EntityManager em;
    private final TuningIterationRepository iterations;
    private final UserDirectoryRepository directory;
    private final TrackerService trackerService;

    public InsightsService(EntityManager em, TuningIterationRepository iterations, UserDirectoryRepository directory,
                           TrackerService trackerService) {
        this.em = em;
        this.iterations = iterations;
        this.directory = directory;
        this.trackerService = trackerService;
    }

    // =====================================================================================================
    // Q1, Q2, Q3, Q7 (day), Q8 - daily view
    // =====================================================================================================

    public record CountPoint(String label, long count) {
    }

    public record PatternCount(Long groupId, long count, long totalInstances, String sqlSnippet, Long trackerId,
                               WorkflowStatus status, String theme, boolean recurring) {
    }

    public record Daily(LocalDate date, long badQueries, long badQueriesPreviousDay, long patterns,
                        long newPatterns, long recurringPatterns, long recurringQueries, long repeatedTodayPatterns,
                        double totalMinutes, List<CountPoint> byHour, List<CountPoint> last14Days,
                        List<PatternCount> topPatterns, List<CountPoint> instanceBuckets, List<CountPoint> topUsers,
                        Integer peakHour, LocalDate latestDataDay) {
    }

    public Daily daily(LocalDate date) {
        LocalDateTime from = date.atStartOfDay();
        LocalDateTime to = from.plusDays(1);
        long bad = count(from, to);
        long prev = count(from.minusDays(1), from);

        Map<Long, long[]> perGroup = new HashMap<>(); // groupId -> {count}
        Map<Long, Double> minutesPerGroup = new HashMap<>();
        for (Object[] r : em.createQuery("select l.groupId, count(l), sum(l.durationMinutes) from QueryLog l where "
                        + TS + " >= :f and " + TS + " < :t and l.groupId is not null group by l.groupId", Object[].class)
                .setParameter("f", from).setParameter("t", to).getResultList()) {
            perGroup.put((Long) r[0], new long[] {(Long) r[1]});
            minutesPerGroup.put((Long) r[0], r[2] == null ? 0d : ((Number) r[2]).doubleValue());
        }
        Map<Long, QueryGroup> groups = groupsById(perGroup.keySet());
        Map<Long, TuningTracker> trackers = trackersByGroup(perGroup.keySet());
        long newPatterns = 0;
        long recurringPatterns = 0;
        long recurringQueries = 0;
        long repeated = 0;
        for (var e : perGroup.entrySet()) {
            QueryGroup g = groups.get(e.getKey());
            boolean recurring = g != null && g.getFirstSeen() != null && g.getFirstSeen().isBefore(from);
            if (recurring) {
                recurringPatterns++;
                recurringQueries += e.getValue()[0];
            } else {
                newPatterns++;
            }
            if (e.getValue()[0] > 1) {
                repeated++;
            }
        }

        long[] hours = new long[24];
        for (Object[] r : em.createQuery("select hour(" + TS + "), count(l) from QueryLog l where " + TS
                        + " >= :f and " + TS + " < :t group by hour(" + TS + ")", Object[].class)
                .setParameter("f", from).setParameter("t", to).getResultList()) {
            if (r[0] != null) {
                hours[((Number) r[0]).intValue()] = (Long) r[1];
            }
        }
        List<CountPoint> byHour = new ArrayList<>();
        Integer peak = null;
        for (int h = 0; h < 24; h++) {
            byHour.add(new CountPoint(String.format("%02d", h), hours[h]));
            if (hours[h] > 0 && (peak == null || hours[h] > hours[peak])) {
                peak = h;
            }
        }

        List<CountPoint> last14 = new ArrayList<>();
        Map<LocalDate, Long> perDay = perDay(date.minusDays(13).atStartOfDay(), to);
        for (int i = 13; i >= 0; i--) {
            LocalDate d = date.minusDays(i);
            last14.add(new CountPoint(d.toString(), perDay.getOrDefault(d, 0L)));
        }

        List<PatternCount> top = perGroup.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                .limit(10)
                .map(e -> {
                    QueryGroup g = groups.get(e.getKey());
                    TuningTracker t = trackers.get(e.getKey());
                    return new PatternCount(e.getKey(), e.getValue()[0], g == null ? 0 : g.getGroupSize(),
                            g == null ? null : snippet(g.getSampleQuery()), t == null ? null : t.getId(),
                            t == null ? null : t.getWorkflowStatus(), t == null ? null : t.getTheme(),
                            g != null && g.getFirstSeen() != null && g.getFirstSeen().isBefore(from));
                }).toList();

        long[] buckets = new long[4];
        for (long[] c : perGroup.values()) {
            buckets[c[0] == 1 ? 0 : c[0] <= 5 ? 1 : c[0] <= 20 ? 2 : 3]++;
        }
        List<CountPoint> instanceBuckets = List.of(new CountPoint("1", buckets[0]), new CountPoint("2-5", buckets[1]),
                new CountPoint("6-20", buckets[2]), new CountPoint("21+", buckets[3]));

        List<CountPoint> topUsers = em.createQuery("select l.userId, count(l) from QueryLog l where " + TS + " >= :f and "
                        + TS + " < :t and l.userId is not null group by l.userId order by count(l) desc", Object[].class)
                .setParameter("f", from).setParameter("t", to).setMaxResults(10).getResultList().stream()
                .map(r -> new CountPoint((String) r[0], (Long) r[1])).toList();

        double minutes = minutesPerGroup.values().stream().mapToDouble(Double::doubleValue).sum();
        return new Daily(date, bad, prev, perGroup.size(), newPatterns, recurringPatterns, recurringQueries, repeated,
                round1(minutes), byHour, last14, top, instanceBuckets, topUsers, peak, latestDataDay());
    }

    // =====================================================================================================
    // Q4, Q6, Q9, Q15 - pipeline, prior-day outcome, turnaround, adoption
    // =====================================================================================================

    public record StageCount(WorkflowStatus status, long count) {
    }

    public record ThemeCount(String theme, long patterns, long badQueries) {
    }

    public record PriorDay(LocalDate date, long patterns, long badQueries, long optimizedPatterns,
                           long optimizedQueries, long notOptimizedPatterns, long notOptimizedQueries,
                           long categorizedPatterns, long uncategorizedPatterns, long untrackedPatterns,
                           List<ThemeCount> themes) {
    }

    /**
     * Q6. The headline is the <b>active</b> backlog: not adopted and with bad queries in the last {@code activeDays}
     * days up to {@code asOf}. The all-time figures count every pattern ever loaded that is not adopted.
     */
    public record Outstanding(long patterns, long badQueries, long untrackedPatterns, long inProgressPatterns,
                              long awaitingAdoptionPatterns, long onHoldOrRejectedPatterns, int activeDays,
                              LocalDate asOf, long allTimePatterns, long allTimeBadQueries, long allTimeUntrackedPatterns) {
    }

    public record AwaitingItem(Long trackerId, Long groupId, String sqlSnippet, String theme, Integer daysWaiting,
                               Double improvementPct, String devTeamLead) {
    }

    public record RejectionItem(Long groupId, Long trackerId, Long iterationId, String reason, String comments,
                                String role, String by, LocalDateTime at) {
    }

    public record Rejections(long total, long inaccurate, List<CountPoint> byReason, List<RejectionItem> latest) {
    }

    public record StageDuration(WorkflowStatus stage, Double avgDays) {
    }

    public record Turnaround(long adoptedItems, Double avgDays, Double medianDays, Double p90Days,
                             Double avgDaysFromDetection, List<StageDuration> byStage, long openItems,
                             Double avgOpenAgeDays) {
    }

    public record Pipeline(List<StageCount> stages, Outstanding outstanding, PriorDay priorDay,
                           List<AwaitingItem> awaitingAdoption, Rejections rejections, Turnaround turnaround) {
    }

    public Pipeline pipeline(LocalDate priorDay, LocalDate from, LocalDate to) {
        List<TuningTracker> all = em.createQuery("select t from TuningTracker t join fetch t.group", TuningTracker.class)
                .getResultList();
        Map<WorkflowStatus, Long> byStatus = new EnumMap<>(WorkflowStatus.class);
        for (WorkflowStatus s : WorkflowStatus.values()) {
            byStatus.put(s, 0L);
        }
        all.forEach(t -> byStatus.merge(t.getWorkflowStatus(), 1L, Long::sum));
        List<StageCount> stages = byStatus.entrySet().stream().map(e -> new StageCount(e.getKey(), e.getValue())).toList();

        // Q6 outstanding: patterns that are not adopted. Active = had bad queries in the window up to the day after
        // the prior day (i.e. the day being looked at); all-time = every pattern ever loaded.
        Map<Long, TuningTracker> byGroup = all.stream().collect(Collectors.toMap(TuningTracker::getGroupId, Function.identity()));
        LocalDate asOf = priorDay.plusDays(1);
        LocalDateTime activeTo = asOf.plusDays(1).atStartOfDay();
        LocalDateTime activeFrom = activeTo.minusDays(ACTIVE_DAYS);
        Map<Long, Long> recent = new HashMap<>();
        for (Object[] r : em.createQuery("select l.groupId, count(l) from QueryLog l where " + TS + " >= :f and " + TS
                        + " < :t and l.groupId is not null group by l.groupId", Object[].class)
                .setParameter("f", activeFrom).setParameter("t", activeTo).getResultList()) {
            recent.put((Long) r[0], (Long) r[1]);
        }
        long outPatterns = 0;
        long outQueries = 0;
        long untracked = 0;
        long inProgress = 0;
        long awaiting = 0;
        long parked = 0;
        long allPatterns = 0;
        long allQueries = 0;
        long allUntracked = 0;
        for (Object[] r : em.createQuery("select g.id, g.groupSize from QueryGroup g where g.groupSize > 0", Object[].class)
                .getResultList()) {
            TuningTracker t = byGroup.get((Long) r[0]);
            if (t != null && t.getWorkflowStatus() == WorkflowStatus.ADOPTED) {
                continue;
            }
            allPatterns++;
            allQueries += ((Number) r[1]).longValue();
            if (t == null) {
                allUntracked++;
            }
            Long active = recent.get((Long) r[0]);
            if (active == null) {
                continue;
            }
            outPatterns++;
            outQueries += active;
            if (t == null) {
                untracked++;
            } else if (t.getWorkflowStatus() == WorkflowStatus.SME_VALIDATION) {
                awaiting++;
            } else if (t.getWorkflowStatus() == WorkflowStatus.ON_HOLD || t.getWorkflowStatus() == WorkflowStatus.REJECTED) {
                parked++;
            } else {
                inProgress++;
            }
        }
        Outstanding outstanding = new Outstanding(outPatterns, outQueries, untracked, inProgress, awaiting, parked,
                ACTIVE_DAYS, asOf, allPatterns, allQueries, allUntracked);

        // Q4 prior day: of the patterns that produced bad queries that day, how many are optimized / categorized
        LocalDateTime pf = priorDay.atStartOfDay();
        Map<Long, Long> dayCounts = new HashMap<>();
        for (Object[] r : em.createQuery("select l.groupId, count(l) from QueryLog l where " + TS + " >= :f and " + TS
                        + " < :t and l.groupId is not null group by l.groupId", Object[].class)
                .setParameter("f", pf).setParameter("t", pf.plusDays(1)).getResultList()) {
            dayCounts.put((Long) r[0], (Long) r[1]);
        }
        Set<Long> withTested = testedTrackerIds(all.stream().map(TuningTracker::getId).toList());
        long optP = 0, optQ = 0, notP = 0, notQ = 0, cat = 0, uncat = 0, untrackedDay = 0;
        Map<String, long[]> themes = new TreeMap<>();
        for (var e : dayCounts.entrySet()) {
            TuningTracker t = byGroup.get(e.getKey());
            boolean optimized = t != null && (OPTIMIZED.contains(t.getWorkflowStatus()) || withTested.contains(t.getId()));
            if (optimized) {
                optP++;
                optQ += e.getValue();
                continue;
            }
            notP++;
            notQ += e.getValue();
            String theme = t == null ? NOT_TRACKED : Texts.isBlank(t.getTheme()) ? UNCATEGORIZED : t.getTheme();
            if (t == null) {
                untrackedDay++;
            } else if (Texts.isBlank(t.getTheme())) {
                uncat++;
            } else {
                cat++;
            }
            long[] agg = themes.computeIfAbsent(theme, k -> new long[2]);
            agg[0]++;
            agg[1] += e.getValue();
        }
        List<ThemeCount> themeCounts = themes.entrySet().stream()
                .map(e -> new ThemeCount(e.getKey(), e.getValue()[0], e.getValue()[1]))
                .sorted(Comparator.comparingLong(ThemeCount::patterns).reversed()).toList();
        PriorDay pd = new PriorDay(priorDay, dayCounts.size(), dayCounts.values().stream().mapToLong(Long::longValue).sum(),
                optP, optQ, notP, notQ, cat, uncat, untrackedDay, themeCounts);

        // Q15 awaiting adoption
        LocalDateTime now = LocalDateTime.now();
        List<AwaitingItem> awaitingItems = all.stream()
                .filter(t -> t.getWorkflowStatus() == WorkflowStatus.SME_VALIDATION)
                .sorted(Comparator.comparing(t -> t.getStageChangedAt() == null ? now : t.getStageChangedAt()))
                .map(t -> new AwaitingItem(t.getId(), t.getGroupId(), snippet(t.getGroup().getSampleQuery()), t.getTheme(),
                        t.getStageChangedAt() == null ? null : (int) Duration.between(t.getStageChangedAt(), now).toDays(),
                        TrackerDto.improvement(t.getOgRunDurationMinutes(), t.getPostRunDurationMinutes()),
                        t.getDevTeamLead()))
                .toList();

        // Q15 rejections in the period
        LocalDateTime rf = from.atStartOfDay();
        LocalDateTime rt = to.plusDays(1).atStartOfDay();
        List<Feedback> rejected = em.createQuery("select f from Feedback f where f.decision = :d and f.createdAt >= :f "
                        + "and f.createdAt < :t order by f.createdAt desc", Feedback.class)
                .setParameter("d", Feedback.Decision.REJECTED).setParameter("f", rf).setParameter("t", rt).getResultList();
        Map<Long, Long> trackerOfGroup = all.stream().collect(Collectors.toMap(TuningTracker::getGroupId, TuningTracker::getId));
        Map<String, Long> reasons = new TreeMap<>();
        rejected.forEach(f -> reasons.merge(Texts.isBlank(f.getRejectionReason()) ? "Not specified" : f.getRejectionReason(),
                1L, Long::sum));
        long inaccurate = rejected.stream()
                .filter(f -> f.getRejectionReason() != null && f.getRejectionReason().toLowerCase().contains("inaccura"))
                .count();
        Rejections rej = new Rejections(rejected.size(), inaccurate,
                reasons.entrySet().stream().map(e -> new CountPoint(e.getKey(), e.getValue()))
                        .sorted(Comparator.comparingLong(CountPoint::count).reversed()).toList(),
                rejected.stream().limit(20).map(f -> new RejectionItem(f.getGroupId(), trackerOfGroup.get(f.getGroupId()),
                        f.getIterationId(), f.getRejectionReason(), f.getComments(), f.getSourceRole().name(),
                        f.getCreatedBy(), f.getCreatedAt())).toList());

        return new Pipeline(stages, outstanding, pd, awaitingItems, rej, turnaround(all, from, to));
    }

    /** Q9: end-to-end time of items adopted in the period, plus the average time spent in each stage. */
    private Turnaround turnaround(List<TuningTracker> all, LocalDate from, LocalDate to) {
        LocalDateTime f = from.atStartOfDay();
        LocalDateTime t = to.plusDays(1).atStartOfDay();
        List<TuningTracker> adopted = all.stream()
                .filter(x -> x.getAdoptedAt() != null && !x.getAdoptedAt().isBefore(f) && x.getAdoptedAt().isBefore(t))
                .toList();
        List<Double> days = adopted.stream()
                .map(x -> Duration.between(x.getCreatedAt(), x.getAdoptedAt()).toMinutes() / 1440d).sorted().toList();
        Double fromDetection = adopted.stream()
                .map(x -> {
                    LocalDateTime start = x.getGroup().getFirstSeen() != null && x.getGroup().getFirstSeen().isBefore(x.getCreatedAt())
                            ? x.getGroup().getFirstSeen() : x.getCreatedAt();
                    return Duration.between(start, x.getAdoptedAt()).toMinutes() / 1440d;
                })
                .mapToDouble(Double::doubleValue).average().stream().boxed().findFirst().map(InsightsService::round1).orElse(null);
        Map<WorkflowStatus, List<Double>> perStage = new EnumMap<>(WorkflowStatus.class);
        for (TuningTracker x : adopted) {
            for (TrackerService.JourneyStep s : trackerService.journey(x.getId()).steps()) {
                if (s.daysInStage() != null && s.stage() != WorkflowStatus.ADOPTED) {
                    perStage.computeIfAbsent(s.stage(), k -> new ArrayList<>()).add(s.daysInStage());
                }
            }
        }
        List<StageDuration> byStage = TrackerService.STAGES.stream()
                .filter(s -> s != WorkflowStatus.ADOPTED)
                .map(s -> new StageDuration(s, perStage.containsKey(s)
                        ? round1(perStage.get(s).stream().mapToDouble(Double::doubleValue).average().orElse(0)) : null))
                .toList();
        LocalDateTime now = LocalDateTime.now();
        List<TuningTracker> open = all.stream().filter(x -> x.getClosedAt() == null).toList();
        Double openAge = open.isEmpty() ? null : round1(open.stream()
                .mapToDouble(x -> Duration.between(x.getCreatedAt(), now).toMinutes() / 1440d).average().orElse(0));
        return new Turnaround(adopted.size(),
                days.isEmpty() ? null : round1(days.stream().mapToDouble(Double::doubleValue).average().orElse(0)),
                percentile(days, 50), percentile(days, 90), fromDetection, byStage, open.size(), openAge);
    }

    // =====================================================================================================
    // Q5, Q11 - adoption and savings
    // =====================================================================================================

    public record SavingsItem(Long trackerId, Long groupId, String theme, String sqlSnippet, LocalDateTime adoptedAt,
                              Double perRunMinutesSaved, Double perRunCpuSecondsSaved, Long perRunRowsReduced,
                              Integer perRunTablesAvoided, double baselineRunsPerDay, double runsPerDayAfter,
                              double adoptedDaysInPeriod, double minutesSaved, double cpuSecondsSaved,
                              double rowsReduced, double tableScansAvoided, Double improvementPct) {
    }

    public record ThemeImpact(String theme, long adoptedItems, Double avgImprovementPct, double minutesSaved,
                              double cpuSecondsSaved, double baselineRunsPerDay, double runsPerDayAfter) {
    }

    public record PeriodPoint(String label, double minutesSaved, double cpuSecondsSaved, long adopted) {
    }

    public record Savings(LocalDate from, LocalDate to, long tunedItems, long adoptedTotal, long adoptedInPeriod,
                          long awaitingAdoption, Double adoptionRatePct, double minutesSaved, double cpuSecondsSaved,
                          double rowsScannedReduced, double tableScansAvoided, List<PeriodPoint> byWeek,
                          List<ThemeImpact> themes, List<SavingsItem> items, String method) {
    }

    public Savings savings(LocalDate from, LocalDate to) {
        LocalDateTime pf = from.atStartOfDay();
        LocalDateTime pt = to.plusDays(1).atStartOfDay();
        List<TuningTracker> all = em.createQuery("select t from TuningTracker t join fetch t.group", TuningTracker.class)
                .getResultList();
        Set<Long> tested = testedTrackerIds(all.stream().map(TuningTracker::getId).toList());
        long tuned = all.stream().filter(t -> tested.contains(t.getId()) || OPTIMIZED.contains(t.getWorkflowStatus())).count();
        List<TuningTracker> adopted = all.stream().filter(t -> t.getAdoptedAt() != null).toList();
        long adoptedInPeriod = adopted.stream().filter(t -> !t.getAdoptedAt().isBefore(pf) && t.getAdoptedAt().isBefore(pt)).count();
        long awaiting = all.stream().filter(t -> t.getWorkflowStatus() == WorkflowStatus.SME_VALIDATION).count();

        Map<Long, Map<LocalDate, Long>> runsByGroupDay = runsByGroupDay(adopted.stream().map(TuningTracker::getGroupId).toList(),
                adopted.stream().map(t -> t.getAdoptedAt().minusDays(30)).min(Comparator.naturalOrder()).orElse(pf));
        LocalDateTime now = LocalDateTime.now();
        Map<LocalDate, double[]> perWeek = new TreeMap<>();
        List<SavingsItem> items = new ArrayList<>();
        for (TuningTracker t : adopted) {
            Map<LocalDate, Long> runs = runsByGroupDay.getOrDefault(t.getGroupId(), Map.of());
            LocalDate adoptDay = t.getAdoptedAt().toLocalDate();
            double before = runs.entrySet().stream().filter(e -> !e.getKey().isBefore(adoptDay.minusDays(30))
                    && e.getKey().isBefore(adoptDay)).mapToLong(Map.Entry::getValue).sum() / 30d;
            long daysSince = Math.max(1, ChronoUnit.DAYS.between(adoptDay, now.toLocalDate()));
            double after = runs.entrySet().stream().filter(e -> e.getKey().isAfter(adoptDay))
                    .mapToLong(Map.Entry::getValue).sum() / (double) daysSince;
            LocalDateTime start = t.getAdoptedAt().isAfter(pf) ? t.getAdoptedAt() : pf;
            LocalDateTime end = pt.isBefore(now) ? pt : now;
            double days = end.isAfter(start) ? Duration.between(start, end).toMinutes() / 1440d : 0;
            Double perMin = diff(t.getOgRunDurationMinutes(), t.getPostRunDurationMinutes());
            Double perCpu = diff(t.getOgCpuSeconds(), t.getPostRunCpuSeconds());
            Long perRows = t.getOgRowsScanned() != null && t.getPostRunRowsScanned() != null
                    ? t.getOgRowsScanned() - t.getPostRunRowsScanned() : null;
            Integer perTables = t.getOgTablesScanned() != null && t.getPostRunTablesScanned() != null
                    ? t.getOgTablesScanned() - t.getPostRunTablesScanned() : null;
            double runsInPeriod = before * days;
            SavingsItem item = new SavingsItem(t.getId(), t.getGroupId(), t.getTheme(), snippet(t.getGroup().getSampleQuery()),
                    t.getAdoptedAt(), perMin, perCpu, perRows, perTables, round1(before), round1(after), round1(days),
                    round1(nz(perMin) * runsInPeriod), round1(nz(perCpu) * runsInPeriod),
                    Math.round(nz(perRows) * runsInPeriod), round1(nz(perTables) * runsInPeriod),
                    TrackerDto.improvement(t.getOgRunDurationMinutes(), t.getPostRunDurationMinutes()));
            items.add(item);
            // spread the item's daily savings over the weeks of the period
            for (LocalDate d = start.toLocalDate(); d.isBefore(end.toLocalDate()) && !d.isAfter(to); d = d.plusDays(1)) {
                double[] w = perWeek.computeIfAbsent(d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)),
                        k -> new double[3]);
                w[0] += nz(perMin) * before;
                w[1] += nz(perCpu) * before;
            }
            if (!t.getAdoptedAt().isBefore(pf) && t.getAdoptedAt().isBefore(pt)) {
                perWeek.computeIfAbsent(adoptDay.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)),
                        k -> new double[3])[2]++;
            }
        }
        for (LocalDate w = from.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)); !w.isAfter(to); w = w.plusWeeks(1)) {
            perWeek.putIfAbsent(w, new double[3]);
        }
        List<PeriodPoint> byWeek = perWeek.entrySet().stream()
                .map(e -> new PeriodPoint(e.getKey().toString(), round1(e.getValue()[0]), round1(e.getValue()[1]),
                        (long) e.getValue()[2]))
                .toList();

        Map<String, List<SavingsItem>> perTheme = items.stream()
                .collect(Collectors.groupingBy(i -> Texts.isBlank(i.theme()) ? UNCATEGORIZED : i.theme()));
        List<ThemeImpact> themes = perTheme.entrySet().stream().map(e -> new ThemeImpact(e.getKey(), e.getValue().size(),
                        avg(e.getValue().stream().map(SavingsItem::improvementPct).toList()),
                        round1(e.getValue().stream().mapToDouble(SavingsItem::minutesSaved).sum()),
                        round1(e.getValue().stream().mapToDouble(SavingsItem::cpuSecondsSaved).sum()),
                        round1(e.getValue().stream().mapToDouble(SavingsItem::baselineRunsPerDay).sum()),
                        round1(e.getValue().stream().mapToDouble(SavingsItem::runsPerDayAfter).sum())))
                .sorted(Comparator.comparingDouble(ThemeImpact::minutesSaved).reversed()).toList();

        items.sort(Comparator.comparingDouble(SavingsItem::minutesSaved).reversed());
        return new Savings(from, to, tuned, adopted.size(), adoptedInPeriod, awaiting,
                tuned == 0 ? null : round1(adopted.size() * 100d / tuned),
                round1(items.stream().mapToDouble(SavingsItem::minutesSaved).sum()),
                round1(items.stream().mapToDouble(SavingsItem::cpuSecondsSaved).sum()),
                items.stream().mapToDouble(SavingsItem::rowsReduced).sum(),
                round1(items.stream().mapToDouble(SavingsItem::tableScansAvoided).sum()),
                byWeek, themes, items.stream().limit(50).toList(),
                "Per-run saving (original minus adopted iteration) x the pattern's runs per day in the 30 days "
                        + "before adoption x days since adoption within the period. CPU / rows / table scans need "
                        + "the profile or manual entry on both sides.");
    }

    // =====================================================================================================
    // Q12 - prioritized outstanding themes
    // =====================================================================================================

    public record ThemePriority(int rank, String theme, long outstandingPatterns, long badQueries, double minutes,
                                double expectedImprovementPct, String improvementBasis,
                                double potentialMinutesPerMonth) {
    }

    public List<ThemePriority> themePriorities(int days) {
        LocalDateTime since = LocalDate.now().minusDays(days).atStartOfDay();
        Map<Long, double[]> recent = new HashMap<>(); // groupId -> {count, minutes}
        for (Object[] r : em.createQuery("select l.groupId, count(l), sum(l.durationMinutes) from QueryLog l where " + TS
                        + " >= :f and l.groupId is not null group by l.groupId", Object[].class)
                .setParameter("f", since).getResultList()) {
            recent.put((Long) r[0], new double[] {(Long) r[1], r[2] == null ? 0 : ((Number) r[2]).doubleValue()});
        }
        List<TuningTracker> all = em.createQuery("select t from TuningTracker t", TuningTracker.class).getResultList();
        Map<Long, TuningTracker> byGroup = all.stream().collect(Collectors.toMap(TuningTracker::getGroupId, Function.identity()));
        Map<String, List<Double>> adoptedImprovement = new HashMap<>();
        all.stream().filter(t -> t.getWorkflowStatus() == WorkflowStatus.ADOPTED).forEach(t -> {
            Double imp = TrackerDto.improvement(t.getOgRunDurationMinutes(), t.getPostRunDurationMinutes());
            if (imp != null) {
                adoptedImprovement.computeIfAbsent(Texts.isBlank(t.getTheme()) ? UNCATEGORIZED : t.getTheme(),
                        k -> new ArrayList<>()).add(imp);
            }
        });
        Double globalImp = avg(adoptedImprovement.values().stream().flatMap(List::stream).map(d -> (Double) d).toList());

        Map<String, double[]> agg = new HashMap<>(); // theme -> {patterns, queries, minutes}
        for (var e : recent.entrySet()) {
            TuningTracker t = byGroup.get(e.getKey());
            if (t != null && t.getWorkflowStatus() == WorkflowStatus.ADOPTED) {
                continue;
            }
            String theme = t == null ? NOT_TRACKED : Texts.isBlank(t.getTheme()) ? UNCATEGORIZED : t.getTheme();
            double[] a = agg.computeIfAbsent(theme, k -> new double[3]);
            a[0]++;
            a[1] += e.getValue()[0];
            a[2] += e.getValue()[1];
        }
        List<ThemePriority> out = new ArrayList<>();
        for (var e : agg.entrySet()) {
            Double themeImp = avg(adoptedImprovement.getOrDefault(e.getKey(), List.of()));
            double imp = themeImp != null ? themeImp : globalImp != null ? globalImp : DEFAULT_IMPROVEMENT_PCT;
            String basis = themeImp != null ? "theme history" : globalImp != null ? "all adopted items" : "default";
            double perMonth = e.getValue()[2] * 30d / days * Math.max(0, imp) / 100d;
            out.add(new ThemePriority(0, e.getKey(), (long) e.getValue()[0], (long) e.getValue()[1],
                    round1(e.getValue()[2]), round1(imp), basis, round1(perMonth)));
        }
        out.sort(Comparator.comparingDouble(ThemePriority::potentialMinutesPerMonth).reversed());
        List<ThemePriority> ranked = new ArrayList<>();
        for (int i = 0; i < out.size(); i++) {
            ThemePriority p = out.get(i);
            ranked.add(new ThemePriority(i + 1, p.theme(), p.outstandingPatterns(), p.badQueries(), p.minutes(),
                    p.expectedImprovementPct(), p.improvementBasis(), p.potentialMinutesPerMonth()));
        }
        return ranked;
    }

    // =====================================================================================================
    // Q7, Q13 - users and user groups
    // =====================================================================================================

    public record UserStat(String userId, String displayName, String userGroup, long badQueries, long patterns,
                           long activeDays, double consistencyPct, double minutes, Double avgMinutes,
                           double sharePct, boolean repeatOffender) {
    }

    public record GroupStat(String userGroup, long users, long badQueries, long patterns, double minutes,
                            double sharePct) {
    }

    public record Users(LocalDate from, LocalDate to, long totalBadQueries, long daysInRange, List<UserStat> users,
                        List<GroupStat> groups, long unmappedUsers) {
    }

    public Users users(LocalDate from, LocalDate to) {
        LocalDateTime f = from.atStartOfDay();
        LocalDateTime t = to.plusDays(1).atStartOfDay();
        long daysInRange = ChronoUnit.DAYS.between(from, to) + 1;
        Map<String, long[]> perUser = new HashMap<>(); // {count, activeDays}
        Map<String, Double> minutes = new HashMap<>();
        for (Object[] r : em.createQuery("select l.userId, " + DAY + ", count(l), sum(l.durationMinutes) from QueryLog l"
                        + " where " + TS + " >= :f and " + TS + " < :t and l.userId is not null group by l.userId, " + DAY,
                        Object[].class)
                .setParameter("f", f).setParameter("t", t).getResultList()) {
            long[] a = perUser.computeIfAbsent((String) r[0], k -> new long[2]);
            a[0] += (Long) r[2];
            a[1]++;
            minutes.merge((String) r[0], r[3] == null ? 0d : ((Number) r[3]).doubleValue(), Double::sum);
        }
        Map<String, Long> patterns = new HashMap<>();
        Map<String, Set<Long>> patternSets = new HashMap<>();
        for (Object[] r : em.createQuery("select distinct l.userId, l.groupId from QueryLog l where " + TS + " >= :f and "
                        + TS + " < :t and l.userId is not null and l.groupId is not null", Object[].class)
                .setParameter("f", f).setParameter("t", t).getResultList()) {
            patterns.merge((String) r[0], 1L, Long::sum);
            patternSets.computeIfAbsent((String) r[0], k -> new HashSet<>()).add((Long) r[1]);
        }
        long total = perUser.values().stream().mapToLong(a -> a[0]).sum();
        Map<String, UserDirectoryEntry> dir = directory.findAll().stream()
                .collect(Collectors.toMap(UserDirectoryEntry::getUserId, Function.identity()));
        List<UserStat> users = perUser.entrySet().stream().map(e -> {
                    UserDirectoryEntry d = dir.get(e.getKey());
                    long count = e.getValue()[0];
                    long active = e.getValue()[1];
                    double consistency = round1(active * 100d / daysInRange);
                    double mins = minutes.getOrDefault(e.getKey(), 0d);
                    return new UserStat(e.getKey(), d == null ? null : d.getDisplayName(),
                            d == null || Texts.isBlank(d.getUserGroup()) ? null : d.getUserGroup(), count,
                            patterns.getOrDefault(e.getKey(), 0L), active, consistency, round1(mins),
                            count == 0 ? null : round1(mins / count), total == 0 ? 0 : round1(count * 100d / total),
                            daysInRange >= 5 && consistency >= 50 && count >= 5);
                })
                .sorted(Comparator.comparingLong(UserStat::badQueries).reversed()).toList();
        Map<String, List<UserStat>> perGroup = users.stream()
                .collect(Collectors.groupingBy(u -> u.userGroup() == null ? "Unassigned" : u.userGroup()));
        List<GroupStat> groups = perGroup.entrySet().stream().map(e -> {
                    Set<Long> ps = new HashSet<>();
                    e.getValue().forEach(u -> ps.addAll(patternSets.getOrDefault(u.userId(), Set.of())));
                    long c = e.getValue().stream().mapToLong(UserStat::badQueries).sum();
                    return new GroupStat(e.getKey(), e.getValue().size(), c, ps.size(),
                            round1(e.getValue().stream().mapToDouble(UserStat::minutes).sum()),
                            total == 0 ? 0 : round1(c * 100d / total));
                })
                .sorted(Comparator.comparingLong(GroupStat::badQueries).reversed()).toList();
        long unmapped = users.stream().filter(u -> u.userGroup() == null).count();
        return new Users(from, to, total, daysInRange, users, groups, unmapped);
    }

    // =====================================================================================================
    // Q10, Q14 - month over month trend and request sources
    // =====================================================================================================

    public record MonthPoint(String month, long badQueries, long newPatterns, long adoptedPatterns,
                             long outstandingPatterns, long outstandingBadQueries) {
    }

    public record SourceMonth(String month, Map<RequestSource, Long> created) {
    }

    public record Trends(List<MonthPoint> months, String outstandingDirection, Double outstandingChangePct,
                         List<SourceMonth> requestsByMonth, Map<RequestSource, Long> requestsTotal,
                         Map<RequestSource, Long> requestsAdopted) {
    }

    public Trends trends(int monthCount) {
        YearMonth last = YearMonth.now();
        YearMonth first = last.minusMonths(monthCount - 1L);
        LocalDateTime since = first.atDay(1).atStartOfDay();

        Map<Long, Map<YearMonth, Long>> rows = new HashMap<>();
        for (Object[] r : em.createQuery("select l.groupId, year(" + TS + "), month(" + TS + "), count(l) from QueryLog l "
                        + "where " + TS + " >= :f and l.groupId is not null group by l.groupId, year(" + TS + "), month("
                        + TS + ")", Object[].class)
                .setParameter("f", since).getResultList()) {
            YearMonth ym = YearMonth.of(((Number) r[1]).intValue(), ((Number) r[2]).intValue());
            rows.computeIfAbsent((Long) r[0], k -> new HashMap<>()).merge(ym, (Long) r[3], Long::sum);
        }
        List<Object[]> groups = em.createQuery("select g.id, g.firstSeen from QueryGroup g where g.firstSeen is not null",
                Object[].class).getResultList();
        List<TuningTracker> all = em.createQuery("select t from TuningTracker t", TuningTracker.class).getResultList();
        Map<Long, LocalDateTime> adoptedAt = new HashMap<>();
        all.forEach(t -> {
            if (t.getAdoptedAt() != null) {
                adoptedAt.put(t.getGroupId(), t.getAdoptedAt());
            }
        });

        List<MonthPoint> months = new ArrayList<>();
        for (YearMonth m = first; !m.isAfter(last); m = m.plusMonths(1)) {
            LocalDateTime start = m.atDay(1).atStartOfDay();
            LocalDateTime end = m.plusMonths(1).atDay(1).atStartOfDay();
            final YearMonth month = m;
            long bad = rows.values().stream().mapToLong(x -> x.getOrDefault(month, 0L)).sum();
            long newPatterns = groups.stream().filter(g -> {
                LocalDateTime fs = (LocalDateTime) g[1];
                return !fs.isBefore(start) && fs.isBefore(end);
            }).count();
            long adopted = adoptedAt.values().stream().filter(a -> !a.isBefore(start) && a.isBefore(end)).count();
            long outstanding = 0;
            long outstandingBad = 0;
            for (Object[] g : groups) {
                Long id = (Long) g[0];
                LocalDateTime fs = (LocalDateTime) g[1];
                LocalDateTime a = adoptedAt.get(id);
                if (fs.isBefore(end) && (a == null || !a.isBefore(end))) {
                    outstanding++;
                    outstandingBad += rows.getOrDefault(id, Map.of()).getOrDefault(month, 0L);
                }
            }
            months.add(new MonthPoint(m.toString(), bad, newPatterns, adopted, outstanding, outstandingBad));
        }
        String direction = "flat";
        Double change = null;
        if (months.size() >= 2) {
            long a = months.get(months.size() - 2).outstandingPatterns();
            long b = months.getLast().outstandingPatterns();
            direction = b < a ? "down" : b > a ? "up" : "flat";
            change = a == 0 ? null : round1((b - a) * 100d / a);
        }

        Map<YearMonth, Map<RequestSource, Long>> bySource = new TreeMap<>();
        Map<RequestSource, Long> totals = new EnumMap<>(RequestSource.class);
        Map<RequestSource, Long> adoptedBySource = new EnumMap<>(RequestSource.class);
        for (RequestSource s : RequestSource.values()) {
            totals.put(s, 0L);
            adoptedBySource.put(s, 0L);
        }
        for (TuningTracker t : all) {
            totals.merge(t.getRequestSource(), 1L, Long::sum);
            if (t.getWorkflowStatus() == WorkflowStatus.ADOPTED) {
                adoptedBySource.merge(t.getRequestSource(), 1L, Long::sum);
            }
            YearMonth ym = YearMonth.from(t.getCreatedAt());
            if (!ym.isBefore(first)) {
                bySource.computeIfAbsent(ym, k -> new EnumMap<>(RequestSource.class)).merge(t.getRequestSource(), 1L, Long::sum);
            }
        }
        List<SourceMonth> requests = new ArrayList<>();
        for (YearMonth m = first; !m.isAfter(last); m = m.plusMonths(1)) {
            Map<RequestSource, Long> c = new EnumMap<>(RequestSource.class);
            for (RequestSource s : RequestSource.values()) {
                c.put(s, bySource.getOrDefault(m, Map.of()).getOrDefault(s, 0L));
            }
            requests.add(new SourceMonth(m.toString(), c));
        }
        return new Trends(months, direction, change, requests, totals, adoptedBySource);
    }

    // =====================================================================================================
    // helpers
    // =====================================================================================================

    /** The most recent day with any bad query (null when no logs are loaded). */
    private LocalDate latestDataDay() {
        String day = em.createQuery("select max(" + DAY + ") from QueryLog l", String.class).getSingleResult();
        return day == null ? null : LocalDate.parse(day.substring(0, 10));
    }

    private long count(LocalDateTime from, LocalDateTime to) {
        return em.createQuery("select count(l) from QueryLog l where " + TS + " >= :f and " + TS + " < :t", Long.class)
                .setParameter("f", from).setParameter("t", to).getSingleResult();
    }

    private Map<LocalDate, Long> perDay(LocalDateTime from, LocalDateTime to) {
        Map<LocalDate, Long> out = new HashMap<>();
        for (Object[] r : em.createQuery("select " + DAY + ", count(l) from QueryLog l where " + TS + " >= :f and " + TS
                        + " < :t group by " + DAY, Object[].class)
                .setParameter("f", from).setParameter("t", to).getResultList()) {
            out.put(LocalDate.parse((String) r[0]), (Long) r[1]);
        }
        return out;
    }

    private Map<Long, Map<LocalDate, Long>> runsByGroupDay(Collection<Long> groupIds, LocalDateTime since) {
        Map<Long, Map<LocalDate, Long>> out = new HashMap<>();
        if (groupIds.isEmpty()) {
            return out;
        }
        for (Object[] r : em.createQuery("select l.groupId, " + DAY + ", count(l) from QueryLog l where l.groupId in :ids"
                        + " and " + TS + " >= :f group by l.groupId, " + DAY, Object[].class)
                .setParameter("ids", groupIds).setParameter("f", since).getResultList()) {
            out.computeIfAbsent((Long) r[0], k -> new HashMap<>()).put(LocalDate.parse((String) r[1]), (Long) r[2]);
        }
        return out;
    }

    private Map<Long, QueryGroup> groupsById(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return em.createQuery("select g from QueryGroup g where g.id in :ids", QueryGroup.class)
                .setParameter("ids", ids).getResultList().stream()
                .collect(Collectors.toMap(QueryGroup::getId, Function.identity()));
    }

    private Map<Long, TuningTracker> trackersByGroup(Collection<Long> groupIds) {
        if (groupIds.isEmpty()) {
            return Map.of();
        }
        return em.createQuery("select t from TuningTracker t where t.groupId in :ids", TuningTracker.class)
                .setParameter("ids", groupIds).getResultList().stream()
                .collect(Collectors.toMap(TuningTracker::getGroupId, Function.identity()));
    }

    private Set<Long> testedTrackerIds(Collection<Long> trackerIds) {
        if (trackerIds.isEmpty()) {
            return Set.of();
        }
        return iterations.findByTrackerIdIn(trackerIds).stream()
                .filter(i -> i.getStatus() == TuningIteration.Status.TESTED || i.getStatus() == TuningIteration.Status.SELECTED
                        || i.getStatus() == TuningIteration.Status.ADOPTED)
                .map(TuningIteration::getTrackerId).collect(Collectors.toSet());
    }

    private static String snippet(String sql) {
        return sql == null ? null : Texts.truncate(sql.replaceAll("\\s+", " ").trim(), 160);
    }

    private static Double diff(Double before, Double after) {
        return before == null || after == null ? null : round1(before - after);
    }

    private static double nz(Number n) {
        return n == null ? 0 : n.doubleValue();
    }

    private static Double avg(List<Double> values) {
        List<Double> v = values.stream().filter(Objects::nonNull).toList();
        return v.isEmpty() ? null : round1(v.stream().mapToDouble(Double::doubleValue).average().orElse(0));
    }

    private static Double percentile(List<Double> sorted, int p) {
        if (sorted.isEmpty()) {
            return null;
        }
        int idx = (int) Math.ceil(p / 100d * sorted.size()) - 1;
        return round1(sorted.get(Math.clamp(idx, 0, sorted.size() - 1)));
    }

    static double round1(double v) {
        return Math.round(v * 10d) / 10d;
    }
}
