package com.techcoder.sqlperf.group;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
    private static final int CHUNK = 200;

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

    /** Recomputes the given groups in chunked transactions. Returns the number of groups touched. */
    public int recompute(Collection<FingerprintKey> keys) {
        List<FingerprintKey> list = new ArrayList<>(keys);
        for (int i = 0; i < list.size(); i += CHUNK) {
            List<FingerprintKey> chunk = list.subList(i, Math.min(list.size(), i + CHUNK));
            tx.executeWithoutResult(s -> chunk.forEach(this::recomputeOne));
        }
        return list.size();
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

    private void recomputeOne(FingerprintKey key) {
        QueryGroup g = groups.findBySqlEngineAndFingerprint(key.sqlEngine(), key.fingerprint()).orElseGet(() -> {
            QueryGroup ng = new QueryGroup();
            ng.setSqlEngine(key.sqlEngine());
            ng.setFingerprint(key.fingerprint());
            ng.setCreatedAt(LocalDateTime.now());
            return ng;
        });
        GroupStats st = logs.stats(key.sqlEngine(), key.fingerprint());
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
        g.setUserIds(Texts.truncate(String.join(", ", logs.distinctUsers(key.sqlEngine(), key.fingerprint())), 4000));
        g.setRowIndices(logs.seqIds(key.sqlEngine(), key.fingerprint()).stream()
                .map(String::valueOf).collect(Collectors.joining(",")));

        List<QueryLog> sample = logs.samples(key.sqlEngine(), key.fingerprint(), PageRequest.of(0, 1));
        if (!sample.isEmpty()) {
            QueryLog s = sample.getFirst();
            g.setSampleLogId(s.getId());
            g.setSampleQuerySeqId(s.getSeqId());
            g.setSampleQuery(groupingSql(s));
            g.setNormalizedQuery(fingerprinter.normalize(groupingSql(s)));
        }
        g.setUpdatedAt(LocalDateTime.now());
        groups.save(g);
        logs.assignGroup(key.sqlEngine(), key.fingerprint(), g.getId());
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
