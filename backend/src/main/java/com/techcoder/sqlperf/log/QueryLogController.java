package com.techcoder.sqlperf.log;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.techcoder.sqlperf.ingestion.IngestionBatch;
import com.techcoder.sqlperf.ingestion.IngestionBatchRepository;

import com.techcoder.sqlperf.common.NotFoundException;
import com.techcoder.sqlperf.common.PageResponse;
import com.techcoder.sqlperf.common.Paging;
import com.techcoder.sqlperf.common.RowGrouping;
import com.techcoder.sqlperf.common.Specs;
import com.techcoder.sqlperf.common.Texts;
import com.techcoder.sqlperf.group.QueryGroup;
import com.techcoder.sqlperf.users.UserDirectoryEntry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Query log screen (lowest drill level). */
@RestController
@RequestMapping("/api/logs")
public class QueryLogController {

    private static final Set<String> SORTABLE = Set.of("seqId", "userId", "startTime", "endTime", "durationMinutes",
            "errorCode", "errorCategory", "groupId", "batchId", "id", "seenCount", "lastSeenAt", "createdAt");

    /** Q7's name for users without a user group. */
    private static final String UNASSIGNED = "Unassigned";

    private final QueryLogRepository repo;
    private final LogSightingRepository sightings;
    private final IngestionBatchRepository batches;
    private final EntityManager em;

    public QueryLogController(QueryLogRepository repo, LogSightingRepository sightings, IngestionBatchRepository batches,
                              EntityManager em) {
        this.repo = repo;
        this.sightings = sightings;
        this.batches = batches;
        this.em = em;
    }

    public record LoadHistoryEntry(Long batchId, LocalDateTime seenAt, boolean first, String sourceKind,
                                   String sourceName, Long duplicateOfBatchId) {
    }

    /** Every load this row appeared in; only the first one processed it. */
    @GetMapping("/{id}/history")
    public List<LoadHistoryEntry> history(@PathVariable Long id) {
        repo.findById(id).orElseThrow(() -> new NotFoundException("Query log", id));
        List<LogSighting> list = sightings.findByLogIdOrderBySeenAtDescIdDesc(id);
        Map<Long, IngestionBatch> byId = batches.findAllById(list.stream().map(LogSighting::getBatchId).toList())
                .stream().collect(Collectors.toMap(IngestionBatch::getId, Function.identity()));
        return list.stream().map(s -> {
            IngestionBatch b = byId.get(s.getBatchId());
            return new LoadHistoryEntry(s.getBatchId(), s.getSeenAt(), s.isFirst(),
                    b == null ? null : b.getSourceKind().name(), b == null ? null : b.getSourceName(),
                    b == null ? null : b.getDuplicateOfBatchId());
        }).toList();
    }

    /**
     * The screen's filters. Times filter the row's date as the dashboards count it: start time, or load time when
     * the row has no start time.
     *
     * @param user      exact user (links from the dashboards); {@code userId} is a "contains" search
     * @param userGroup user group from Administration → Users &amp; groups; "Unassigned" = users without one
     * @param pattern   RECURRING: the row's pattern was first seen before the {@code from} day; NEW: everything else
     */
    public record LogFilter(String q, String userId, String user, String userGroup, String errorCategory,
                            String errorCode, Long groupId, Long batchId, Double minDuration, Boolean errorsOnly,
                            Boolean reloadedOnly,
                            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
                            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
                            String pattern) {
    }

    /** Columns the grid can group by. */
    static final Map<String, RowGrouping.Column<QueryLog>> GROUPABLE = Map.of(
            "userId", RowGrouping.Column.text("userId"),
            "errorCode", RowGrouping.Column.text("errorCode"),
            "errorCategory", RowGrouping.Column.text("errorCategory"),
            "groupId", RowGrouping.Column.number("groupId"),
            "batchId", RowGrouping.Column.number("batchId"),
            "sqlEngine", RowGrouping.Column.text("sqlEngine"),
            "startDay", RowGrouping.Column.day("startTime", "createdAt"));

