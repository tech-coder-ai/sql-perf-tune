package com.techcoder.sqlperf.group;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.techcoder.sqlperf.common.Texts;
import com.techcoder.sqlperf.config.SptProperties;
import com.techcoder.sqlperf.fingerprint.SqlFingerprinter;
import com.techcoder.sqlperf.log.QueryLog;
import com.techcoder.sqlperf.log.QueryLogRepository;
import com.techcoder.sqlperf.log.QueryLogRepository.FingerprintKey;
import com.techcoder.sqlperf.log.QueryLogRepository.GroupStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Maintains {@code SPT_QUERY_GROUP}: each group's metrics are recomputed from all member log rows, so
 * re-imports and overlapping files never double count.
 */
@Service
public class GroupingService {

    private static final Logger log = LoggerFactory.getLogger(GroupingService.class);
    /** fingerprints per set-based recompute (stays under Oracle's 1000-expression IN list limit) */
    private static final int CHUNK = 500;

    private final QueryLogRepository logs;
    private final QueryGroupRepository groups;
    private final SqlFingerprinter fingerprinter;
    private final SptProperties props;
    private final TransactionTemplate tx;

    public GroupingService(QueryLogRepository logs, QueryGroupRepository groups, SqlFingerprinter fingerprinter,
                           SptProperties props, TransactionTemplate tx) {
        this.logs = logs;
        this.groups = groups;
        this.fingerprinter = fingerprinter;
        this.props = props;
        this.tx = tx;
    }

    /** SQL text that is hashed for a log row. */
    public String groupingSql(QueryLog row) {
        String executed = row.getExecutedQuery();
        String user = row.getUserQuery();
        if (props.fingerprint().preferUserQuery()) {
            return Texts.isBlank(user) ? executed : user;
        }
        return Texts.isBlank(executed) ? user : executed;
    }

    public void applyFingerprint(QueryLog row) {
        String sql = groupingSql(row);
        row.setFingerprint(Texts.isBlank(sql) ? null : fingerprinter.fingerprint(sql).fingerprint());
    }

    /**
     * Recomputes the given groups in chunked transactions. Returns the number of groups touched.
     * Each chunk costs a fixed handful of statements (not several per group): on Oracle every statement is a
     * network round trip, so this is what keeps imports fast.
     */
    public int recompute(Collection<FingerprintKey> keys) {
        Map<String, List<String>> byEngine = keys.stream().collect(Collectors.groupingBy(FingerprintKey::sqlEngine,
                LinkedHashMap::new, Collectors.mapping(FingerprintKey::fingerprint, Collectors.toList())));
        byEngine.forEach((engine, fps) -> {
            for (int i = 0; i < fps.size(); i += CHUNK) {
                List<String> chunk = fps.subList(i, Math.min(fps.size(), i + CHUNK));
                tx.executeWithoutResult(s -> recomputeChunk(engine, chunk));
            }
        });
        return keys.size();
    }

    /**
     * Re-fingerprints every log row (after changing {@code spt.fingerprint.*}) and rebuilds all groups.
     * Groups that end up empty keep their tracker history but show size 0.
     */
    public int rebuildAll() {
        long afterId = 0;
        while (true) {
            final long from = afterId;
            Long last = tx.execute(s -> {
                List<QueryLog> page = logs.pageAfter(from, PageRequest.of(0, 1000));
                for (QueryLog row : page) {
                    applyFingerprint(row);
                    if (row.getFingerprint() == null) {
                        row.setGroupId(null);
                    }
                }
                return page.isEmpty() ? null : page.getLast().getId();
            });
            if (last == null) {
                break;
            }
            afterId = last;
        }
        Set<FingerprintKey> keys = new HashSet<>(logs.allFingerprintKeys());
        int touched = recompute(keys);
        tx.executeWithoutResult(s -> groups.findAll().stream()
                .filter(g -> !keys.contains(new FingerprintKey(g.getSqlEngine(), g.getFingerprint())))
                .forEach(GroupingService::clearStats));
        log.info("Rebuilt {} query groups", touched);
        return touched;
    }

