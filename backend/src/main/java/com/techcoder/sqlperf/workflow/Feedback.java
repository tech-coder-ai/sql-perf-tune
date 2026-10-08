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

/** Adoption / rejection feedback (step 12 and "Failed Optimization" note) fed back into prompt tuning. */
@Entity
@Table(name = "SPT_FEEDBACK")
@Getter
@Setter
public class Feedback {

    public enum SourceRole { BUSINESS_USER, CLIENT_DEV, CLOUDERA, SME }

    public enum Decision { ADOPTED, REJECTED, COMMENT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long groupId;

    private Long optimizationRunId;

    /** The tuning iteration the decision is about. */
    private Long iterationId;

    /** Lookup REJECTION_REASON (e.g. "Inaccurate results") for REJECTED decisions. */
    private String rejectionReason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private SourceRole sourceRole;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Decision decision;

    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    private String comments;

    @Column(nullable = false)
    private LocalDateTime createdAt;
    private String createdBy;
}
