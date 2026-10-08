package com.techcoder.sqlperf.iteration;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One tuning attempt for a tracker item: candidate SQL (from the AI agent or written by hand) plus the
 * metrics of its test run. The best iteration is selected for adoption; users then adopt or reject it.
 */
@Entity
@Table(name = "SPT_TUNING_ITERATION")
@Getter
@Setter
public class TuningIteration {

    public enum Source { AI_AGENT, MANUAL }

    /** PROPOSED -> TESTED / FAILED -> SELECTED -> ADOPTED / REJECTED. */
    public enum Status { PROPOSED, TESTED, FAILED, SELECTED, ADOPTED, REJECTED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long trackerId;

    @Column(nullable = false)
    private int iterationNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Source source = Source.MANUAL;

    private Long optimizationRunId;

    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    private String optimizedSql;

    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    private String changeNarrative;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PROPOSED;

    private Double runDurationMinutes;
    private Double executionTimeSeconds;
    private Double teardownTimeSeconds;
    private Double cpuSeconds;
    private Long rowsScanned;
    private Long bytesScanned;
    private Integer tablesScanned;
    private Double peakMemoryMb;
    private Long resultRowCount;
    /** Result set identical to the original (row count / reconciliation); false disqualifies the iteration. */
    private Boolean resultMatches;

    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    private String notes;

    private LocalDateTime testedAt;
    private String testedBy;

    @Column(nullable = false)
    private LocalDateTime createdAt;
    private String createdBy;
    private LocalDateTime updatedAt;

    @Version
    private int version;
}
