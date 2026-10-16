package com.techcoder.sqlperf.group;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.techcoder.sqlperf.common.NotFoundException;
import com.techcoder.sqlperf.common.PageResponse;
import com.techcoder.sqlperf.common.Paging;
import com.techcoder.sqlperf.common.Specs;
import com.techcoder.sqlperf.customfield.CustomField.EntityType;
import com.techcoder.sqlperf.customfield.CustomFieldService;
import com.techcoder.sqlperf.log.QueryLog;
import com.techcoder.sqlperf.log.QueryLogRepository;
import com.techcoder.sqlperf.tracker.TuningTracker;
import com.techcoder.sqlperf.tracker.TuningTrackerRepository;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import com.techcoder.sqlperf.common.RowGrouping;
import com.techcoder.sqlperf.log.ActiveWindow;
import com.techcoder.sqlperf.log.QueryLog;
import com.techcoder.sqlperf.settings.WorkflowSettings;
import org.springframework.format.annotation.DateTimeFormat;
import jakarta.persistence.EntityManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Grouping screen: groups (parents) with expandable member log rows (children). */
@RestController
@RequestMapping("/api/groups")
public class QueryGroupController {

    private static final Set<String> SORTABLE = Set.of("id", "groupSize", "distinctUsers", "durationCount",
            "avgDurationMinutes", "minDurationMinutes", "maxDurationMinutes", "totalDurationMinutes", "errorCount",
            "lastSeen", "firstSeen", "sampleQuerySeqId");
    private static final Set<String> LOG_SORTABLE = Set.of("id", "seqId", "userId", "startTime", "durationMinutes");

    private final QueryGroupRepository groups;
    private final QueryLogRepository logs;
    private final TuningTrackerRepository trackers;
    private final GroupingService grouping;
    private final CustomFieldService customFields;
    private final EntityManager em;
    private final WorkflowSettings settings;
    private final PatternClassifier classifier;

    public QueryGroupController(QueryGroupRepository groups, QueryLogRepository logs, TuningTrackerRepository trackers,
                                GroupingService grouping, CustomFieldService customFields, EntityManager em,
                                WorkflowSettings settings, PatternClassifier classifier) {
        this.em = em;
        this.settings = settings;
        this.classifier = classifier;
        this.groups = groups;
        this.logs = logs;
        this.trackers = trackers;
        this.grouping = grouping;
        this.customFields = customFields;
    }

    /**
     * @param activeFrom / activeTo only groups with bad queries between these days (both inclusive)
     * @param includeNormal also patterns that never ran longer than the bad-query threshold (default: bad only)
     * @param pattern       BAD (tuning candidates, enough bad runs), ADHOC (a single bad run), ADHOC_CLOSED (single bad
     *                      run, not seen for the closing period); empty = any
     */
    public record GroupFilter(String q, String userId, String sqlEngine, Integer minGroupSize, Double minAvgDuration,
                              Boolean tracked,
                              @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate activeFrom,
                              @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate activeTo,
                              Boolean includeNormal, String pattern) {
    }

    /** Columns the grid can group by. */
    static final Map<String, RowGrouping.Column<QueryGroup>> GROUPABLE = Map.of(
            "sqlEngine", RowGrouping.Column.text("sqlEngine"),
            "firstSeenDay", RowGrouping.Column.day("firstSeen"),
            "lastSeenDay", RowGrouping.Column.day("lastSeen"),
            "groupSize", RowGrouping.Column.number("groupSize"),
            "distinctUsers", RowGrouping.Column.number("distinctUsers"));

