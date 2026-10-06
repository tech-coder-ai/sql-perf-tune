package com.techcoder.sqlperf.workflow;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SqlDiagnosticRepository extends JpaRepository<SqlDiagnostic, Long> {

    List<SqlDiagnostic> findByGroupIdOrderByIdDesc(Long groupId);
}
