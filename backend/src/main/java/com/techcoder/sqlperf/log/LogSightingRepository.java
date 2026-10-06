package com.techcoder.sqlperf.log;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LogSightingRepository extends JpaRepository<LogSighting, Long> {

    List<LogSighting> findByLogIdOrderBySeenAtDescIdDesc(Long logId);

    long countByBatchId(Long batchId);

    /** Re-load of an identical file: every row seen by the processing import is seen again by the new one. */
    @Modifying
    @Query(value = """
            INSERT INTO SPT_QUERY_LOG_SIGHTING (LOG_ID, BATCH_ID, SEEN_AT, IS_FIRST)
            SELECT s.LOG_ID, :newBatch, :seenAt, 0 FROM SPT_QUERY_LOG_SIGHTING s WHERE s.BATCH_ID = :fromBatch
            """, nativeQuery = true)
    int copyFromBatch(@Param("fromBatch") Long fromBatch, @Param("newBatch") Long newBatch,
                      @Param("seenAt") LocalDateTime seenAt);
}
