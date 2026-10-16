package com.techcoder.sqlperf.autorun;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.techcoder.sqlperf.iteration.IterationService;
import com.techcoder.sqlperf.iteration.TuningIteration;
import com.techcoder.sqlperf.iteration.TuningIterationRepository;
import com.techcoder.sqlperf.settings.WorkflowSettings;
import com.techcoder.sqlperf.tracker.TuningTracker;
import com.techcoder.sqlperf.tracker.TuningTracker.ResultStatus;
import com.techcoder.sqlperf.tracker.TuningTrackerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Step 5 "Evaluate results": after a test, where does the item go? The best iteration (fastest tested one with the
 * same result) is compared with the original run:
 * <ul>
 *   <li>faster by more than the adoption threshold (Administration, default 15%) → READY_FOR_ADOPTION (users);</li>
 *   <li>tested, but not faster enough → NEEDS_SME_REVIEW (tuning team);</li>
 *   <li>every tested iteration returned a different result or failed → FAILED (tuning team).</li>
 * </ul>
 * The result is stored on the item ({@code RESULT_STATUS}) for the persona views.
 */
@Service
public class ComparisonModule {

    /** @param gainPct run-time reduction of the best iteration, in percent */
    public record Decision(ResultStatus status, TuningIteration best, Double gainPct, double thresholdPct, String reason) {
    }

    private final TuningTrackerRepository trackers;
    private final TuningIterationRepository iterations;
    private final WorkflowSettings settings;

    public ComparisonModule(TuningTrackerRepository trackers, TuningIterationRepository iterations,
                            WorkflowSettings settings) {
        this.trackers = trackers;
        this.iterations = iterations;
        this.settings = settings;
    }

    /** @return empty while nothing has been tested yet */
    @Transactional
    public Optional<Decision> evaluate(Long trackerId) {
        TuningTracker t = trackers.findById(trackerId).orElse(null);
        if (t == null) {
            return Optional.empty();
        }
        List<TuningIteration> all = iterations.findByTrackerIdOrderByIterationNoAsc(trackerId);
        double threshold = settings.adoptionThresholdPct();
        Optional<TuningIteration> best = IterationService.best(all);
        Decision d;
        if (best.isPresent()) {
            Double gain = gain(t.getOgRunDurationMinutes(), best.get().getRunDurationMinutes());
            if (gain != null && gain > threshold) {
                d = new Decision(ResultStatus.READY_FOR_ADOPTION, best.get(), gain, threshold,
                        fmt(gain) + " faster (more than " + fmt(threshold) + ")");
            } else {
                d = new Decision(ResultStatus.NEEDS_SME_REVIEW, best.get(), gain, threshold,
                        (gain == null ? "no original run time to compare with"
                                : "only " + fmt(gain) + " faster (needs more than " + fmt(threshold) + ")"));
            }
        } else if (all.stream().anyMatch(i -> i.getTestedAt() != null || i.getStatus() == TuningIteration.Status.FAILED)) {
            d = new Decision(ResultStatus.FAILED, null, null, threshold,
                    "no tested iteration returns the same result as the original");
        } else {
            return Optional.empty();
        }
        trackers.setResultStatus(trackerId, d.status());
        return Optional.of(d);
    }

    static Double gain(Double original, Double tuned) {
        return original == null || tuned == null || original <= 0 ? null
                : Math.round((original - tuned) / original * 1000d) / 10d;
    }

    private static String fmt(double pct) {
        return String.format(Locale.ROOT, "%.1f%%", pct);
    }
}
