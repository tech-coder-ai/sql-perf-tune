package com.techcoder.sqlperf.audit;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    List<AuditEvent> findByEntityTypeAndEntityIdOrderByChangedAtDescIdDesc(String entityType, Long entityId);
}
