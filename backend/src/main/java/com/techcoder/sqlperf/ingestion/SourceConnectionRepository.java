package com.techcoder.sqlperf.ingestion;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SourceConnectionRepository extends JpaRepository<SourceConnection, Long> {
}
