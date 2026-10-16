package com.techcoder.sqlperf.group;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import com.techcoder.sqlperf.settings.WorkflowSettings;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

/**
 * What a query pattern is for the tuning workflow (steps 1-2), from its bad runs (longer than the bad-query
 * threshold) and the admin settings:
 * <ul>
 *   <li>BAD: at least {@code candidate.min-runs} bad runs: a tuning candidate;</li>
 *   <li>ADHOC: bad, but ran only once so far ("run once / adhoc-only": no sense tuning it yet);</li>
 *   <li>ADHOC_CLOSED: adhoc-only and not seen again for {@code adhoc.close-days};</li>
 *   <li>NORMAL: never ran longer than the threshold.</li>
 * </ul>
 * Derived on read, so changing a setting re-classifies every pattern at once.
 */
@Component
public class PatternClassifier {

    public enum PatternClass { BAD, ADHOC, ADHOC_CLOSED, NORMAL }

    private final EntityManager em;
    private final WorkflowSettings settings;

    public PatternClassifier(EntityManager em, WorkflowSettings settings) {
        this.em = em;
        this.settings = settings;
    }

    public PatternClass classify(long badRuns, LocalDateTime lastSeen) {
        if (badRuns <= 0) {
            return PatternClass.NORMAL;
        }
        if (badRuns >= settings.candidateMinRuns()) {
            return PatternClass.BAD;
        }
        return lastSeen != null && lastSeen.isBefore(LocalDateTime.now().minusDays(settings.adhocCloseDays()))
                ? PatternClass.ADHOC_CLOSED : PatternClass.ADHOC;
    }

    /** groupId → number of bad runs. */
    public Map<Long, Long> badRuns(Collection<Long> groupIds) {
        Map<Long, Long> out = new HashMap<>();
        if (groupIds.isEmpty()) {
            return out;
        }
        for (Object[] r : em.createQuery("select l.groupId, count(l) from QueryLog l where l.groupId in :ids and "
                        + "l.durationMinutes > :bad group by l.groupId", Object[].class)
                .setParameter("ids", groupIds).setParameter("bad", settings.badMinMinutes()).getResultList()) {
            out.put((Long) r[0], (Long) r[1]);
        }
        return out;
    }
}
