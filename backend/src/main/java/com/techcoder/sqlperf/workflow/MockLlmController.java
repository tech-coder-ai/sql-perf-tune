package com.techcoder.sqlperf.workflow;

import com.techcoder.sqlperf.config.SptProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Built-in stand-in for the LLM endpoint (development, demos): same contract as the real one ({@link LlmApi}),
 * answers after {@code spt.agent.mock-delay-millis} with a simulated rewrite. Off with
 * {@code spt.agent.mock-endpoint=false} (the prod profile).
 */
@RestController
@ConditionalOnProperty(prefix = "spt.agent", name = "mock-endpoint", havingValue = "true", matchIfMissing = true)
public class MockLlmController {

    private final MockLlm mock;
    private final long delayMillis;

    public MockLlmController(MockLlm mock, SptProperties props) {
        this.mock = mock;
        this.delayMillis = props.agent().mockDelayMillis();
    }

    @PostMapping(HttpLlmOptimizationAgent.MOCK_PATH)
    public LlmApi.CompletionResponse optimize(@RequestBody LlmApi.CompletionRequest req) throws InterruptedException {
        if (delayMillis > 0) {
            Thread.sleep(delayMillis);
        }
        LlmApi.Metadata m = req.metadata();
        return new LlmApi.CompletionResponse(mock.answer(req.prompt(), m == null ? null : m.sql(),
                m == null ? 0 : m.attempt()), "mock-llm");
    }
}
