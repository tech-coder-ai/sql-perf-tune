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

    // ---- set-based group maintenance: one statement per query for a whole list of fingerprints
    // (callers keep the list under Oracle's 1000-expression IN limit)

    record GroupStats(String fingerprint, long groupSize, long distinctUsers, long durationCount, Double avgDuration,
                      Double minDuration, Double maxDuration, Double totalDuration, long errorCount,
                      LocalDateTime firstSeen, LocalDateTime lastSeen) {
    }

    @Query("""
            select new com.techcoder.sqlperf.log.QueryLogRepository$GroupStats(
                l.fingerprint, count(l), count(distinct l.userId), count(l.durationMinutes),
                avg(l.durationMinutes), min(l.durationMinutes), max(l.durationMinutes), sum(l.durationMinutes),
                sum(case when l.errorCode is not null or l.errorCategory is not null then 1 else 0 end),
                min(l.startTime), max(l.startTime))
            from QueryLog l where l.sqlEngine = :engine and l.fingerprint in :fps
            group by l.fingerprint
            """)
    List<GroupStats> stats(@Param("engine") String engine, @Param("fps") Collection<String> fingerprints);

    /** [fingerprint, userId] pairs, ordered. */
    @Query("""
            select distinct l.fingerprint, l.userId from QueryLog l
            where l.sqlEngine = :engine and l.fingerprint in :fps and l.userId is not null
            order by l.fingerprint, l.userId
            """)
    List<Object[]> distinctUsers(@Param("engine") String engine, @Param("fps") Collection<String> fingerprints);

    /** [fingerprint, seqId] pairs, ordered. */
    @Query("""
            select l.fingerprint, l.seqId from QueryLog l
            where l.sqlEngine = :engine and l.fingerprint in :fps and l.seqId is not null
            order by l.fingerprint, l.seqId
            """)
    List<Object[]> seqIds(@Param("engine") String engine, @Param("fps") Collection<String> fingerprints);

    /**
     * [fingerprint, id] of every member, longest running first within a fingerprint (rows without a duration
     * last): the first id per fingerprint is the group's sample ("bad SQL").
     */
    @Query("""
            select l.fingerprint, l.id from QueryLog l where l.sqlEngine = :engine and l.fingerprint in :fps
            order by l.fingerprint, case when l.durationMinutes is null then 1 else 0 end, l.durationMinutes desc, l.id
            """)
    List<Object[]> sampleCandidates(@Param("engine") String engine, @Param("fps") Collection<String> fingerprints);

    /** Points every row of the fingerprints at its group. */
    @Modifying
    @Query("""
            update QueryLog l set l.groupId = (
                select g.id from QueryGroup g where g.sqlEngine = l.sqlEngine and g.fingerprint = l.fingerprint)
            where l.sqlEngine = :engine and l.fingerprint in :fps
              and (l.groupId is null or l.groupId <> (
                select g2.id from QueryGroup g2 where g2.sqlEngine = l.sqlEngine and g2.fingerprint = l.fingerprint))
            """)
    int assignGroups(@Param("engine") String engine, @Param("fps") Collection<String> fingerprints);

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