    private void recomputeChunk(String engine, List<String> fps) {
        Map<String, QueryGroup> existing = groups.findBySqlEngineAndFingerprintIn(engine, fps).stream()
                .collect(Collectors.toMap(QueryGroup::getFingerprint, Function.identity()));
        Map<String, GroupStats> stats = logs.stats(engine, fps).stream()
                .collect(Collectors.toMap(GroupStats::fingerprint, Function.identity()));
        Map<String, List<String>> users = pairs(logs.distinctUsers(engine, fps));
        Map<String, List<String>> seqIds = pairs(logs.seqIds(engine, fps));
        Map<String, Long> sampleIds = new HashMap<>();
        for (Object[] row : logs.sampleCandidates(engine, fps)) {
            sampleIds.putIfAbsent((String) row[0], (Long) row[1]);
        }
        Map<Long, QueryLog> samples = logs.findAllById(sampleIds.values()).stream()
                .collect(Collectors.toMap(QueryLog::getId, Function.identity()));

        LocalDateTime now = LocalDateTime.now();
        List<QueryGroup> changed = new ArrayList<>();
        for (String fp : fps) {
            QueryGroup g = existing.get(fp);
            GroupStats st = stats.get(fp);
            if (st == null) {
                // no member rows left (e.g. after a fingerprint rule change): keep the group, show it empty
                if (g != null) {
                    clearStats(g);
                    changed.add(g);
                }
                continue;
            }
            if (g == null) {
                g = new QueryGroup();
                g.setSqlEngine(engine);
                g.setFingerprint(fp);
                g.setCreatedAt(now);
            }
            g.setGroupSize((int) st.groupSize());
            g.setDistinctUsers((int) st.distinctUsers());
            g.setDurationCount((int) st.durationCount());
            g.setAvgDurationMinutes(round(st.avgDuration()));
            g.setMinDurationMinutes(round(st.minDuration()));
            g.setMaxDurationMinutes(round(st.maxDuration()));
            g.setTotalDurationMinutes(round(st.totalDuration()));
            g.setErrorCount((int) st.errorCount());
            g.setFirstSeen(st.firstSeen());
            g.setLastSeen(st.lastSeen());
            g.setUserIds(Texts.truncate(String.join(", ", users.getOrDefault(fp, List.of())), 4000));
            g.setRowIndices(String.join(",", seqIds.getOrDefault(fp, List.of())));
            QueryLog sample = samples.get(sampleIds.get(fp));
            if (sample != null) {
                g.setSampleLogId(sample.getId());
                g.setSampleQuerySeqId(sample.getSeqId());
                g.setSampleQuery(groupingSql(sample));
                g.setNormalizedQuery(fingerprinter.normalize(groupingSql(sample)));
            }
            g.setUpdatedAt(now);
            changed.add(g);
        }
        groups.saveAll(changed); // batched: pooled ids, see V5__pooled_ids.sql
        groups.flush();
        logs.assignGroups(engine, fps);
    }

    /** [fingerprint, value] rows (ordered by fingerprint) -> fingerprint -> values in order. */
    private static Map<String, List<String>> pairs(List<Object[]> rows) {
        Map<String, List<String>> out = new HashMap<>();
        for (Object[] row : rows) {
            out.computeIfAbsent((String) row[0], k -> new ArrayList<>()).add(String.valueOf(row[1]));
        }
        return out;
    }

    private static void clearStats(QueryGroup g) {
        g.setGroupSize(0);
        g.setDistinctUsers(0);
        g.setDurationCount(0);
        g.setAvgDurationMinutes(null);
        g.setMinDurationMinutes(null);
        g.setMaxDurationMinutes(null);
        g.setTotalDurationMinutes(null);
        g.setErrorCount(0);
        g.setRowIndices(null);
        g.setUpdatedAt(LocalDateTime.now());
    }

    private static Double round(Double v) {
        return v == null ? null : Math.round(v * 10_000d) / 10_000d;
    }
}
