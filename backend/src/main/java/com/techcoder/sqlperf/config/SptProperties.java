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
        @DefaultValue Cors cors) {

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
     */
    public record Ingestion(
            @DefaultValue("500") int batchSize,
            @DefaultValue("1000") int maxRejectedRows,
            @DefaultValue("IMPALA") String defaultSqlEngine,
            @DefaultValue("600") int jdbcQueryTimeoutSeconds) {
    }

    public record Cors(@DefaultValue("http://localhost:4200") List<String> allowedOrigins) {
    }
}
