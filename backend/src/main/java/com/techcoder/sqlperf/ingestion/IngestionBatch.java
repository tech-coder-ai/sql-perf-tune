package com.techcoder.sqlperf.ingestion;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "SPT_INGESTION_BATCH")
@Getter
@Setter
public class IngestionBatch {

    public enum SourceKind { CSV, EXCEL, JDBC }

    public enum Status { RUNNING, COMPLETED, COMPLETED_WITH_ERRORS, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SourceKind sourceKind;

    private Long dataSourceId;
    private String sourceName;

    @Column(nullable = false, length = 20)
    private String sqlEngine;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Status status;

    private int rowsRead;
    private int rowsLoaded;
    private int rowsRejected;
    private int groupsAffected;

    @Column(length = 4000)
    private String message;

    @Column(nullable = false)
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private String createdBy;
}
