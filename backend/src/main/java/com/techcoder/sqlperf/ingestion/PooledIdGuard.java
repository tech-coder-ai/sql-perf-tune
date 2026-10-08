package com.techcoder.sqlperf.ingestion;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Oracle only: the high-volume tables take pooled ids from sequences (V5__pooled_ids.sql). If rows were ever
 * inserted without them (a DBA script, an older instance still using the IDENTITY column during an upgrade),
 * a sequence can fall behind {@code MAX(ID)} and every insert would hit ORA-00001. At start-up each sequence is
 * moved past the highest id when needed.
 */
@Component
@Profile("oracle")
@Order(0)
class PooledIdGuard implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PooledIdGuard.class);

    /** sequence -> table (constant names, never user input) */
    static final Map<String, String> SEQUENCES = Map.of(
            "SPT_QUERY_LOG_SEQ", "SPT_QUERY_LOG",
            "SPT_QUERY_LOG_SIGHTING_SEQ", "SPT_QUERY_LOG_SIGHTING",
            "SPT_QUERY_GROUP_SEQ", "SPT_QUERY_GROUP");

    private final JdbcTemplate jdbc;

    PooledIdGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        SEQUENCES.forEach(this::check);
    }

    private void check(String sequence, String table) {
        long start = 0;
        try {
            long maxId = jdbc.queryForObject("SELECT NVL(MAX(ID), 0) FROM " + table, Long.class);
            // nextval is the first id of the next block (pooled-lo); taking one only skips 1000 ids
            long next = jdbc.queryForObject("SELECT " + sequence + ".NEXTVAL FROM DUAL", Long.class);
            if (next > maxId) {
                return;
            }
            start = maxId + 1;
            jdbc.execute("ALTER SEQUENCE " + sequence + " RESTART START WITH " + start);
            log.warn("Sequence {} was behind {} (next {} <= max id {}); restarted at {}", sequence, table, next,
                    maxId, start);
        } catch (RuntimeException e) {
            log.error("Could not check sequence {} against {}. If imports fail with ORA-00001, run: "
                    + "ALTER SEQUENCE {} RESTART START WITH <MAX(ID) + 1>", sequence, table, sequence, e);
        }
    }
}
