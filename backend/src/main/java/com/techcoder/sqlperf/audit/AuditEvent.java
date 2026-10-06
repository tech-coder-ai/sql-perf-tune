package com.techcoder.sqlperf.audit;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "SPT_AUDIT_EVENT")
@Getter
@Setter
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String entityType;

    @Column(nullable = false)
    private Long entityId;

    @Column(nullable = false, length = 20)
    private String action;

    private String fieldName;

    @Column(length = 4000)
    private String oldValue;

    @Column(length = 4000)
    private String newValue;

    @Column(nullable = false)
    private LocalDateTime changedAt;
    private String changedBy;
}
