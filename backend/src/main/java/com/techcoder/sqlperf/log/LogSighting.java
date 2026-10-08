package com.techcoder.sqlperf.log;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Load history: one row per (log row, load) the row appeared in. */
@Entity
@Table(name = "SPT_QUERY_LOG_SIGHTING")
@Getter
@Setter
public class LogSighting {

    @Id
    // IDENTITY on SQLite; on Oracle META-INF/orm-oracle.xml switches to pooled sequence ids (batched inserts)
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long logId;

    @Column(nullable = false)
    private Long batchId;

    @Column(nullable = false)
    private LocalDateTime seenAt;

    /** true for the load that inserted and processed the row. */
    @Column(name = "is_first")
    private boolean first;
}
