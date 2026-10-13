package com.techcoder.sqlperf.workflow;

import java.util.Optional;

/**
 * Port to the LLM that performs step 8. Implementations must be side-effect free: the workflow service
 * persists the prompt and the answer.
 *
 * <p>Which agent runs is set by {@code spt.agent.mode}:
 * <ul>
 *   <li>{@code MANUAL} ({@link ManualOptimizationAgent}): no answer, the run stays PENDING so an analyst runs the
 *       rendered prompt in the approved LLM tool and pastes the answer back
 *       ({@code POST /api/groups/{id}/optimization-runs/{runId}/response});</li>
 *   <li>{@code MOCK} ({@link MockLlmOptimizationAgent}): a stand-in for the LLM endpoint that answers after a short
 *       delay with a plausible rewrite, different on every attempt.</li>
 * </ul>
 * The custom LLM endpoint becomes a third implementation: POST the prompt, return the model's text. Automatic
 * agents are called in the background ({@link WorkflowService#startOptimization}), so a slow model never holds an
 * HTTP request or a database transaction.
 */
public interface OptimizationAgent {

    /**
     * @param badSql  the group's sample SQL (also inside the prompt)
     * @param attempt how many runs the group had before this one (0 = first)
     */
    record Request(Long groupId, String sqlEngine, String badSql, String prompt, int attempt) {
    }

    String modelName();

    /** False when the answer is pasted by a person (no background call). */
    default boolean automatic() {
        return true;
    }

    /** @return the raw model answer, or empty when the answer will be supplied later. */
    Optional<String> optimize(Request request) throws Exception;
}