    @GetMapping
    @Transactional(readOnly = true)
    public PageResponse<QueryGroupDto> search(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) List<String> gk,
            GroupFilter f) {
        var pageable = Paging.of(page, size, sort, SORTABLE, Sort.by(Sort.Direction.DESC, "totalDurationMinutes"));
        Page<QueryGroup> result = groups.findAll(spec(f, gk), pageable);
        return toDtos(result);
    }

    /** Row grouping: distinct values of {@code by} with their group counts under the same filters. */
    @GetMapping("/buckets")
    @Transactional(readOnly = true)
    public PageResponse<RowGrouping.Bucket> buckets(
            @RequestParam String by,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) List<String> gk,
            GroupFilter f) {
        boolean byValue = sort != null && sort.startsWith(by + ",");
        boolean desc = sort == null || !byValue || sort.endsWith(",desc");
        return RowGrouping.buckets(em, QueryGroup.class, spec(f, gk), GROUPABLE, by, byValue, desc, page, size);
    }

    private Specification<QueryGroup> spec(GroupFilter f, List<String> gk) {
        double bad = settings.badMinMinutes();
        Boolean tracked = f.tracked();
        Specification<QueryGroup> trackedSpec = tracked == null ? null : (root, cq, cb) -> {
            Subquery<Long> sq = cq.subquery(Long.class);
            var t = sq.from(TuningTracker.class);
            sq.select(t.get("groupId")).where(cb.equal(t.get("groupId"), root.get("id")));
            return tracked ? cb.exists(sq) : cb.not(cb.exists(sq));
        };
        return Specs.<QueryGroup>all(
                Specs.like("sampleQuery", f.q()),
                Specs.like("userIds", f.userId()),
                Specs.eq("sqlEngine", f.sqlEngine()),
                Specs.gte("groupSize", f.minGroupSize()),
                Specs.gte("avgDurationMinutes", f.minAvgDuration()),
                Specs.gte("groupSize", 1),
                trackedSpec,
                f.activeFrom() == null && f.activeTo() == null ? null
                        : (root, cq, cb) -> ActiveWindow.of(root.get("id"), f.activeFrom(), f.activeTo(),
                                Boolean.TRUE.equals(f.includeNormal()) ? null : bad, cq, cb),
                Boolean.TRUE.equals(f.includeNormal()) ? null
                        : (root, cq, cb) -> cb.greaterThan(root.<Double>get("maxDurationMinutes"), bad),
                patternSpec(f.pattern(), bad),
                RowGrouping.within(RowGrouping.parse(gk, GROUPABLE), GROUPABLE));
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public QueryGroupDto get(@PathVariable Long id) {
        QueryGroup g = groups.findById(id).orElseThrow(() -> new NotFoundException("Query group", id));
        var t = trackers.findByGroupId(id);
        long b = classifier.badRuns(List.of(id)).getOrDefault(id, 0L);
        return QueryGroupDto.of(g, t.map(TuningTracker::getId).orElse(null),
                t.map(x -> x.getWorkflowStatus().name()).orElse(null),
                customFields.valuesFor(EntityType.GROUP, List.of(id)).get(id), b, classifier.classify(b, g.getLastSeen()));
    }

    /** Drill down: the member log rows of a group. */
    @GetMapping("/{id}/logs")
    public PageResponse<QueryLog> members(@PathVariable Long id,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size,
                                          @RequestParam(required = false) String sort) {
        var pageable = Paging.of(page, size, sort, LOG_SORTABLE, Sort.by(Sort.Direction.DESC, "durationMinutes"));
        return PageResponse.of(logs.findByGroupId(id, pageable), l -> l);
    }

    @PutMapping("/{id}/custom-fields")
    public QueryGroupDto saveCustomFields(@PathVariable Long id, @RequestBody Map<String, String> values) {
        groups.findById(id).orElseThrow(() -> new NotFoundException("Query group", id));
        customFields.saveValues(EntityType.GROUP, id, values);
        return get(id);
    }

    /** Re-fingerprint all logs and rebuild every group (after changing fingerprint settings). */
    @PostMapping("/rebuild")
    public Map<String, Integer> rebuild() {
        return Map.of("groups", grouping.rebuildAll());
    }

    /** Bad runs of a group (runs longer than the threshold), as a subquery. */
    private static jakarta.persistence.criteria.Expression<Long> badRuns(jakarta.persistence.criteria.Root<QueryGroup> root,
            jakarta.persistence.criteria.CriteriaQuery<?> cq, jakarta.persistence.criteria.CriteriaBuilder cb, double bad) {
        Subquery<Long> sq = cq.subquery(Long.class);
        var l = sq.from(QueryLog.class);
        sq.select(cb.count(l)).where(cb.equal(l.get("groupId"), root.get("id")),
                cb.greaterThan(l.<Double>get("durationMinutes"), bad));
        return sq;
    }

    private Specification<QueryGroup> patternSpec(String pattern, double bad) {
        if (pattern == null || pattern.isBlank()) {
            return null;
        }
        int minRuns = settings.candidateMinRuns();
        java.time.LocalDateTime closedBefore = java.time.LocalDateTime.now().minusDays(settings.adhocCloseDays());
        return switch (pattern.toUpperCase(java.util.Locale.ROOT)) {
            case "BAD" -> (root, cq, cb) -> cb.greaterThanOrEqualTo(badRuns(root, cq, cb, bad), (long) minRuns);
            case "ADHOC" -> (root, cq, cb) -> cb.and(cb.lessThan(badRuns(root, cq, cb, bad), (long) minRuns),
                    cb.greaterThan(badRuns(root, cq, cb, bad), 0L), cb.greaterThanOrEqualTo(root.get("lastSeen"), closedBefore));
            case "ADHOC_CLOSED" -> (root, cq, cb) -> cb.and(cb.lessThan(badRuns(root, cq, cb, bad), (long) minRuns),
                    cb.greaterThan(badRuns(root, cq, cb, bad), 0L), cb.lessThan(root.get("lastSeen"), closedBefore));
            case "NORMAL" -> (root, cq, cb) -> cb.equal(badRuns(root, cq, cb, bad), 0L);
            default -> throw new IllegalArgumentException("pattern must be BAD, ADHOC, ADHOC_CLOSED or NORMAL");
        };
    }

    private PageResponse<QueryGroupDto> toDtos(Page<QueryGroup> page) {
        List<Long> ids = page.getContent().stream().map(QueryGroup::getId).toList();
        Map<Long, TuningTracker> byGroup = ids.isEmpty() ? Map.of() : trackers.findByGroupIdIn(ids).stream()
                .collect(Collectors.toMap(TuningTracker::getGroupId, Function.identity()));
        Map<Long, Map<String, String>> custom = customFields.valuesFor(EntityType.GROUP, ids);
        Map<Long, Long> bad = classifier.badRuns(ids);
        return PageResponse.of(page, g -> {
            TuningTracker t = byGroup.get(g.getId());
            long b = bad.getOrDefault(g.getId(), 0L);
            return QueryGroupDto.of(g, t == null ? null : t.getId(), t == null ? null : t.getWorkflowStatus().name(),
                    custom.get(g.getId()), b, classifier.classify(b, g.getLastSeen()));
        });
    }
}
