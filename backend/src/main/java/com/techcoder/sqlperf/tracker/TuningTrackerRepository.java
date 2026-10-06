package com.techcoder.sqlperf.tracker;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface TuningTrackerRepository extends JpaRepository<TuningTracker, Long>, JpaSpecificationExecutor<TuningTracker> {

    Optional<TuningTracker> findByGroupId(Long groupId);

    @Override
    @EntityGraph(attributePaths = "group")
    Page<TuningTracker> findAll(Specification<TuningTracker> spec, Pageable pageable);

    @Override
    @EntityGraph(attributePaths = "group")
    List<TuningTracker> findAll(Specification<TuningTracker> spec, Sort sort);

    List<TuningTracker> findByGroupIdIn(Collection<Long> groupIds);

    interface StatusCount {
        TuningTracker.WorkflowStatus getStatus();

        long getCount();
    }

    @Query("select t.workflowStatus as status, count(t) as count from TuningTracker t group by t.workflowStatus")
    List<StatusCount> countByStatus();
}
