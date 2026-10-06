package com.techcoder.sqlperf.workflow;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TableDdlRepository extends JpaRepository<TableDdl, Long> {

    List<TableDdl> findByGroupIdOrderByIdDesc(Long groupId);
}
