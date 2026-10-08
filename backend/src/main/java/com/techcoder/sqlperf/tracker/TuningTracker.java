package com.techcoder.sqlperf.tracker;

import java.time.LocalDateTime;

import com.techcoder.sqlperf.group.QueryGroup;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.Getter;
import lombok.Setter;

/**
 * Tracking-screen record for a query group. Group metrics (size, durations, sample query, ...) are not
 * copied here; they are joined live from {@code SPT_QUERY_GROUP} so the tracker never goes stale.
 */
@Entity
@Table(name = "SPT_TUNING_TRACKER")
@Getter
@Setter
public class TuningTracker {

    public enum WorkflowStatus {
        NEW, DIAGNOSTICS_CAPTURED, OPTIMIZATION_REQUESTED, OPTIMIZED, POST_RUN_VALIDATED, SME_VALIDATION,
        ADOPTED, REJECTED, ON_HOLD
    }

    public enum Priority { LOW, MEDIUM, HIGH, CRITICAL }

    /** Where the tuning request came from (Q14: proactive vs user requested vs detected in logs). */
    public enum RequestSource { LOG_DETECTED, PROACTIVE_UAT, USER_REQUEST }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id", nullable = false)
    private Long groupId;

    /** Read-only association used for joins / sorting on group metrics; write through {@link #groupId}. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id", insertable = false, updatable = false)
    private QueryGroup group;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private WorkflowStatus workflowStatus = WorkflowStatus.NEW;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Priority priority = Priority.MEDIUM;

    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    private String sampleQueryFormatted;
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    private String cleansedQuery;
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    private String optimizedQuery;

    private String devTeamLead;
    private String devTeamStatus;
    private String clouderaTeamLead;
    private String smeTeamLead;
    private String optimizedSqlStatus;
    private String clouderaPostRunValidation;

    private Double ogRunDurationMinutes;
    private Double postRunDurationMinutes;
    private Double ogExecutionTimeSeconds;
    private Double postRunExecutionTimeSeconds;
    private Double ogTeardownTimeSeconds;
    private Double postRunTeardownTimeSeconds;
    private Double ogTeardownPct;
    private Double postRunTeardownPct;
    private Double ogCpuSeconds;
    private Double postRunCpuSeconds;
    private Long ogRowsScanned;
    private Long postRunRowsScanned;
    private Integer ogTablesScanned;
    private Integer postRunTablesScanned;
    private Long ogBytesScanned;
    private Long postRunBytesScanned;
    private Double ogPeakMemoryMb;
    private Double postRunPeakMemoryMb;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private RequestSource requestSource = RequestSource.LOG_DETECTED;

    private String requestedBy;
    private String environment;

    /** Iteration chosen as the best result (copied into the post-run metrics and optimized query). */
    private Long selectedIterationId;

    /** When the item entered its current workflow status. */
    private LocalDateTime stageChangedAt;
    private LocalDateTime adoptedAt;
    /** Set when the item reaches ADOPTED or REJECTED. */
    private LocalDateTime closedAt;

    private String theme;
    private String smeValidation;
    private String installStatus;
    private String executeStatus;
    private String validationStatus;

    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    private String changes;
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    private String problem;
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    private String recommendations;

    @Column(nullable = false)
    private LocalDateTime createdAt;
    private String createdBy;
    private LocalDateTime updatedAt;
    private String updatedBy;

    @Version
    private int version;
}
