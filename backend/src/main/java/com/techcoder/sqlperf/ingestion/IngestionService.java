package com.techcoder.sqlperf.ingestion;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.techcoder.sqlperf.common.CurrentUser;
import com.techcoder.sqlperf.common.NotFoundException;
import com.techcoder.sqlperf.common.SqlEngine;
import com.techcoder.sqlperf.common.Texts;
import com.techcoder.sqlperf.config.SptProperties;
import com.techcoder.sqlperf.group.GroupingService;
import com.techcoder.sqlperf.ingestion.IngestionBatch.SourceKind;
import com.techcoder.sqlperf.ingestion.IngestionBatch.Status;
import com.techcoder.sqlperf.log.QueryLog;
import com.techcoder.sqlperf.log.QueryLogRepository;
import com.techcoder.sqlperf.log.QueryLogRepository.FingerprintKey;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Loads query logs (file or JDBC), fingerprints every row and refreshes the affected groups.
 * Rows are written in chunks so a large file never holds one giant transaction.
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final IngestionBatchRepository batches;
    private final QueryLogRepository logs;
    private final SourceConnectionRepository sources;
    private final LogFileParser fileParser;
    private final JdbcLogPuller puller;
    private final GroupingService grouping;
    private final TransactionTemplate tx;
    private final EntityManager em;
    private final SptProperties props;

    public IngestionService(IngestionBatchRepository batches, QueryLogRepository logs, SourceConnectionRepository sources,
                            LogFileParser fileParser, JdbcLogPuller puller, GroupingService grouping,
                            TransactionTemplate tx, EntityManager em, SptProperties props) {
        this.batches = batches;
        this.logs = logs;
        this.sources = sources;
        this.fileParser = fileParser;
        this.puller = puller;
        this.grouping = grouping;
        this.tx = tx;
        this.em = em;
        this.props = props;
    }

    public IngestionBatch importFile(String fileName, InputStream in, String sqlEngine) {
        String engine = engine(sqlEngine);
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        SourceKind kind = lower.endsWith(".xlsx") || lower.endsWith(".xls") ? SourceKind.EXCEL : SourceKind.CSV;
        IngestionBatch batch = start(kind, null, fileName, engine);
        Loader loader = new Loader(batch);
        try {
            if (kind == SourceKind.EXCEL) {
                fileParser.parseExcel(in, loader);
            } else {
                fileParser.parseCsv(in, loader);
            }
            return finish(loader, null);
        } catch (IOException | RuntimeException e) {
            log.warn("Import of {} failed", fileName, e);
            return fail(loader, e);
        }
    }

    public IngestionBatch pull(Long sourceId) {
        SourceConnection src = sources.findById(sourceId).orElseThrow(() -> new NotFoundException("Data source", sourceId));
        if (!src.isActive()) {
            throw new IllegalArgumentException("Data source " + src.getName() + " is inactive");
        }
        IngestionBatch batch = start(SourceKind.JDBC, src.getId(), src.getName(), engine(src.getSqlEngine()));
        Loader loader = new Loader(batch);
        try {
            LocalDateTime watermark = puller.pull(src, loader);
            IngestionBatch done = finish(loader, null);
            tx.executeWithoutResult(s -> sources.findById(sourceId).ifPresent(fresh -> fresh.setLastWatermark(watermark)));
            return done;
        } catch (Exception e) {
            log.warn("Pull from data source {} failed", src.getName(), e);
            return fail(loader, e);
        }
    }

    private String engine(String requested) {
        String e = Texts.isBlank(requested) ? props.ingestion().defaultSqlEngine() : requested.trim().toUpperCase(Locale.ROOT);
        SqlEngine.valueOf(e); // validates
        return e;
    }

    private IngestionBatch start(SourceKind kind, Long dsId, String name, String engine) {
        IngestionBatch b = new IngestionBatch();
        b.setSourceKind(kind);
        b.setDataSourceId(dsId);
        b.setSourceName(Texts.truncate(name, 500));
        b.setSqlEngine(engine);
        b.setStatus(Status.RUNNING);
        b.setStartedAt(LocalDateTime.now());
        b.setCreatedBy(CurrentUser.name());
        return tx.execute(s -> batches.save(b));
    }

    private IngestionBatch finish(Loader loader, String extraMessage) {
        loader.flush();
        int groupsAffected = grouping.recompute(loader.keys);
        IngestionBatch b = loader.batch;
        b.setGroupsAffected(groupsAffected);
        b.setStatus(b.getRowsRejected() > 0 ? Status.COMPLETED_WITH_ERRORS : Status.COMPLETED);
        b.setMessage(Texts.truncate(join(loader.errors, extraMessage), 4000));
        b.setCompletedAt(LocalDateTime.now());
        return tx.execute(s -> batches.save(b));
    }

    private IngestionBatch fail(Loader loader, Exception e) {
        // keep whatever was loaded consistent with its groups
        try {
            loader.flush();
            loader.batch.setGroupsAffected(grouping.recompute(loader.keys));
        } catch (RuntimeException ignored) {
            // original error is more relevant
        }
        IngestionBatch b = loader.batch;
        b.setStatus(Status.FAILED);
        b.setMessage(Texts.truncate(join(loader.errors, e.getMessage()), 4000));
        b.setCompletedAt(LocalDateTime.now());
        return tx.execute(s -> batches.save(b));
    }

    private static String join(List<String> errors, String extra) {
        List<String> all = new ArrayList<>();
        if (!Texts.isBlank(extra)) {
            all.add(extra);
        }
        all.addAll(errors);
        return all.isEmpty() ? null : String.join("\n", all);
    }

    /** Buffers parsed rows and writes them in chunks. */
    private final class Loader implements LogRowSink {

        private static final int MAX_ERROR_LINES = 50;

        final IngestionBatch batch;
        final Set<FingerprintKey> keys = new LinkedHashSet<>();
        final List<String> errors = new ArrayList<>();
        private final List<QueryLog> buffer = new ArrayList<>();

        Loader(IngestionBatch batch) {
            this.batch = batch;
        }

        @Override
        public void accept(RawLogRecord record) {
            batch.setRowsRead(batch.getRowsRead() + 1);
            QueryLog q;
            try {
                q = record.toQueryLog();
            } catch (IllegalArgumentException e) {
                reject(record.rowNumber(), e.getMessage());
                return;
            }
            q.setBatchId(batch.getId());
            q.setSqlEngine(batch.getSqlEngine());
            q.setCreatedAt(LocalDateTime.now());
            grouping.applyFingerprint(q);
            buffer.add(q);
            if (buffer.size() >= props.ingestion().batchSize()) {
                flush();
            }
        }

        @Override
        public void reject(long rowNumber, String reason) {
            batch.setRowsRejected(batch.getRowsRejected() + 1);
            if (errors.size() < MAX_ERROR_LINES) {
                errors.add("Row " + rowNumber + ": " + reason);
            }
            if (batch.getRowsRejected() > props.ingestion().maxRejectedRows()) {
                throw new IllegalStateException("Aborted: more than " + props.ingestion().maxRejectedRows()
                        + " rejected rows");
            }
        }

        void flush() {
            if (buffer.isEmpty()) {
                return;
            }
            List<QueryLog> chunk = new ArrayList<>(buffer);
            buffer.clear();
            tx.executeWithoutResult(s -> {
                logs.saveAll(chunk);
                em.flush();
                em.clear();
            });
            batch.setRowsLoaded(batch.getRowsLoaded() + chunk.size());
            for (QueryLog q : chunk) {
                if (q.getFingerprint() != null) {
                    keys.add(new FingerprintKey(q.getSqlEngine(), q.getFingerprint()));
                }
            }
        }
    }
}
