package com.techcoder.sqlperf.log;

import java.time.LocalDate;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

/**
 * "Had bad queries between two days": the pattern has log rows dated (start time, else load time) from
 * {@code from} 00:00 to the end of {@code to}. The same window the dashboards use for <i>active</i> outstanding
 * patterns (InsightsService.ACTIVE_DAYS), so a list filtered with it matches the number that was clicked.
 */
public final class ActiveWindow {

    private ActiveWindow() {
    }

    /**
     * @param badMinMinutes only runs longer than this count (the bad-query threshold); null = any run
     * @return null when neither bound is set
     */
    public static Predicate of(Expression<Long> groupId, LocalDate from, LocalDate to, Double badMinMinutes,
                               CriteriaQuery<?> cq, CriteriaBuilder cb) {
        if (from == null && to == null) {
            return null;
        }
        Subquery<Long> sq = cq.subquery(Long.class);
        Root<QueryLog> l = sq.from(QueryLog.class);
        Expression<java.time.LocalDateTime> ts = cb.coalesce(l.<java.time.LocalDateTime>get("startTime"),
                l.<java.time.LocalDateTime>get("createdAt"));
        Predicate p = cb.equal(l.get("groupId"), groupId);
        if (badMinMinutes != null) {
            p = cb.and(p, cb.greaterThan(l.<Double>get("durationMinutes"), badMinMinutes));
        }
        if (from != null) {
            p = cb.and(p, cb.greaterThanOrEqualTo(ts, from.atStartOfDay()));
        }
        if (to != null) {
            p = cb.and(p, cb.lessThan(ts, to.plusDays(1).atStartOfDay()));
        }
        sq.select(l.get("id")).where(p);
        return cb.exists(sq);
    }
}
