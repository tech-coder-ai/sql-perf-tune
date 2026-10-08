package com.techcoder.sqlperf.workflow;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.Getter;
import lombok.Setter;

/** Output of the SQL Diagnostic Tool for one run of the original (step 5) or optimized (step 10) SQL. */
@Entity
@Table(name = "SPT_SQL_DIAGNOSTIC")
@Getter
@Setter
public class SqlDiagnostic {

    public enum Phase { ORIGINAL, OPTIMIZED }

    public enum Status { CAPTURED, FAILED, TIMEOUT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long groupId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Phase phase;

    private Long optimizationRunId;
    private String queryId;

    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    private String sqlText;

    private Long rowCount;
    private Double runDurationSeconds;

    /** The tuning iteration this post-run test belongs to (phase OPTIMIZED). */
    private Long iterationId;
    private Double executionTimeSeconds;
    private Double teardownTimeSeconds;
    private Double cpuSeconds;
    private Long rowsScanned;
    private Long bytesScanned;
    private Integer tablesScanned;
    private Double peakMemoryMb;

    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    private String explainPlan;
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    private String profileRaw;
    /** JSON from {@link ImpalaProfileParser}. */
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    private String profileSummary;
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    private String execSummary;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.CAPTURED;

    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    private String errorMessage;

    @Column(nullable = false)
    private LocalDateTime capturedAt;
    private String capturedBy;
}
