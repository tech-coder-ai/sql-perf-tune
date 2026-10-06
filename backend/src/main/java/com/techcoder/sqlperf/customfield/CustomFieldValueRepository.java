package com.techcoder.sqlperf.customfield;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomFieldValueRepository extends JpaRepository<CustomFieldValue, Long> {

    List<CustomFieldValue> findByFieldIdInAndEntityIdIn(Collection<Long> fieldIds, Collection<Long> entityIds);

    Optional<CustomFieldValue> findByFieldIdAndEntityId(Long fieldId, Long entityId);
}
