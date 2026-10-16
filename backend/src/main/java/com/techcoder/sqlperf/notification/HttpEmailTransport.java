package com.techcoder.sqlperf.notification;

import java.time.Duration;
import java.util.List;

import com.techcoder.sqlperf.common.Texts;
import com.techcoder.sqlperf.config.SptProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * {@code spt.email.transport=HTTP}: POSTs each e-mail to a mail endpoint ({@code spt.email.endpoint-url}) as
 * {@code {"from", "to": [..], "subject", "html"}}; any 2xx counts as sent.
 */
@Component
@ConditionalOnProperty(prefix = "spt.email", name = "transport", havingValue = "HTTP")
public class HttpEmailTransport implements EmailTransport {

    public record MailRequest(String from, List<String> to, String subject, String html) {
    }

    private final SptProperties.Email cfg;
    private final RestClient http;

    public HttpEmailTransport(SptProperties props) {
        this.cfg = props.email();
        JdkClientHttpRequestFactory f = new JdkClientHttpRequestFactory();
        f.setReadTimeout(Duration.ofSeconds(30));
        this.http = RestClient.builder().requestFactory(f).build();
    }

    @Override
    public boolean send(String from, List<String> to, String subject, String html) {
        if (Texts.isBlank(cfg.endpointUrl())) {
            throw new IllegalStateException("spt.email.transport=HTTP but spt.email.endpoint-url is empty");
        }
        http.post().uri(cfg.endpointUrl().trim()).contentType(MediaType.APPLICATION_JSON)
                .headers(h -> {
                    if (!Texts.isBlank(cfg.authorization())) {
                        h.set("Authorization", cfg.authorization());
                    }
                })
                .body(new MailRequest(from, to, subject, html))
                .retrieve().toBodilessEntity();
        return true;
    }
}
