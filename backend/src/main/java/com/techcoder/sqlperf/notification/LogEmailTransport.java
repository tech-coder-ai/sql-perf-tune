package com.techcoder.sqlperf.notification;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Default: e-mails stay in the outbox (Administration → Notifications) and the log; nothing is sent. */
@Component
@ConditionalOnProperty(prefix = "spt.email", name = "transport", havingValue = "LOG", matchIfMissing = true)
public class LogEmailTransport implements EmailTransport {

    private static final Logger log = LoggerFactory.getLogger(LogEmailTransport.class);

    @Override
    public boolean send(String from, List<String> to, String subject, String html) {
        log.info("E-mail not sent (spt.email.transport=LOG): to={} subject={}", to, subject);
        return false;
    }
}
