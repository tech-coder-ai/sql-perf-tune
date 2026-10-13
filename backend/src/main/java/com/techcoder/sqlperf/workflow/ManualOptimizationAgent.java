package com.techcoder.sqlperf.workflow;

import java.util.Optional;

import org.springframework.stereotype.Component;

/** {@code spt.agent.mode=MANUAL}: the analyst pastes the LLM answer (also the fallback without another agent). */
@Component
public class ManualOptimizationAgent implements OptimizationAgent {

    @Override
    public String modelName() {
        return "manual";
    }

    @Override
    public boolean automatic() {
        return false;
    }

    @Override
    public Optional<String> optimize(Request request) {
        return Optional.empty();
    }
}
