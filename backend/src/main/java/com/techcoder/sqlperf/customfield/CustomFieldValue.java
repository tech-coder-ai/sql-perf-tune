package com.techcoder.sqlperf.customfield;

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
@Table(name = "SPT_CUSTOM_FIELD_VALUE")
@Getter
@Setter
public class CustomFieldValue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long fieldId;

    @Column(nullable = false)
    private Long entityId;

    @Column(length = 4000)
    private String valueText;

    @Column(nullable = false)
    private LocalDateTime updatedAt;
    private String updatedBy;
}
