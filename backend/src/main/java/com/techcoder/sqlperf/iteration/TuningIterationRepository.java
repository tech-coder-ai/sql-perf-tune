package com.techcoder.sqlperf.iteration;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TuningIterationRepository extends JpaRepository<TuningIteration, Long> {

    List<TuningIteration> findByTrackerIdOrderByIterationNoAsc(Long trackerId);

    List<TuningIteration> findByTrackerIdIn(Collection<Long> trackerIds);

    Optional<TuningIteration> findFirstByTrackerIdOrderByIterationNoDesc(Long trackerId);

    Optional<TuningIteration> findByOptimizationRunId(Long optimizationRunId);

    long countByTrackerId(Long trackerId);

    interface TrackerCount {
        Long getTrackerId();

        long getCount();
    }

    @Query("select i.trackerId as trackerId, count(i) as count from TuningIteration i where i.trackerId in :ids group by i.trackerId")
    List<TrackerCount> countByTrackerIds(@Param("ids") Collection<Long> trackerIds);
}
