package com.techcoder.sqlperf.notification;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One e-mail the workflow produced (the outbox), whether or not it was sent. */
@Entity
@Table(name = "SPT_NOTIFICATION")
@Getter
@Setter
public class Notification {

    public enum Kind { READY_FOR_ADOPTION, NEEDS_SME_REVIEW }

    /**
     * PENDING: queued; SENT: handed to the mail server / endpoint; LOGGED: transport LOG (not sent);
     * DISABLED: e-mail is switched off in the settings; NO_RECIPIENTS: no address configured; FAILED: see error.
     */
    public enum Status { PENDING, SENT, LOGGED, FAILED, DISABLED, NO_RECIPIENTS }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long trackerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private Kind kind;

    @Column(length = 2000)
    private String recipients;

    @Column(nullable = false, length = 500)
    private String subject;

    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(length = 2000)
    private String errorMessage;

    @Column(nullable = false)
    private LocalDateTime createdAt;
    private LocalDateTime sentAt;
}
