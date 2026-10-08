package com.techcoder.sqlperf.users;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Maps a log user id to a display name, user group and department (for "bad queries by user group"). */
@Entity
@Table(name = "SPT_USER_DIRECTORY")
@Getter
@Setter
public class UserDirectoryEntry {

    @Id
    @Column(length = 200)
    private String userId;

    private String displayName;
    private String userGroup;
    private String department;
    private boolean active = true;

    @Column(nullable = false)
    private LocalDateTime updatedAt;
    private String updatedBy;
}
