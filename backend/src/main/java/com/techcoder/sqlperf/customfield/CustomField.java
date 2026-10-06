package com.techcoder.sqlperf.customfield;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** A business column added at runtime to the tracker, group or log screens. */
@Entity
@Table(name = "SPT_CUSTOM_FIELD")
@Getter
@Setter
public class CustomField {

    public enum EntityType { TRACKER, GROUP, LOG }

    public enum DataType { TEXT, LONG_TEXT, NUMBER, DATE, BOOLEAN, ENUM }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private EntityType entityType;

    @Column(nullable = false, length = 100)
    private String fieldKey;

    @Column(nullable = false, length = 200)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DataType dataType;

    @Column(length = 4000)
    private String optionsCsv;

    private int displayOrder;
    private boolean required;
    private boolean active = true;

    @Column(nullable = false)
    private LocalDateTime createdAt;
    private String createdBy;
}
