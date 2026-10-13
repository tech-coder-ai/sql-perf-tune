package com.techcoder.sqlperf.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Application settings bound from {@code spt.*}.
 */
@ConfigurationProperties(prefix = "spt")
public record SptProperties(
        @DefaultValue Security security,
        @DefaultValue Fingerprint fingerprint,
        @DefaultValue Ingestion ingestion,
        @DefaultValue Cors cors,
        @DefaultValue Ui ui,
        @DefaultValue Diagnostics diagnostics,
        @DefaultValue Agent agent) {

    public enum SecurityMode { NONE, JWT }

    public record Security(@DefaultValue("NONE") SecurityMode mode) {
    }

    /**
     * @param stripWhereClause drop WHERE clauses before hashing so the same SQL with different filters groups together
     * @param maskLiterals     replace string / numeric literals with {@code ?} everywhere else
     * @param preferUserQuery  hash USER_QUERY instead of EXECUTED_QUERY when both are present
     */
    public record Fingerprint(
            @DefaultValue("true") boolean stripWhereClause,
            @DefaultValue("true") boolean maskLiterals,
            @DefaultValue("false") boolean preferUserQuery) {
    }

    /**
     * @param batchSize         rows persisted per transaction chunk
     * @param maxRejectedRows   abort an import once this many rows fail to parse
     * @param defaultSqlEngine  engine assumed for file uploads that do not specify one
     * @param jdbcQueryTimeoutSeconds statement timeout for log pulls from source databases
     * @param rowIdentity       how a log row is recognised on re-load
     */
    public record Ingestion(
            @DefaultValue("500") int batchSize,
            @DefaultValue("1000") int maxRejectedRows,
            @DefaultValue("IMPALA") String defaultSqlEngine,
            @DefaultValue("600") int jdbcQueryTimeoutSeconds,
            @DefaultValue("CONTENT") RowIdentity rowIdentity) {
    }

    /**
     * CONTENT: engine + seq_id + user + start/end time + SQL text (safe default).
     * SEQ_ID: engine + seq_id only, for sources whose seq_id is globally unique and stable
     * (falls back to CONTENT for rows without a seq_id).
     */
    public enum RowIdentity { CONTENT, SEQ_ID }

    /**
     * @param agGridLicenseKey AG Grid Enterprise key handed to the browser (it is client-side by nature);
     *                         empty = evaluation mode with watermark
     */
    public record Ui(@DefaultValue("") String agGridLicenseKey) {
    }

    /**
     * Automatic capture of explain plan, profile and table DDL when a group is put on the tracker.
     *
     * @param autoCapture     start the capture for every newly tracked group
     * @param client          MOCK: generated, Impala-shaped diagnostics (until the Impala client is wired in)
     * @param mockDelayMillis how long the mock client takes, so the "in progress" state can be seen
     * @param threads         captures running at the same time
     */
    public record Diagnostics(
            @DefaultValue("true") boolean autoCapture,
            @DefaultValue("MOCK") DiagnosticsClient client,
            @DefaultValue("4000") long mockDelayMillis,
            @DefaultValue("2") int threads) {
    }

    public enum DiagnosticsClient { MOCK }

    /**
     * The optimization agent (LLM) of step 8.
     *
     * @param mode            HTTP: POST the prompt to {@code endpointUrl} and store the answer;
     *                        MANUAL: the analyst runs the prompt elsewhere and pastes the answer
     * @param endpointUrl     the LLM endpoint; empty = this server's built-in mock ({@code /api/mock-llm/v1/optimize})
     * @param model           model name recorded on each run and sent to the endpoint
     * @param authorization   value of the Authorization header sent to the endpoint (e.g. "Bearer ..."); empty = none
     * @param timeoutSeconds  how long to wait for an answer
     * @param mockEndpoint    serve the built-in mock endpoint (development; off in prod)
     * @param mockDelayMillis how long the mock takes to answer
     */
    public record Agent(
            @DefaultValue("HTTP") AgentMode mode,
            @DefaultValue("") String endpointUrl,
            @DefaultValue("mock-llm") String model,
            @DefaultValue("") String authorization,
            @DefaultValue("180") int timeoutSeconds,
            @DefaultValue("true") boolean mockEndpoint,
            @DefaultValue("5000") long mockDelayMillis) {
    }

    public enum AgentMode { HTTP, MANUAL }

    public record Cors(@DefaultValue("http://localhost:4200") List<String> allowedOrigins) {
    }
}
