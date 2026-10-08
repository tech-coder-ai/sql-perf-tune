package com.techcoder.sqlperf.dashboard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.techcoder.sqlperf.common.Specs;
import com.techcoder.sqlperf.common.Texts;
import com.techcoder.sqlperf.group.QueryGroup;
import com.techcoder.sqlperf.group.QueryGroupRepository;
import com.techcoder.sqlperf.log.QueryLog;
import com.techcoder.sqlperf.log.QueryLogRepository;
import com.techcoder.sqlperf.tracker.TuningTracker;
import com.techcoder.sqlperf.tracker.TuningTrackerRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Global "find a SQL" box: T-12 (tracker), #5 / G-5 (group), a seq_id, or text in the SQL. */
@RestController
public class SearchController {

    private static final Pattern TRACKER = Pattern.compile("(?i)^t-?(\\d+)$");
    private static final Pattern GROUP = Pattern.compile("(?i)^(?:#|g-?)(\\d+)$");
    private static final Pattern NUMBER = Pattern.compile("^\\d+$");

    private final TuningTrackerRepository trackers;
    private final QueryGroupRepository groups;
    private final QueryLogRepository logs;

    public SearchController(TuningTrackerRepository trackers, QueryGroupRepository groups, QueryLogRepository logs) {
        this.trackers = trackers;
        this.groups = groups;
        this.logs = logs;
    }

    public record Hit(String type, Long id, String title, String subtitle, String status, Long trackerId, Long groupId) {
    }

    @GetMapping("/api/search")
    @Transactional(readOnly = true)
    public List<Hit> search(@RequestParam String q) {
        String s = q == null ? "" : q.trim();
        if (s.isEmpty()) {
            return List.of();
        }
        Map<String, Hit> hits = new LinkedHashMap<>();
        Matcher m = TRACKER.matcher(s);
        if (m.matches()) {
            trackers.findById(Long.valueOf(m.group(1))).ifPresent(t -> add(hits, tracker(t)));
        }
        m = GROUP.matcher(s);
        if (m.matches()) {
            groups.findById(Long.valueOf(m.group(1))).ifPresent(g -> add(hits, group(g)));
        }
        if (NUMBER.matcher(s).matches()) {
            long n = Long.parseLong(s);
            trackers.findById(n).ifPresent(t -> add(hits, tracker(t)));
            groups.findById(n).ifPresent(g -> add(hits, group(g)));
            logs.findAll(Specs.<QueryLog>eq("seqId", n), PageRequest.of(0, 3)).forEach(l -> add(hits, log(l)));
        }
        if (s.length() >= 3) {
            groups.findAll(Specs.<QueryGroup>like("sampleQuery", s),
                            PageRequest.of(0, 8, Sort.by(Sort.Direction.DESC, "totalDurationMinutes")))
                    .forEach(g -> add(hits, group(g)));
            trackers.findAll(Specs.<TuningTracker>like("theme", s), PageRequest.of(0, 5))
                    .forEach(t -> add(hits, tracker(t)));
        }
        return new ArrayList<>(hits.values()).subList(0, Math.min(12, hits.size()));
    }

    private static void add(Map<String, Hit> hits, Hit h) {
        hits.putIfAbsent(h.type() + h.id(), h);
    }

    private Hit tracker(TuningTracker t) {
        return new Hit("TRACKER", t.getId(), "T-" + t.getId() + " · group #" + t.getGroupId(),
                snippet(t.getGroup() == null ? null : t.getGroup().getSampleQuery()), t.getWorkflowStatus().name(),
                t.getId(), t.getGroupId());
    }

    private Hit group(QueryGroup g) {
        Long trackerId = trackers.findByGroupId(g.getId()).map(TuningTracker::getId).orElse(null);
        String status = trackers.findByGroupId(g.getId()).map(t -> t.getWorkflowStatus().name()).orElse(null);
        return new Hit("GROUP", g.getId(), "Group #" + g.getId() + " · " + g.getGroupSize() + " runs",
                snippet(g.getSampleQuery()), status, trackerId, g.getId());
    }

    private Hit log(QueryLog l) {
        Long trackerId = l.getGroupId() == null ? null
                : trackers.findByGroupId(l.getGroupId()).map(TuningTracker::getId).orElse(null);
        return new Hit("LOG", l.getId(), "Log seq " + l.getSeqId() + (l.getUserId() == null ? "" : " · " + l.getUserId()),
                snippet(l.getExecutedQuery() == null ? l.getUserQuery() : l.getExecutedQuery()), null, trackerId,
                l.getGroupId());
    }

    private static String snippet(String sql) {
        return sql == null ? null : Texts.truncate(sql.replaceAll("\\s+", " ").trim(), 140);
    }
}
