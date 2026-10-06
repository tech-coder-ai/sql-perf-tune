package com.techcoder.sqlperf.group;

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

    public QueryGroupController(QueryGroupRepository groups, QueryLogRepository logs, TuningTrackerRepository trackers,
                                GroupingService grouping, CustomFieldService customFields) {
        this.groups = groups;
        this.logs = logs;
        this.trackers = trackers;
        this.grouping = grouping;
        this.customFields = customFields;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public PageResponse<QueryGroupDto> search(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String sqlEngine,
            @RequestParam(required = false) Integer minGroupSize,
            @RequestParam(required = false) Double minAvgDuration,
            @RequestParam(required = false) Boolean tracked) {
        Specification<QueryGroup> trackedSpec = tracked == null ? null : (root, cq, cb) -> {
            Subquery<Long> sq = cq.subquery(Long.class);
            var t = sq.from(TuningTracker.class);
            sq.select(t.get("groupId")).where(cb.equal(t.get("groupId"), root.get("id")));
            return tracked ? cb.exists(sq) : cb.not(cb.exists(sq));
        };
        var spec = Specs.<QueryGroup>all(
                Specs.like("sampleQuery", q),
                Specs.like("userIds", userId),
                Specs.eq("sqlEngine", sqlEngine),
                Specs.gte("groupSize", minGroupSize),
                Specs.gte("avgDurationMinutes", minAvgDuration),
                Specs.gte("groupSize", 1),
                trackedSpec);
        var pageable = Paging.of(page, size, sort, SORTABLE, Sort.by(Sort.Direction.DESC, "totalDurationMinutes"));
        Page<QueryGroup> result = groups.findAll(spec, pageable);
        return toDtos(result);
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public QueryGroupDto get(@PathVariable Long id) {
        QueryGroup g = groups.findById(id).orElseThrow(() -> new NotFoundException("Query group", id));
        var t = trackers.findByGroupId(id);
        return QueryGroupDto.of(g, t.map(TuningTracker::getId).orElse(null),
                t.map(x -> x.getWorkflowStatus().name()).orElse(null),
                customFields.valuesFor(EntityType.GROUP, List.of(id)).get(id));
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

    private PageResponse<QueryGroupDto> toDtos(Page<QueryGroup> page) {
        List<Long> ids = page.getContent().stream().map(QueryGroup::getId).toList();
        Map<Long, TuningTracker> byGroup = ids.isEmpty() ? Map.of() : trackers.findByGroupIdIn(ids).stream()
                .collect(Collectors.toMap(TuningTracker::getGroupId, Function.identity()));
        Map<Long, Map<String, String>> custom = customFields.valuesFor(EntityType.GROUP, ids);
        return PageResponse.of(page, g -> {
            TuningTracker t = byGroup.get(g.getId());
            return QueryGroupDto.of(g, t == null ? null : t.getId(), t == null ? null : t.getWorkflowStatus().name(),
                    custom.get(g.getId()));
        });
    }
}
