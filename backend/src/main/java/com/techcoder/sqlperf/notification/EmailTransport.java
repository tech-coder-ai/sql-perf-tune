package com.techcoder.sqlperf.notification;

import java.util.List;

/**
 * Delivers one e-mail. Selected by {@code spt.email.transport}: {@link LogEmailTransport} (LOG, default: nothing is
 * sent), {@link SmtpEmailTransport} (SMTP via {@code spring.mail.*}) or {@link HttpEmailTransport} (HTTP: JSON to a
 * mail endpoint). Add another implementation for a different mail service.
 */
public interface EmailTransport {

    /** @return true when the mail left the application, false when it was only recorded (LOG) */
    boolean send(String from, List<String> to, String subject, String html) throws Exception;
}
