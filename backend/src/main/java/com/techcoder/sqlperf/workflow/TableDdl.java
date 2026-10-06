package com.techcoder.sqlperf.workflow;

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

/** DDL and row count for a table referenced by the bad SQL (agent input, step 8). */
@Entity
@Table(name = "SPT_TABLE_DDL")
@Getter
@Setter
public class TableDdl {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long groupId;

    @Column(nullable = false, length = 300)
    private String tableName;

    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    private String ddlText;

    private Long rowCount;

    @Column(nullable = false)
    private LocalDateTime capturedAt;
    private String capturedBy;
}
