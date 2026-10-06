package com.techcoder.sqlperf.ingestion;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.techcoder.sqlperf.log.QueryLog;
import com.techcoder.sqlperf.log.QueryLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Gives rows loaded before de-duplication existed their {@code ROW_KEY} (one-off, on start-up).
 * Rows that were loaded twice before then keep a null key on the later copy and are reported.
 */
@Component
public class RowKeyBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RowKeyBackfill.class);

    private final QueryLogRepository logs;
    private final RowKeys rowKeys;
    private final TransactionTemplate tx;

    public RowKeyBackfill(QueryLogRepository logs, RowKeys rowKeys, TransactionTemplate tx) {
        this.logs = logs;
        this.rowKeys = rowKeys;
        this.tx = tx;
    }

    @Override
    public void run(ApplicationArguments args) {
        Set<Long> skipped = new HashSet<>();
        int filled = 0;
        while (true) {
            Integer n = tx.execute(s -> {
                List<QueryLog> page = logs.withoutRowKey(PageRequest.of(0, 500)).stream()
                        .filter(l -> !skipped.contains(l.getId())).toList();
                Set<String> keys = new HashSet<>();
                page.forEach(l -> keys.add(rowKeys.of(l)));
                Set<String> taken = new HashSet<>();
                logs.findByRowKeyIn(keys).forEach(l -> taken.add(l.getRowKey()));
                int done = 0;
                for (QueryLog l : page) {
                    String key = rowKeys.of(l);
                    if (taken.add(key)) {
                        l.setRowKey(key);
                        done++;
                    } else {
                        skipped.add(l.getId());
                    }
                }
                return page.isEmpty() ? null : done;
            });
            if (n == null) {
                break;
            }
            filled += n;
        }
        if (filled > 0 || !skipped.isEmpty()) {
            log.info("Back-filled ROW_KEY for {} log rows; {} pre-existing duplicate rows left without a key",
                    filled, skipped.size());
        }
    }
}
