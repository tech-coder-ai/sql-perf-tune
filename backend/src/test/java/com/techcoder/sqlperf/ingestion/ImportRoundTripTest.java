package com.techcoder.sqlperf.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import com.techcoder.sqlperf.group.QueryGroupRepository;
import com.techcoder.sqlperf.log.QueryLogRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Database round trips of an import. On Oracle every statement is a network round trip, so an import must
 * batch its inserts and recompute groups set-wise instead of issuing several statements per row / pattern.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class ImportRoundTripTest {

    /** distinct seq ids per run, so re-runs against a shared Oracle schema still insert new rows */
    static final long BASE = System.currentTimeMillis() % 1_000_000_000L * 10_000L;

    @TempDir
    static Path tmp;

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        r.add("SPT_SQLITE_PATH", () -> tmp.resolve("roundtrip.db").toString());
    }

    @Autowired
    IngestionService ingestion;
    @Autowired
    EntityManagerFactory emf;
    @Autowired
    QueryLogRepository logs;
    @Autowired
    QueryGroupRepository groups;

    @Test
    void twoThousandRowsTakeFewStatements() {
        StringBuilder csv = new StringBuilder("seq_id,executed_query,user_id,start_time,duration_minutes\n");
        for (int i = 0; i < 2000; i++) {
            int p = i % 300;
            csv.append(BASE + i).append(",\"select c").append(p).append(", sum(v) from db.t").append(p)
                    .append(" where k = ").append(BASE + i).append(" group by c").append(p).append("\",u").append(i % 25)
                    .append(",2026-10-08 10:").append(String.format("%02d", i % 60)).append(":00,")
                    .append(1 + i % 90).append('\n');
        }
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
        long t0 = System.nanoTime();

        IngestionBatch b = ingestion.importFile("perf.csv",
                new ByteArrayResource(csv.toString().getBytes(StandardCharsets.UTF_8)), null);

        long ms = (System.nanoTime() - t0) / 1_000_000;
        long statements = stats.getPrepareStatementCount();
        System.out.printf("IMPORT 2000 rows / 300 patterns: %d statements, %d ms%n", statements, ms);

        assertThat(b.getRowsLoaded()).isEqualTo(2000);
        assertThat(b.getGroupsAffected()).isGreaterThanOrEqualTo(300);
        assertThat(logs.countByBatchId(b.getId())).isEqualTo(2000);
        assertThat(groups.count()).isGreaterThanOrEqualTo(300);
        assertThat(logs.findAll()).allSatisfy(l -> assertThat(l.getGroupId()).isNotNull());
        // SQLite (local) keeps IDENTITY ids: one INSERT + one id lookup per new row (~8000 for 4000 rows incl.
        // sightings). Groups are recomputed set-wise: a handful of statements per 500 patterns, not ~7 per pattern.
        assertThat(statements).isLessThan(maxStatements());
    }

    /** Overridden for Oracle, where pooled ids batch the inserts. */
    long maxStatements() {
        return 8_800;
    }
}
