package com.techcoder.sqlperf.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import com.techcoder.sqlperf.group.QueryGroup;
import com.techcoder.sqlperf.group.QueryGroupRepository;
import com.techcoder.sqlperf.log.LogSightingRepository;
import com.techcoder.sqlperf.log.QueryLogRepository;
import com.techcoder.sqlperf.tracker.TrackerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** End to end on a throw-away SQLite file: CSV -> logs -> groups -> tracker, including re-import. */
@SpringBootTest
class IngestionFlowTest {

    @TempDir
    static Path tmp;

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        r.add("SPT_SQLITE_PATH", () -> tmp.resolve("it.db").toString());
    }

    @Autowired
    IngestionService ingestion;
    @Autowired
    QueryGroupRepository groups;
    @Autowired
    QueryLogRepository logs;
    @Autowired
    TrackerService trackers;
    @Autowired
    LogSightingRepository sightings;
    @Autowired
    SourceFileRepository files;

    private static final String CSV = """
            seq_id,executed_query,user_query,error_code,error_category,error_message,useris,start_time,end_time,duration_minutes
            1,"select a from t where x = 1",,,,,u1,2026-09-01 10:00:00,2026-09-01 10:10:00,10
            2,"SELECT a FROM t WHERE x = 2",,E1,Resource,oom,u2,2026-09-01 11:00:00,2026-09-01 11:30:00,
            3,"select b from t",,,,,u1,2026-09-01 12:00:00,,4.5
            4,,,,,,u3,2026-09-01 13:00:00,,1
            5,"select a from t where y = 'z'",,,,,u1,not-a-date,,2
            """;

    @Test
    void importsGroupsAndTracksQueries() {
        IngestionBatch b = ingestion.importFile("log.csv", stream(CSV), null);
        assertThat(b.getStatus()).isEqualTo(IngestionBatch.Status.COMPLETED_WITH_ERRORS);
        assertThat(b.getRowsLoaded()).isEqualTo(3);
        assertThat(b.getRowsRejected()).isEqualTo(2);
        assertThat(b.getMessage()).contains("Row 5").contains("Row 6");

        List<QueryGroup> all = groups.findAll().stream()
                .filter(x -> x.getSampleQuery() != null && x.getSampleQuery().toLowerCase().contains(" from t")).toList();
        assertThat(all).hasSize(2);
        QueryGroup g = all.stream().filter(x -> x.getGroupSize() == 2).findFirst().orElseThrow();
        assertThat(g.getDistinctUsers()).isEqualTo(2);
        assertThat(g.getUserIds()).isEqualTo("u1, u2");
        assertThat(g.getMaxDurationMinutes()).isEqualTo(30.0); // computed from start/end
        assertThat(g.getTotalDurationMinutes()).isEqualTo(40.0);
        assertThat(g.getErrorCount()).isEqualTo(1);
        assertThat(g.getRowIndices()).isEqualTo("1,2");
        assertThat(g.getSampleQuerySeqId()).isEqualTo(2L);
        assertThat(logs.findAll()).allSatisfy(l -> {
            assertThat(l.getGroupId()).isNotNull();
            assertThat(l.getRowKey()).isNotNull();
        });

        var tracker = trackers.createForGroups(List.of(g.getId())).getFirst();
        assertThat(tracker.groupSize()).isEqualTo(2);
        assertThat(tracker.ogRunDurationMinutes()).isEqualTo(30.0);

        // a second file adds members to the existing group instead of creating a new one
        ingestion.importFile("more.csv", stream("""
                seq_id,executed_query,user_id,duration_minutes
                10,select a from t where x = 42,u9,5
                """), "impala");
        QueryGroup again = groups.findById(g.getId()).orElseThrow();
        assertThat(again.getGroupSize()).isEqualTo(3);
        assertThat(again.getRowIndices()).isEqualTo("1,2,10");
        assertThat(trackers.get(tracker.trackerId()).groupSize()).isEqualTo(3);
    }

    @Test
    void eachRowIsProcessedOnceAndReloadsAreCounted() {
        String day1 = """
                seq_id,executed_query,user_id,start_time,duration_minutes
                101,select x from dedup.a where k = 1,u1,2026-10-01 08:00:00,3
                102,select x from dedup.a where k = 2,u2,2026-10-01 09:00:00,5
                102,select x from dedup.a where k = 2,u2,2026-10-01 09:00:00,5
                """;
        IngestionBatch first = ingestion.importFile("day1.csv", stream(day1), null);
        assertThat(first.getRowsLoaded()).isEqualTo(2);
        assertThat(first.getRowsDuplicate()).isEqualTo(1); // repeated inside the same file
        assertThat(first.getFileLoadNumber()).isEqualTo(1);
        QueryGroup g = groups.findAll().stream().filter(x -> x.getSampleQuery().contains("dedup.a")).findFirst().orElseThrow();
        assertThat(g.getGroupSize()).isEqualTo(2);

        // identical file again: not parsed, flagged, every row seen once more
        IngestionBatch again = ingestion.importFile("day1-copy.csv", stream(day1), null);
        assertThat(again.getDuplicateOfBatchId()).isEqualTo(first.getId());
        assertThat(again.getFileLoadNumber()).isEqualTo(2);
        assertThat(again.getRowsLoaded()).isZero();
        assertThat(again.getRowsDuplicate()).isEqualTo(2);
        assertThat(again.getGroupsAffected()).isZero();

        // cumulative export later in the day: one old row, one new row
        IngestionBatch later = ingestion.importFile("day1-evening.csv", stream("""
                seq_id,executed_query,user_id,start_time,duration_minutes
                102,select x from dedup.a where k = 2,u2,2026-10-01 09:00:00,5
                103,select x from dedup.a where k = 3,u3,2026-10-01 18:00:00,7
                """), null);
        assertThat(later.getDuplicateOfBatchId()).isNull();
        assertThat(later.getRowsLoaded()).isEqualTo(1);
        assertThat(later.getRowsDuplicate()).isEqualTo(1);

        QueryGroup after = groups.findById(g.getId()).orElseThrow();
        assertThat(after.getGroupSize()).isEqualTo(3);
        assertThat(after.getRowIndices()).isEqualTo("101,102,103");

        var row102 = logs.findAll().stream().filter(l -> Long.valueOf(102).equals(l.getSeqId())).toList();
        assertThat(row102).hasSize(1);
        assertThat(row102.getFirst().getSeenCount()).isEqualTo(3); // day1, identical re-load, evening export
        assertThat(sightings.findByLogIdOrderBySeenAtDescIdDesc(row102.getFirst().getId()))
                .extracting(s -> s.isFirst()).containsExactlyInAnyOrder(true, false, false);
        assertThat(files.findByContentHash(first.getContentHash()).orElseThrow().getLoadCount()).isEqualTo(2);
    }

    private static ByteArrayResource stream(String s) {
        return new ByteArrayResource(s.getBytes(StandardCharsets.UTF_8));
    }
}
