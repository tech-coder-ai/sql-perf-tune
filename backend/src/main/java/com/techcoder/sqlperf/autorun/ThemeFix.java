package com.techcoder.sqlperf.autorun;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** An architectural fix released for a theme (step 15: its waiting SQL are retried). */
@Entity
@Table(name = "SPT_THEME_FIX")
@Getter
@Setter
public class ThemeFix {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String theme;

    @Column(length = 2000)
    private String notes;

    private int itemsRetried;

    @Column(nullable = false)
    private LocalDateTime releasedAt;
    private String releasedBy;
}
