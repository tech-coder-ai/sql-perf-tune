package com.techcoder.sqlperf.workflow;

import java.util.Optional;

/**
 * Port to the LLM that performs step 8. Implementations must be side-effect free: the workflow service
 * persists the prompt and the answer.
 *
 * <p>The default {@link ManualOptimizationAgent} returns no answer, leaving the run PENDING so an analyst can
 * run the rendered prompt in the approved LLM tool and paste the answer back
 * ({@code POST /api/groups/{id}/optimization-runs/{runId}/response}). Provide another bean of this type to
 * call an LLM gateway directly.
 */
public interface OptimizationAgent {

    String modelName();

    /** @return the raw model answer, or empty when the answer will be supplied later. */
    Optional<String> optimize(String prompt);
}
