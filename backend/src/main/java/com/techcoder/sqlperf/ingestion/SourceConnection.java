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
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.Getter;
import lombok.Setter;

/** A database the query log can be pulled from (Oracle audit table, Impala query log table, ...). */
@Entity
@Table(name = "SPT_DATA_SOURCE")
@Getter
@Setter
public class SourceConnection {

    public enum SourceType { ORACLE, IMPALA, GENERIC_JDBC }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SourceType sourceType;

    @Column(nullable = false, length = 20)
    private String sqlEngine = "IMPALA";

    @Column(nullable = false, length = 1000)
    private String jdbcUrl;

    private String driverClass;
    private String username;

    /** Name of the env var / property that holds the password. */
    private String passwordRef;

    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    @Column(nullable = false)
    private String logQuery;

    private int fetchSize = 1000;
    private LocalDateTime lastWatermark;
    private boolean active = true;
    private String description;

    @Column(nullable = false)
    private LocalDateTime createdAt;
    private String createdBy;
    private LocalDateTime updatedAt;
    private String updatedBy;

    @Version
    private int version;
}