    @GetMapping
    public PageResponse<QueryLog> search(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) List<String> gk,
            LogFilter f) {
        var pageable = Paging.of(page, size, sort, SORTABLE, Sort.by(Sort.Direction.DESC, "startTime").and(Sort.by("id")));
        return PageResponse.of(repo.findAll(spec(f, gk), pageable), l -> l);
    }

    /** Row grouping: distinct values of {@code by} with their row counts under the same filters. */
    @GetMapping("/buckets")
    public PageResponse<RowGrouping.Bucket> buckets(
            @RequestParam String by,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) List<String> gk,
            LogFilter f) {
        boolean byValue = sort != null && sort.startsWith(by + ",");
        boolean desc = sort == null || !byValue || sort.endsWith(",desc");
        return RowGrouping.buckets(em, QueryLog.class, spec(f, gk), GROUPABLE, by, byValue, desc, page, size);
    }

    static Specification<QueryLog> spec(LogFilter f, List<String> gk) {
        return Specs.<QueryLog>all(
                Specs.like("executedQuery", f.q()),
                Specs.like("userId", f.userId()),
                Specs.eq("userId", Texts.trimToNull(f.user())),
                Specs.like("errorCategory", f.errorCategory()),
                Specs.like("errorCode", f.errorCode()),
                Specs.eq("groupId", f.groupId()),
                Specs.eq("batchId", f.batchId()),
                Specs.gte("durationMinutes", f.minDuration()),
                f.from() == null ? null : (root, cq, cb) -> cb.greaterThanOrEqualTo(day(root, cb), f.from()),
                f.to() == null ? null : (root, cq, cb) -> cb.lessThanOrEqualTo(day(root, cb), f.to()),
                Boolean.TRUE.equals(f.reloadedOnly()) ? (root, cq, cb) -> cb.greaterThan(root.get("seenCount"), 1) : null,
                Boolean.TRUE.equals(f.errorsOnly())
                        ? (root, cq, cb) -> cb.or(cb.isNotNull(root.get("errorCode")), cb.isNotNull(root.get("errorCategory")))
                        : null,
                userGroup(Texts.trimToNull(f.userGroup())),
                pattern(f.pattern(), f.from()),
                RowGrouping.within(RowGrouping.parse(gk, GROUPABLE), GROUPABLE));
    }

    /** The timestamp the dashboards date a row by. */
    private static Expression<LocalDateTime> day(Root<QueryLog> root, CriteriaBuilder cb) {
        return cb.coalesce(root.<LocalDateTime>get("startTime"), root.<LocalDateTime>get("createdAt"));
    }

    private static Specification<QueryLog> userGroup(String group) {
        if (group == null) {
            return null;
        }
        return (root, cq, cb) -> {
            Subquery<String> sq = cq.subquery(String.class);
            Root<UserDirectoryEntry> u = sq.from(UserDirectoryEntry.class);
            if (UNASSIGNED.equalsIgnoreCase(group)) {
                // length(trim(..)) > 0 also works on Oracle, where '' is NULL
                sq.select(u.get("userId")).where(cb.greaterThan(cb.length(cb.trim(u.<String>get("userGroup"))), 0));
                return cb.or(cb.isNull(root.get("userId")), cb.not(root.get("userId").in(sq)));
            }
            sq.select(u.get("userId")).where(cb.equal(u.get("userGroup"), group));
            return root.get("userId").in(sq);
        };
    }

    /** Recurring = the row's pattern was first seen before the day of {@code from} (as on the Command center). */
    private static Specification<QueryLog> pattern(String pattern, LocalDateTime from) {
        if (Texts.isBlank(pattern)) {
            return null;
        }
        if (from == null) {
            throw new IllegalArgumentException("pattern needs a from date");
        }
        boolean recurring = pattern.equalsIgnoreCase("RECURRING");
        if (!recurring && !pattern.equalsIgnoreCase("NEW")) {
            throw new IllegalArgumentException("pattern must be RECURRING or NEW");
        }
        LocalDateTime dayStart = from.toLocalDate().atStartOfDay();
        return (root, cq, cb) -> {
            Subquery<Long> sq = cq.subquery(Long.class);
            Root<QueryGroup> g = sq.from(QueryGroup.class);
            sq.select(g.get("id")).where(cb.lessThan(g.get("firstSeen"), dayStart));
            var isRecurring = root.get("groupId").in(sq);
            return recurring ? isRecurring : cb.or(cb.isNull(root.get("groupId")), cb.not(isRecurring));
        };
    }

    /** Single row; {@code groupId} is the drill-up link. */
    @GetMapping("/{id}")
    public QueryLog get(@PathVariable Long id) {
        return repo.findById(id).orElseThrow(() -> new NotFoundException("Query log", id));
    }
}
