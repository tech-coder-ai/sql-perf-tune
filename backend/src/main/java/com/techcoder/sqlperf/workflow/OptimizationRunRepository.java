package com.techcoder.sqlperf.workflow;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OptimizationRunRepository extends JpaRepository<OptimizationRun, Long> {

    List<OptimizationRun> findByGroupIdOrderByIdDesc(Long groupId);

    long countByGroupIdAndIdLessThan(Long groupId, Long id);

    List<OptimizationRun> findByStatusAndModelName(OptimizationRun.Status status, String modelName);
}
