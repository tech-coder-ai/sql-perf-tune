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

/** One execution of the optimization agent (steps 8-9): rendered prompt in, narrative + SQL out. */
@Entity
@Table(name = "SPT_OPTIMIZATION_RUN")
@Getter
@Setter
public class OptimizationRun {

    public enum Status { PENDING, COMPLETED, FAILED }

    public enum Outcome { ACCEPTED, REJECTED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long groupId;

    private Long promptTemplateId;
    private String modelName;

    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    private String promptText;
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    private String responseRaw;
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    private String changeNarrative;
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    private String optimizedSql;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Outcome outcome;

    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    private String errorMessage;

    @Column(nullable = false)
    private LocalDateTime requestedAt;
    private LocalDateTime completedAt;
    private String requestedBy;
}
