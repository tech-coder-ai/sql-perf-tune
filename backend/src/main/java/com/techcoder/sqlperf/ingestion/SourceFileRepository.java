package com.techcoder.sqlperf.ingestion;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SourceFileRepository extends JpaRepository<SourceFile, Long> {

    Optional<SourceFile> findByContentHash(String contentHash);
}
