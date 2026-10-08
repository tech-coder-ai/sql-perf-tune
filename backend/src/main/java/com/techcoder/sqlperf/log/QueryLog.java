package com.techcoder.sqlperf.log;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.Getter;
import lombok.Setter;

/** One executed statement from the input log (file row or source table row). */
@Entity
@Table(name = "SPT_QUERY_LOG")
@Getter
@Setter
public class QueryLog {

    @Id
    // IDENTITY on SQLite; on Oracle META-INF/orm-oracle.xml switches to pooled sequence ids (batched inserts)
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long batchId;

    private Long seqId;

    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    private String executedQuery;

    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    private String userQuery;

    private String errorCode;
    private String errorCategory;

    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    private String errorMessage;

    private String userId;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Double durationMinutes;

    @Column(nullable = false, length = 20)
    private String sqlEngine;

    @Column(length = 64)
    private String fingerprint;

    /** Drill-up link to {@code SPT_QUERY_GROUP}. */
    private Long groupId;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    /** Identity hash of the row; a row with an existing key is never processed again. */
    @Column(length = 64)
    private String rowKey;

    /** Number of loads this row appeared in (1 = loaded once). */
    private int seenCount = 1;

    private LocalDateTime lastSeenAt;
    private Long lastSeenBatchId;
}
