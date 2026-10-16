package com.techcoder.sqlperf.autorun;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import com.techcoder.sqlperf.group.PatternClassifier;
import com.techcoder.sqlperf.group.QueryGroup;
import com.techcoder.sqlperf.group.QueryGroupRepository;
import com.techcoder.sqlperf.ingestion.IngestionService;
import com.techcoder.sqlperf.notification.Notification;
import com.techcoder.sqlperf.notification.NotificationRepository;
import com.techcoder.sqlperf.settings.WorkflowSettings;
import com.techcoder.sqlperf.tracker.TrackerDto;
import com.techcoder.sqlperf.tracker.TrackerService;
import com.techcoder.sqlperf.tracker.TuningTracker.AutoRunStatus;
import com.techcoder.sqlperf.tracker.TuningTracker.ResultStatus;
import com.techcoder.sqlperf.tracker.TuningTracker.WorkflowStatus;
import com.techcoder.sqlperf.tracker.TuningTrackerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The first tuning round runs by itself: import → candidates tracked → diagnostics → agent (mock LLM endpoint over
 * HTTP) → test → comparison → users (e-mail) or tuning team (e-mail). Normal and adhoc-only patterns are left alone.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spt.settings.auto.enabled=true", "spt.settings.bad.min-minutes=5",
        "spt.diagnostics.mock-delay-millis=50", "spt.agent.mock-delay-millis=50"})
class AutoRunFlowTest {

    @TempDir
    static Path tmp;

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        r.add("SPT_SQLITE_PATH", () -> tmp.resolve("auto.db").toString());
    }

    @Autowired IngestionService ingestion;
    @Autowired QueryGroupRepository groups;
    @Autowired TrackerService trackers;
    @Autowired TuningTrackerRepository trackerRepo;
    @Autowired NotificationRepository outbox;
    @Autowired WorkflowSettings settings;
    @Autowired PatternClassifier classifier;

    @Test
    void firstRoundRoutesByTheThreshold() throws InterruptedException {
        settings.save(Map.of(WorkflowSettings.EMAIL_ENABLED, "true",
                WorkflowSettings.EMAIL_ADOPTION_TO, "app-users@example.com",
                WorkflowSettings.EMAIL_TUNING_TO, "tuning-team@example.com"));
        load("a.csv", """
                seq_id,executed_query,user_id,start_time,duration_minutes
                1,"select s.region, sum(t.amount) from sales.tx t join sales.stores s on t.store_id = s.id where t.dt = '2026-10-01' group by s.region",alice,2026-10-08 09:00:00,40
                2,"select s.region, sum(t.amount) from sales.tx t join sales.stores s on t.store_id = s.id where t.dt = '2026-10-02' group by s.region",bob,2026-10-08 10:00:00,44
                3,select x from ops.small where y = 1,alice,2026-10-08 11:00:00,2
                4,select x from ops.small where y = 2,bob,2026-10-08 12:00:00,3
                5,select z from ops.once where y = 1,carol,2026-10-08 13:00:00,30
                """);
        QueryGroup join = group("sales.tx");
        TrackerDto t = await(join.getId());
        // normal (2-3 min) and adhoc-only (one bad run) patterns are not tracked
        assertThat(trackerRepo.findByGroupId(group("ops.small").getId())).isEmpty();
        assertThat(trackerRepo.findByGroupId(group("ops.once").getId())).isEmpty();
        assertThat(classifier.classify(1, java.time.LocalDateTime.now())).isEqualTo(PatternClassifier.PatternClass.ADHOC);

        assertThat(t.autoRunStatus()).isEqualTo(AutoRunStatus.DONE);
        assertThat(t.resultStatus()).isEqualTo(ResultStatus.READY_FOR_ADOPTION);
        assertThat(t.workflowStatus()).isEqualTo(WorkflowStatus.SME_VALIDATION);
        assertThat(t.diagnosticId()).isEqualTo(TrackerService.diagnosticId(t.trackerId()));
        assertThat(t.autoRunMessage()).contains("sent to the users");
        Notification mail = awaitMail(t.trackerId(), Notification.Kind.READY_FOR_ADOPTION);
        assertThat(mail.getRecipients()).isEqualTo("app-users@example.com");
        assertThat(mail.getStatus()).isEqualTo(Notification.Status.LOGGED); // transport LOG: recorded, not sent
        assertThat(mail.getSubject()).contains(t.diagnosticId()).contains("ready for adoption");
        assertThat(mail.getBody()).contains("How it was tuned").contains("/* +BROADCAST */").contains("/tracker/" + t.trackerId());
        assertThat(trackers.get(t.trackerId()).notifiedAt()).isNotNull();

        // a threshold the rewrite cannot reach: the next candidate goes to the tuning team
        settings.save(Map.of(WorkflowSettings.ADOPTION_THRESHOLD_PCT, "95"));
        load("b.csv", """
                seq_id,executed_query,user_id,start_time,duration_minutes
                11,"select c.name from crm.customers c join crm.orders o on o.cid = c.id where o.dt = '2026-10-03'",dan,2026-10-09 09:00:00,20
                12,"select c.name from crm.customers c join crm.orders o on o.cid = c.id where o.dt = '2026-10-04'",erin,2026-10-09 10:00:00,22
                """);
        TrackerDto t2 = await(group("crm.customers").getId());
        assertThat(t2.resultStatus()).isEqualTo(ResultStatus.NEEDS_SME_REVIEW);
        assertThat(t2.workflowStatus()).isNotEqualTo(WorkflowStatus.SME_VALIDATION);
        assertThat(t2.autoRunMessage()).contains("needs more than 95.0%").contains("tuning team");
        assertThat(awaitMail(t2.trackerId(), Notification.Kind.NEEDS_SME_REVIEW).getRecipients())
                .isEqualTo("tuning-team@example.com");
    }

    private void load(String name, String csv) {
        ingestion.importFile(name, new ByteArrayResource(csv.getBytes(StandardCharsets.UTF_8)), null);
    }

    private QueryGroup group(String table) {
        return groups.findAll().stream().filter(g -> g.getSampleQuery().contains(table)).findFirst().orElseThrow();
    }

    private TrackerDto await(Long groupId) throws InterruptedException {
        waitFor(() -> trackerRepo.findByGroupId(groupId)
                .map(x -> x.getAutoRunStatus() == AutoRunStatus.DONE || x.getAutoRunStatus() == AutoRunStatus.FAILED)
                .orElse(false));
        return trackers.get(trackerRepo.findByGroupId(groupId).orElseThrow().getId());
    }

    private Notification awaitMail(Long trackerId, Notification.Kind kind) throws InterruptedException {
        waitFor(() -> outbox.findByTrackerIdOrderByIdDesc(trackerId).stream()
                .anyMatch(n -> n.getKind() == kind && n.getStatus() != Notification.Status.PENDING));
        return outbox.findByTrackerIdOrderByIdDesc(trackerId).stream().filter(n -> n.getKind() == kind).findFirst()
                .orElseThrow();
    }

    private static void waitFor(BooleanSupplier done) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            if (done.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("timed out");
    }

}
