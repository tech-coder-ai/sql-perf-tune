package com.techcoder.sqlperf.ingestion;

import org.springframework.data.jpa.repository.JpaRepository;

public interface IngestionBatchRepository extends JpaRepository<IngestionBatch, Long> {
}
