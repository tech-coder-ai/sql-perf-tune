package com.techcoder.sqlperf.workflow;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PromptTemplateRepository extends JpaRepository<PromptTemplate, Long> {

    List<PromptTemplate> findAllByOrderByNameAscVersionNoDesc();

    Optional<PromptTemplate> findFirstBySqlEngineAndActiveTrueOrderByVersionNoDesc(String sqlEngine);

    Optional<PromptTemplate> findFirstByNameOrderByVersionNoDesc(String name);
}
