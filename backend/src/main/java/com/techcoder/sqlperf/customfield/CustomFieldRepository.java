package com.techcoder.sqlperf.customfield;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomFieldRepository extends JpaRepository<CustomField, Long> {

    List<CustomField> findByEntityTypeOrderByDisplayOrderAscIdAsc(CustomField.EntityType entityType);

    boolean existsByEntityTypeAndFieldKey(CustomField.EntityType entityType, String fieldKey);
}
