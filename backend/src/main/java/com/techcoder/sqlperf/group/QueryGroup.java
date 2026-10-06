package com.techcoder.sqlperf.group;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.Getter;
import lombok.Setter;

/** Aggregate of all log rows sharing a fingerprint. Recomputed whenever new rows arrive. */
@Entity
@Table(name = "SPT_QUERY_GROUP")
@Getter
@Setter
public class QueryGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String fingerprint;

    @Column(nullable = false, length = 20)
    private String sqlEngine;

    private int groupSize;
    private int distinctUsers;

    @Column(length = 4000)
    private String userIds;

    private int durationCount;
    private Double avgDurationMinutes;
    private Double minDurationMinutes;
    private Double maxDurationMinutes;
    private Double totalDurationMinutes;
    private int errorCount;

    private Long sampleLogId;
    private Long sampleQuerySeqId;

    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    private String sampleQuery;

    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    private String normalizedQuery;

    /** Comma separated SEQ_IDs of member rows. */
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    private String rowIndices;

    private LocalDateTime firstSeen;
    private LocalDateTime lastSeen;

    @Column(nullable = false)
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Version
    private int version;
}
