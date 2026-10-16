package com.techcoder.sqlperf.log;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.techcoder.sqlperf.common.RowGrouping;
import com.techcoder.sqlperf.ingestion.IngestionService;
import com.techcoder.sqlperf.insights.InsightsService;
import com.techcoder.sqlperf.users.UserDirectoryEntry;
import com.techcoder.sqlperf.users.UserDirectoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The dashboards' numbers link to the Query logs screen with filters; the filtered row count must equal the number
 * that was clicked. Also covers server-side row grouping (buckets + opening a group).
 */
@SpringBootTest(properties = "spt.diagnostics.auto-capture=false")
class LogDrillDownTest {

    @TempDir
    static Path tmp;

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        r.add("SPT_SQLITE_PATH", () -> tmp.resolve("drill.db").toString());
    }

    @Autowired IngestionService ingestion;
    @Autowired QueryLogController logs;
    @Autowired InsightsService insights;
    @Autowired UserDirectoryRepository directory;

    @Test
    void dashboardNumbersMatchTheirLogDrillDown() {
        LocalDate day = LocalDate.of(2026, 10, 8);
        String prev = day.minusDays(1) + " 09:00:00";
        String d9 = day + " 09:15:00";
        String d14 = day + " 14:05:00";
        ingestion.importFile("drill.csv", new ByteArrayResource(("""
                seq_id,executed_query,user_id,start_time,duration_minutes,error_category
                1,select a from t1 where x = 1,alice,%s,3,
                2,select a from t1 where x = 2,alice,%s,4,Resource
                3,select a from t1 where x = 3,alice2,%s,5,
                4,select b from t2 where y = 1,bob,%s,6,Resource
                5,select c from t3 where z = 1,bob,%s,7,
                """.formatted(prev, d9, d9, d14, d14)).getBytes(StandardCharsets.UTF_8)), null);
        UserDirectoryEntry bob = new UserDirectoryEntry();
        bob.setUserId("bob");
        bob.setUserGroup("Finance");
        bob.setUpdatedAt(LocalDateTime.now());
        directory.save(bob);

        LocalDateTime from = day.atStartOfDay();
        LocalDateTime to = day.atTime(23, 59, 59);
        InsightsService.Daily d = insights.daily(day);

        // Q1: bad queries of the day
        assertThat(total(filter(null, null, from, to, null))).isEqualTo(d.badQueries()).isEqualTo(4);
        // Q8: one hour
        assertThat(total(filter(null, null, day.atTime(14, 0), day.atTime(14, 59, 59), null)))
                .isEqualTo(d.byHour().get(14).count()).isEqualTo(2);
        // Q2: recurring vs new patterns of the day
        assertThat(total(filter(null, null, from, to, "RECURRING"))).isEqualTo(d.recurringQueries()).isEqualTo(2);
        assertThat(total(filter(null, null, from, to, "NEW"))).isEqualTo(d.badQueries() - d.recurringQueries());
        // Q7 / Q13: exact user (not "alice2"), and user groups
        assertThat(total(filter("alice", null, null, null, null))).isEqualTo(2);
        assertThat(total(filter(null, "Finance", null, null, null))).isEqualTo(2);
        assertThat(total(filter(null, "Unassigned", null, null, null))).isEqualTo(3);

        // row grouping: users of the day, then the rows of one of them
        var buckets = logs.buckets("userId", 0, 100, null, null, filter(null, null, from, to, null));
        assertThat(buckets.content()).extracting(RowGrouping.Bucket::key).containsExactly("bob", "alice", "alice2");
        assertThat(buckets.content()).extracting(RowGrouping.Bucket::count).containsExactly(2L, 1L, 1L);
        var byDay = logs.buckets("startDay", 0, 100, "startDay,asc", null, filter(null, null, null, null, null));
        assertThat(byDay.content()).extracting(RowGrouping.Bucket::key).containsExactly(day.minusDays(1).toString(), day.toString());
        var nested = logs.buckets("errorCategory", 0, 100, null, List.of("userId:bob"), filter(null, null, null, null, null));
        assertThat(nested.content()).extracting(RowGrouping.Bucket::count).containsExactlyInAnyOrder(1L, 1L);
        assertThat(logs.search(0, 50, null, List.of("userId:bob", "errorCategory!"), filter(null, null, null, null, null))
                .content()).singleElement().extracting(QueryLog::getSeqId).isEqualTo(5L);
    }

    private static QueryLogController.LogFilter filter(String user, String userGroup, LocalDateTime from,
                                                       LocalDateTime to, String pattern) {
        return new QueryLogController.LogFilter(null, null, user, userGroup, null, null, null, null, null, null, null,
                from, to, pattern, null);
    }

    private long total(QueryLogController.LogFilter f) {
        return logs.search(0, 50, null, null, f).totalElements();
    }
}
