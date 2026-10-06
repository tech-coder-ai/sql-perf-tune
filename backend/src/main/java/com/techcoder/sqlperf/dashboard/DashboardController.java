package com.techcoder.sqlperf.dashboard;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.techcoder.sqlperf.group.QueryGroup;
import com.techcoder.sqlperf.group.QueryGroupRepository;
import com.techcoder.sqlperf.log.QueryLogRepository;
import com.techcoder.sqlperf.tracker.TuningTrackerRepository;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final QueryLogRepository logs;
    private final QueryGroupRepository groups;
    private final TuningTrackerRepository trackers;
    private final EntityManager em;

    public DashboardController(QueryLogRepository logs, QueryGroupRepository groups, TuningTrackerRepository trackers,
                               EntityManager em) {
        this.logs = logs;
        this.groups = groups;
        this.trackers = trackers;
        this.em = em;
    }

    public record TopGroup(Long groupId, int groupSize, Double avgDurationMinutes, Double totalDurationMinutes,
                           String sampleQuery) {
    }

    public record Summary(long logCount, long groupCount, long trackedCount, Double totalDurationMinutes,
                          Map<String, Long> trackerByStatus, List<TopGroup> topGroups, Double savedMinutesPerRun) {
    }

    @GetMapping("/summary")
    @Transactional(readOnly = true)
    public Summary summary() {
        Double total = em.createQuery("select sum(g.totalDurationMinutes) from QueryGroup g", Double.class).getSingleResult();
        Double saved = em.createQuery("""
                select sum(t.ogRunDurationMinutes - t.postRunDurationMinutes) from TuningTracker t
                where t.ogRunDurationMinutes is not null and t.postRunDurationMinutes is not null
                """, Double.class).getSingleResult();
        Map<String, Long> byStatus = new LinkedHashMap<>();
        trackers.countByStatus().forEach(s -> byStatus.put(s.getStatus().name(), s.getCount()));
        List<TopGroup> top = groups.findAll(PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "totalDurationMinutes")))
                .map(DashboardController::top).getContent();
        return new Summary(logs.count(), groups.count(), trackers.count(), total, byStatus, top, saved);
    }

    private static TopGroup top(QueryGroup g) {
        String q = g.getSampleQuery();
        return new TopGroup(g.getId(), g.getGroupSize(), g.getAvgDurationMinutes(), g.getTotalDurationMinutes(),
                q == null ? null : q.length() > 300 ? q.substring(0, 300) + "..." : q);
    }
}
