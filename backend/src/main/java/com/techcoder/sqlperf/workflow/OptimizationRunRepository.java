package com.techcoder.sqlperf.workflow;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OptimizationRunRepository extends JpaRepository<OptimizationRun, Long> {

    List<OptimizationRun> findByGroupIdOrderByIdDesc(Long groupId);
}
