package com.techcoder.sqlperf.notification;

import java.util.List;

import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * {@code spt.email.transport=SMTP}: sends through the mail server configured with Spring Boot's
 * {@code spring.mail.host / port / username / password / properties.*}.
 */
@Component
@ConditionalOnProperty(prefix = "spt.email", name = "transport", havingValue = "SMTP")
public class SmtpEmailTransport implements EmailTransport {

    private final ObjectProvider<JavaMailSender> mailSender;

    public SmtpEmailTransport(ObjectProvider<JavaMailSender> mailSender) {
        this.mailSender = mailSender;
    }

    @Override
    public boolean send(String from, List<String> to, String subject, String html) throws Exception {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            throw new IllegalStateException("spt.email.transport=SMTP but spring.mail.host is not configured");
        }
        MimeMessage msg = sender.createMimeMessage();
        MimeMessageHelper h = new MimeMessageHelper(msg, true, "UTF-8");
        h.setFrom(from);
        h.setTo(to.toArray(String[]::new));
        h.setSubject(subject);
        h.setText(html, true);
        sender.send(msg);
        return true;
    }
}
