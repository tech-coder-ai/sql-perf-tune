package com.techcoder.sqlperf.log;

import java.time.LocalDateTime;
import java.util.Set;

import com.techcoder.sqlperf.common.NotFoundException;
import com.techcoder.sqlperf.common.PageResponse;
import com.techcoder.sqlperf.common.Paging;
import com.techcoder.sqlperf.common.Specs;
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
            "errorCode", "errorCategory", "groupId", "batchId", "id");

    private final QueryLogRepository repo;

    public QueryLogController(QueryLogRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public PageResponse<QueryLog> search(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String errorCategory,
            @RequestParam(required = false) String errorCode,
            @RequestParam(required = false) Long groupId,
            @RequestParam(required = false) Long batchId,
            @RequestParam(required = false) Double minDuration,
            @RequestParam(required = false) Boolean errorsOnly,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        var spec = Specs.<QueryLog>all(
                Specs.like("executedQuery", q),
                Specs.like("userId", userId),
                Specs.like("errorCategory", errorCategory),
                Specs.like("errorCode", errorCode),
                Specs.eq("groupId", groupId),
                Specs.eq("batchId", batchId),
                Specs.gte("durationMinutes", minDuration),
                Specs.gte("startTime", from),
                Specs.lte("startTime", to),
                Boolean.TRUE.equals(errorsOnly)
                        ? (root, cq, cb) -> cb.or(cb.isNotNull(root.get("errorCode")), cb.isNotNull(root.get("errorCategory")))
                        : null);
        var pageable = Paging.of(page, size, sort, SORTABLE, Sort.by(Sort.Direction.DESC, "startTime").and(Sort.by("id")));
        return PageResponse.of(repo.findAll(spec, pageable), l -> l);
    }

    /** Single row; {@code groupId} is the drill-up link. */
    @GetMapping("/{id}")
    public QueryLog get(@PathVariable Long id) {
        return repo.findById(id).orElseThrow(() -> new NotFoundException("Query log", id));
    }
}
