package com.techcoder.sqlperf.lookup;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** One admin-editable dropdown value (e.g. category THEME, value "Trade all"). */
@Entity
@Table(name = "SPT_LOOKUP")
@Getter
@Setter
public class Lookup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String category;

    @Column(name = "lookup_value", nullable = false, length = 200)
    private String value;

    private int sortOrder;

    /** UI chip colour hint: ok, warn, bad, info, muted. */
    @Column(length = 20)
    private String tone;

    private boolean active = true;

    @Column(nullable = false)
    private LocalDateTime createdAt;
}
