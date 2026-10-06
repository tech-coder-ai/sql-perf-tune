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
            "errorCode", "errorCategory", "groupId", "batchId", "id", "seenCount", "lastSeenAt", "createdAt");

    private final QueryLogRepository repo;
    private final LogSightingRepository sightings;
    private final IngestionBatchRepository batches;

    public QueryLogController(QueryLogRepository repo, LogSightingRepository sightings, IngestionBatchRepository batches) {
        this.repo = repo;
        this.sightings = sightings;
        this.batches = batches;
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
            @RequestParam(required = false) Boolean reloadedOnly,
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
                Boolean.TRUE.equals(reloadedOnly) ? (root, cq, cb) -> cb.greaterThan(root.get("seenCount"), 1) : null,
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
