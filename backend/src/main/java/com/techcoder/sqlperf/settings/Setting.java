package com.techcoder.sqlperf.settings;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** One admin-edited workflow setting (absent = the default in {@link WorkflowSettings}). */
@Entity
@Table(name = "SPT_SETTING")
@Getter
@Setter
public class Setting {

    @Id
    @Column(name = "SETTING_KEY", length = 100)
    private String key;

    @Column(name = "SETTING_VALUE", length = 4000)
    private String value;

    @Column(nullable = false)
    private LocalDateTime updatedAt;
    private String updatedBy;
}
