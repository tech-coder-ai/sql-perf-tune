package com.techcoder.sqlperf.ingestion;

import java.time.LocalDateTime;
import java.util.List;

import com.techcoder.sqlperf.common.Texts;
import com.techcoder.sqlperf.group.GroupingService;
import com.techcoder.sqlperf.log.QueryLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Start-up clean-up after an unclean stop: imports still marked RUNNING are closed as interrupted and log rows
 * that were inserted but never grouped are grouped, so the groups and tracker always match the logs.
 */
@Component
@Order(20)
public class IngestionRecovery implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IngestionRecovery.class);

    private final IngestionBatchRepository batches;
    private final QueryLogRepository logs;
    private final GroupingService grouping;
    private final TransactionTemplate tx;

    public IngestionRecovery(IngestionBatchRepository batches, QueryLogRepository logs, GroupingService grouping,
                             TransactionTemplate tx) {
        this.batches = batches;
        this.logs = logs;
        this.grouping = grouping;
        this.tx = tx;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<IngestionBatch> stale = tx.execute(s -> batches.findAll().stream()
                .filter(b -> b.getStatus() == IngestionBatch.Status.RUNNING)
                .peek(b -> {
                    long loaded = logs.countByBatchId(b.getId());
                    b.setStatus(IngestionBatch.Status.FAILED);
                    b.setRowsLoaded((int) loaded);
                    b.setCompletedAt(LocalDateTime.now());
                    b.setMessage(Texts.truncate("Interrupted: the service stopped during this import. The " + loaded
                            + " rows loaded before the stop were kept and grouped; upload the file again to load the "
                            + "rest (rows already loaded are skipped).", 4000));
                })
                .toList());
        var orphans = logs.orphanFingerprintKeys();
        if (!orphans.isEmpty()) {
            grouping.recompute(orphans);
        }
        if (!stale.isEmpty() || !orphans.isEmpty()) {
            log.info("Recovered {} interrupted imports and grouped {} orphaned query patterns", stale.size(), orphans.size());
        }
    }
}
