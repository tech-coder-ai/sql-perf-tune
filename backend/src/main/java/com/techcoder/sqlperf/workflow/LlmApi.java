package com.techcoder.sqlperf.workflow;

/**
 * The contract between the app and the LLM endpoint ({@code spt.agent.endpoint-url}). The built-in mock
 * ({@link MockLlmController}) implements it; the real endpoint must accept the same JSON.
 *
 * <pre>
 * POST {endpoint-url}            Content-Type: application/json   (Authorization: spt.agent.authorization)
 * {
 *   "model":    "mock-llm",                     // spt.agent.model
 *   "prompt":   "You are a senior Cloudera Impala performance engineer ...",
 *   "metadata": { "groupId": 2, "runId": 14, "sqlEngine": "IMPALA", "attempt": 1, "sql": "SELECT ..." }
 * }
 * 200 OK
 * { "text": "### CHANGE NARRATIVE\n1. ...\n### OPTIMIZED SQL\n```sql\nSELECT ...\n```", "model": "..." }
 * </pre>
 * Only {@code prompt} is needed to answer; {@code metadata} is context (an endpoint may log it or ignore it).
 * {@code text} must contain one ```sql block (see {@link AgentResponseParser}); {@code model}, if present, is
 * recorded on the run. Any non-2xx status or missing {@code text} marks the run as failed.
 */
public final class LlmApi {

    private LlmApi() {
    }

    public record Metadata(Long groupId, Long runId, String sqlEngine, int attempt, String sql) {
    }

    public record CompletionRequest(String model, String prompt, Metadata metadata) {
    }

    public record CompletionResponse(String text, String model) {
    }
}
