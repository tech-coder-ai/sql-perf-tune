package com.techcoder.sqlperf.log;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;

import com.techcoder.sqlperf.group.PatternClassifier;
import com.techcoder.sqlperf.group.QueryGroupController;
import com.techcoder.sqlperf.ingestion.IngestionService;
import com.techcoder.sqlperf.insights.InsightsService;
import com.techcoder.sqlperf.settings.WorkflowSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The admin threshold splits bad from normal queries everywhere, and a change applies at once. */
@SpringBootTest(properties = "spt.settings.bad.min-minutes=5")
class BadQueryThresholdTest {

    @TempDir
    static Path tmp;

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        r.add("SPT_SQLITE_PATH", () -> tmp.resolve("bad.db").toString());
    }

    @Autowired IngestionService ingestion;
    @Autowired QueryLogController logs;
    @Autowired QueryGroupController groups;
    @Autowired InsightsService insights;
    @Autowired WorkflowSettings settings;

    @Test
    void thresholdSeparatesBadFromNormal() {
        LocalDate day = LocalDate.of(2026, 10, 8);
        ingestion.importFile("b.csv", new ByteArrayResource(("""
                seq_id,executed_query,user_id,start_time,duration_minutes
                1,select a from quick where x = 1,u1,%1$s 09:00:00,2
                2,select a from quick where x = 2,u1,%1$s 10:00:00,3
                3,select b from slow where x = 1,u2,%1$s 11:00:00,6
                4,select b from slow where x = 2,u2,%1$s 12:00:00,7
                5,select c from once where x = 1,u3,%1$s 13:00:00,9
                """.formatted(day)).getBytes(StandardCharsets.UTF_8)), null);
        assertThat(settings.badMinMinutes()).isEqualTo(5.0);

        // Query logs: bad queries by default, all with includeNormal
        assertThat(logs.search(0, 50, null, null, filter(null)).totalElements()).isEqualTo(3);
        assertThat(logs.search(0, 50, null, null, filter(true)).totalElements()).isEqualTo(5);
        // Query groups: bad patterns by default; classes BAD (2 bad runs) and ADHOC (1 bad run)
        var bad = groups.search(0, 50, null, null, groupFilter(null, null));
        assertThat(bad.content()).extracting(g -> g.patternClass().name()).containsExactlyInAnyOrder("BAD", "ADHOC");
        assertThat(groups.search(0, 50, null, null, groupFilter(true, null)).totalElements()).isEqualTo(3);
        assertThat(groups.search(0, 50, null, null, groupFilter(true, "NORMAL")).content())
                .singleElement().extracting(g -> g.patternClass()).isEqualTo(PatternClassifier.PatternClass.NORMAL);
        // dashboards count bad queries only
        assertThat(insights.daily(day).badQueries()).isEqualTo(3);

        // the admin raises the threshold: applies at once
        settings.save(Map.of(WorkflowSettings.BAD_MIN_MINUTES, "6.5"));
        assertThat(logs.search(0, 50, null, null, filter(null)).totalElements()).isEqualTo(2);
        assertThat(insights.daily(day).badQueries()).isEqualTo(2);
        assertThat(groups.search(0, 50, null, null, groupFilter(null, "BAD")).totalElements()).isZero();
        // back to the default; invalid values are refused
        settings.save(Map.of(WorkflowSettings.BAD_MIN_MINUTES, ""));
        assertThat(settings.badMinMinutes()).isEqualTo(5.0);
        assertThatThrownBy(() -> settings.save(Map.of(WorkflowSettings.BAD_MIN_MINUTES, "five")))
                .hasMessageContaining("must be a number");
        assertThatThrownBy(() -> settings.save(Map.of(WorkflowSettings.EMAIL_ADOPTION_TO, "not-an-address")))
                .hasMessageContaining("Not an e-mail");
    }

    private static QueryLogController.LogFilter filter(Boolean includeNormal) {
        return new QueryLogController.LogFilter(null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, includeNormal);
    }

    private static QueryGroupController.GroupFilter groupFilter(Boolean includeNormal, String pattern) {
        return new QueryGroupController.GroupFilter(null, null, null, null, null, null, null, null, includeNormal, pattern);
    }
}
