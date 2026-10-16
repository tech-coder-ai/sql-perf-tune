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
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    List<TuningTracker> findByCaptureStatusIn(Collection<TuningTracker.CaptureStatus> statuses);

    /**
     * Capture bookkeeping as a bulk update: it does not bump the row version, so an edit the user started
     * while the capture ran still saves.
     */
    @Modifying(flushAutomatically = true)
    @Query("update TuningTracker t set t.captureStatus = :status, t.captureFinishedAt = :finishedAt,"
            + " t.captureMessage = :message where t.id = :id")
    int setCapture(@Param("id") Long id, @Param("status") TuningTracker.CaptureStatus status,
                   @Param("finishedAt") java.time.LocalDateTime finishedAt, @Param("message") String message);

    @Modifying(flushAutomatically = true)
    @Query("update TuningTracker t set t.captureStatus = :status, t.captureRequestedAt = :requestedAt,"
            + " t.captureFinishedAt = null, t.captureMessage = null where t.id = :id")
    int requestCapture(@Param("id") Long id, @Param("status") TuningTracker.CaptureStatus status,
                       @Param("requestedAt") java.time.LocalDateTime requestedAt);

    /** Automatic first run and comparison bookkeeping (bulk updates: no row-version bump, see setCapture). */
    @Modifying(flushAutomatically = true)
    @Query("update TuningTracker t set t.autoRunStatus = :status, t.autoRunMessage = :message where t.id = :id")
    int setAutoRun(@Param("id") Long id, @Param("status") TuningTracker.AutoRunStatus status,
                   @Param("message") String message);

    @Modifying(flushAutomatically = true)
    @Query("update TuningTracker t set t.resultStatus = :status where t.id = :id")
    int setResultStatus(@Param("id") Long id, @Param("status") TuningTracker.ResultStatus status);

    @Modifying(flushAutomatically = true)
    @Query("update TuningTracker t set t.notifiedAt = :at where t.id = :id")
    int setNotifiedAt(@Param("id") Long id, @Param("at") java.time.LocalDateTime at);
}
