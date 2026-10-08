package com.techcoder.sqlperf.log;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QueryLogRepository extends JpaRepository<QueryLog, Long>, JpaSpecificationExecutor<QueryLog> {

    Page<QueryLog> findByGroupId(Long groupId, Pageable pageable);

    long countByBatchId(Long batchId);

    record GroupStats(long groupSize, long distinctUsers, long durationCount, Double avgDuration,
                      Double minDuration, Double maxDuration, Double totalDuration, long errorCount,
                      LocalDateTime firstSeen, LocalDateTime lastSeen) {
    }

    @Query("""
            select new com.techcoder.sqlperf.log.QueryLogRepository$GroupStats(
                count(l), count(distinct l.userId), count(l.durationMinutes),
                avg(l.durationMinutes), min(l.durationMinutes), max(l.durationMinutes), sum(l.durationMinutes),
                sum(case when l.errorCode is not null or l.errorCategory is not null then 1 else 0 end),
                min(l.startTime), max(l.startTime))
            from QueryLog l where l.sqlEngine = :engine and l.fingerprint = :fp
            """)
    GroupStats stats(@Param("engine") String engine, @Param("fp") String fingerprint);

    @Query("""
            select distinct l.userId from QueryLog l
            where l.sqlEngine = :engine and l.fingerprint = :fp and l.userId is not null
            order by l.userId
            """)
    List<String> distinctUsers(@Param("engine") String engine, @Param("fp") String fingerprint);

    @Query("""
            select l.seqId from QueryLog l
            where l.sqlEngine = :engine and l.fingerprint = :fp and l.seqId is not null
            order by l.seqId
            """)
    List<Long> seqIds(@Param("engine") String engine, @Param("fp") String fingerprint);

    /** Longest running member first; rows without a duration last. */
    @Query("""
            select l from QueryLog l where l.sqlEngine = :engine and l.fingerprint = :fp
            order by case when l.durationMinutes is null then 1 else 0 end, l.durationMinutes desc, l.id
            """)
    List<QueryLog> samples(@Param("engine") String engine, @Param("fp") String fingerprint, Pageable pageable);

    @Modifying
    @Query("""
            update QueryLog l set l.groupId = :groupId
            where l.sqlEngine = :engine and l.fingerprint = :fp and (l.groupId is null or l.groupId <> :groupId)
            """)
    int assignGroup(@Param("engine") String engine, @Param("fp") String fingerprint, @Param("groupId") Long groupId);

    record FingerprintKey(String sqlEngine, String fingerprint) {
    }

    @Query("""
            select distinct new com.techcoder.sqlperf.log.QueryLogRepository$FingerprintKey(l.sqlEngine, l.fingerprint)
            from QueryLog l where l.fingerprint is not null
            """)
    List<FingerprintKey> allFingerprintKeys();

    /** Fingerprinted rows that never got their group (e.g. the service stopped in the middle of an import). */
    @Query("""
            select distinct new com.techcoder.sqlperf.log.QueryLogRepository$FingerprintKey(l.sqlEngine, l.fingerprint)
            from QueryLog l where l.fingerprint is not null and l.groupId is null
            """)
    List<FingerprintKey> orphanFingerprintKeys();

    List<QueryLog> findByRowKeyIn(Collection<String> rowKeys);

    @Query("select l from QueryLog l where l.rowKey is null order by l.id")
    List<QueryLog> withoutRowKey(Pageable pageable);

    /** Marks rows seen again by a load (used for identical-file re-loads). */
    @Modifying
    @Query("""
            update QueryLog l set l.seenCount = l.seenCount + 1, l.lastSeenAt = :seenAt, l.lastSeenBatchId = :batchId
            where l.id in (select s.logId from LogSighting s where s.batchId = :batchId)
            """)
    int markSeenByBatch(@Param("batchId") Long batchId, @Param("seenAt") LocalDateTime seenAt);

    @Query("select l from QueryLog l where l.id > :afterId order by l.id")
    List<QueryLog> pageAfter(@Param("afterId") long afterId, Pageable pageable);
}
