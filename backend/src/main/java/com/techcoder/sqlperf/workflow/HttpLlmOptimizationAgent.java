package com.techcoder.sqlperf.workflow;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;

import com.techcoder.sqlperf.common.Texts;
import com.techcoder.sqlperf.config.SptProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * {@code spt.agent.mode=HTTP} (default): sends the prompt to the LLM endpoint ({@link LlmApi}) and returns its
 * answer. With no {@code spt.agent.endpoint-url} it calls this server's built-in mock endpoint, so switching to
 * the real model only means setting the URL (and model name / authorization).
 */
@Component
@ConditionalOnProperty(prefix = "spt.agent", name = "mode", havingValue = "HTTP", matchIfMissing = true)
public class HttpLlmOptimizationAgent implements OptimizationAgent {

    static final String MOCK_PATH = "/api/mock-llm/v1/optimize";

    private final SptProperties.Agent cfg;
    private final Environment env;
    private final RestClient http;

    public HttpLlmOptimizationAgent(SptProperties props, Environment env) {
        this.cfg = props.agent();
        this.env = env;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(Duration.ofSeconds(cfg.timeoutSeconds()));
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public String modelName() {
        return cfg.model();
    }

    @Override
    public Optional<String> optimize(Request r) {
        LlmApi.CompletionResponse res = http.post()
                .uri(endpoint())
                .contentType(MediaType.APPLICATION_JSON)
                .headers(h -> {
                    if (!Texts.isBlank(cfg.authorization())) {
                        h.set("Authorization", cfg.authorization());
                    }
                })
                .body(new LlmApi.CompletionRequest(cfg.model(), r.prompt(),
                        new LlmApi.Metadata(r.groupId(), r.runId(), r.sqlEngine(), r.attempt(), r.badSql())))
                .retrieve()
                .body(LlmApi.CompletionResponse.class);
        if (res == null || Texts.isBlank(res.text())) {
            throw new IllegalStateException("The LLM endpoint returned no text");
        }
        return Optional.of(res.text());
    }

    /** The configured endpoint, or this server's mock (port known only once the server runs). */
    String endpoint() {
        if (!Texts.isBlank(cfg.endpointUrl())) {
            return cfg.endpointUrl().trim();
        }
        String port = env.getProperty("local.server.port", env.getProperty("server.port", "8080"));
        return "http://localhost:" + port + MOCK_PATH;
    }
}
