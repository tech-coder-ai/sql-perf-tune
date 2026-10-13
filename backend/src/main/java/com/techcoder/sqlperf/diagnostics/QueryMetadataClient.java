package com.techcoder.sqlperf.diagnostics;

import java.util.List;

/**
 * Fetches what the optimization prompt needs about a bad SQL from the engine that ran it: the explain plan, the
 * query profile of a run, and the DDL and row count of every table it reads.
 *
 * <p>The Impala implementation will run {@code EXPLAIN <sql>}, read the profile of the logged run (Impala daemon
 * web UI {@code /query_profile_plain_text?query_id=} or the Cloudera Manager API), and {@code SHOW CREATE TABLE} /
 * {@code SHOW TABLE STATS} for each table. Until it is wired in, {@link MockImpalaMetadataClient} returns
 * generated, Impala-shaped output.
 */
public interface QueryMetadataClient {

    /** What to fetch: the group's sample SQL as it ran. */
    record Request(Long groupId, String sqlEngine, String sql, Double runMinutes) {
    }

    /** One table read by the SQL. */
    record TableInfo(String tableName, String ddl, Long rowCount) {
    }

    /**
     * @param profileRaw text profile as Impala prints it; the app parses run time, CPU, rows scanned and memory from it
     * @param rowCount   rows the SQL returned
     */
    record Result(String queryId, String explainPlan, String profileRaw, Long rowCount, List<TableInfo> tables) {
    }

    /** Short name shown as the capturing user, e.g. "impala (automatic)". */
    String source();

    Result fetch(Request request) throws Exception;
}
