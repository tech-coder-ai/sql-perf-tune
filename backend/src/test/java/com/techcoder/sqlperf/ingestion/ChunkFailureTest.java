package com.techcoder.sqlperf.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.doAnswer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;

import com.techcoder.sqlperf.log.QueryLog;
import com.techcoder.sqlperf.log.QueryLogRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * A row the database refuses (e.g. ORA-12899 value too large on Oracle) must not stop the import after the
 * first chunk: the failing chunk is retried row by row and only that row is rejected.
 */
@SpringBootTest(properties = "spt.ingestion.batch-size=5")
class ChunkFailureTest {

    @TempDir
    static Path tmp;

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        r.add("SPT_SQLITE_PATH", () -> tmp.resolve("chunk.db").toString());
    }

    @Autowired
    IngestionService ingestion;

    @MockitoSpyBean
    QueryLogRepository logs;

    @PersistenceContext
    EntityManager em;

    @Test
    void badRowIsRejectedAndTheRestOfTheFileIsLoaded() {
        // seq 7 (row 8 of the file, in the second chunk of 5) is refused by the database
        doAnswer(inv -> {
            Iterable<QueryLog> rows = inv.getArgument(0);
            if (StreamSupport.stream(rows.spliterator(), false).anyMatch(q -> Long.valueOf(7).equals(q.getSeqId()))) {
                throw new DataIntegrityViolationException("ORA-12899: value too large for column \"USER_ID\"");
            }
            // what SimpleJpaRepository.saveAll does for new entities (a Spring Data proxy cannot call through)
            List<QueryLog> saved = new ArrayList<>();
            rows.forEach(q -> {
                em.persist(q);
                saved.add(q);
            });
            return saved;
        }).when(logs).saveAll(anyIterable());

        StringBuilder csv = new StringBuilder("seq_id,executed_query,user_id,start_time,duration_minutes\n");
        IntStream.rangeClosed(1, 23).forEach(i -> csv.append(i).append(",\"select c").append(i)
                .append(" from t where k = 1\",u").append(i % 3).append(",2026-09-01 10:00:00,5\n"));

        IngestionBatch b = ingestion.importFile("big.csv",
                new ByteArrayResource(csv.toString().getBytes(StandardCharsets.UTF_8)), null);

        assertThat(b.getRowsRead()).isEqualTo(23);
        assertThat(b.getRowsLoaded()).isEqualTo(22);
        assertThat(b.getRowsRejected()).isEqualTo(1);
        assertThat(b.getRowsDuplicate()).isZero();
        assertThat(b.getStatus()).isEqualTo(IngestionBatch.Status.COMPLETED_WITH_ERRORS);
        assertThat(b.getMessage()).contains("Row 8").contains("ORA-12899");
        assertThat(b.getGroupsAffected()).isEqualTo(22);
    }
}
