package com.techcoder.sqlperf.ingestion;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** A distinct uploaded file content and how many times it was loaded. */
@Entity
@Table(name = "SPT_SOURCE_FILE")
@Getter
@Setter
public class SourceFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String contentHash;

    private String fileName;
    private Long fileSize;
    private int loadCount = 1;

    /** The import that parsed the file; later identical uploads reuse its rows. */
    @Column(nullable = false)
    private Long processedBatchId;

    @Column(nullable = false)
    private Long lastBatchId;

    @Column(nullable = false)
    private LocalDateTime firstLoadedAt;

    @Column(nullable = false)
    private LocalDateTime lastLoadedAt;
}
