package com.techcoder.sqlperf.workflow;

import java.util.Optional;

import org.springframework.stereotype.Component;

/** Fallback used when no other {@link OptimizationAgent} bean is registered. */
@Component
public class ManualOptimizationAgent implements OptimizationAgent {

    @Override
    public String modelName() {
        return "manual";
    }

    @Override
    public Optional<String> optimize(String prompt) {
        return Optional.empty();
    }
}
