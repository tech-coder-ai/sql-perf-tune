package com.techcoder.sqlperf.ingestion;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.techcoder.sqlperf.common.CurrentUser;
import com.techcoder.sqlperf.common.NotFoundException;
import com.techcoder.sqlperf.common.SqlEngine;
import com.techcoder.sqlperf.common.Texts;
import com.techcoder.sqlperf.config.SptProperties;
import com.techcoder.sqlperf.group.GroupingService;
import com.techcoder.sqlperf.ingestion.IngestionBatch.SourceKind;
import com.techcoder.sqlperf.ingestion.IngestionBatch.Status;
import com.techcoder.sqlperf.log.LogSighting;
import com.techcoder.sqlperf.log.LogSightingRepository;
import com.techcoder.sqlperf.log.QueryLog;
import com.techcoder.sqlperf.log.QueryLogRepository;
import com.techcoder.sqlperf.log.QueryLogRepository.FingerprintKey;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.InputStreamSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Loads query logs (file or JDBC) so that <b>every log row is processed exactly once</b>:
 * <ul>
 *   <li>An upload whose bytes were already loaded is not parsed again. The import is flagged with the
 *       import that processed it ({@code DUPLICATE_OF_BATCH_ID}), the file's load count is increased and
 *       each of its rows gets a sighting.</li>
 *   <li>Otherwise rows are parsed and identified by {@link RowKeys}. Known rows (from earlier loads or
 *       repeated in the same file) only get a sighting and a higher {@code SEEN_COUNT}; new rows are
 *       inserted, fingerprinted and grouped.</li>
 * </ul>
 * Imports are serialized within the service instance; the unique index on {@code ROW_KEY} guarantees the
 * rule across instances (a concurrent duplicate makes that chunk fail instead of double counting).
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final IngestionBatchRepository batches;
    private final QueryLogRepository logs;
    private final LogSightingRepository sightings;
    private final SourceFileRepository files;
    private final SourceConnectionRepository sources;
    private final LogFileParser fileParser;
    private final JdbcLogPuller puller;
    private final GroupingService grouping;
    private final RowKeys rowKeys;
    private final TransactionTemplate tx;
    private final EntityManager em;
    private final SptProperties props;
    private final ReentrantLock lock = new ReentrantLock(true);
    /** batch id -> user who asked to cancel it */
    private final Map<Long, String> cancelled = new ConcurrentHashMap<>();
    private final Executor worker;

    public IngestionService(IngestionBatchRepository batches, QueryLogRepository logs, LogSightingRepository sightings,
                            SourceFileRepository files, SourceConnectionRepository sources, LogFileParser fileParser,
                            JdbcLogPuller puller, GroupingService grouping, RowKeys rowKeys, TransactionTemplate tx,
                            EntityManager em, SptProperties props, @Qualifier("ingestionExecutor") Executor worker) {
        this.batches = batches;
        this.logs = logs;
        this.sightings = sightings;
        this.files = files;
        this.sources = sources;
        this.fileParser = fileParser;
        this.puller = puller;
        this.grouping = grouping;
        this.rowKeys = rowKeys;
        this.tx = tx;
        this.em = em;
        this.props = props;
        this.worker = worker;
    }

    /**
     * Synchronous import (tests, scripts).
     *
     * @param source re-readable content (a MultipartFile or a Resource): hashed first, then parsed if new
     */
    public IngestionBatch importFile(String fileName, InputStreamSource source, String sqlEngine) {
        IngestionBatch batch = queueFile(fileName, sqlEngine);
        return runFile(batch, source);
    }

    /**
     * Background import: the batch is created (RUNNING, "Queued") and returned at once; the file is processed
     * by the single ingestion worker. Poll {@code GET /api/ingestion/batches/{id}} for progress.
     */
    public IngestionBatch submitFile(String fileName, InputStreamSource upload, String sqlEngine) throws IOException {
        IngestionBatch batch = queueFile(fileName, sqlEngine);
        Path temp = Files.createTempFile("spt-upload-", ".tmp");
        try (InputStream in = upload.getInputStream()) {
            Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
        }
        worker.execute(() -> {
            try {
                runFile(batch, new FileSystemResource(temp));
            } finally {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException e) {
                    log.warn("Could not delete {}", temp, e);
                }
            }
        });
        return batch;
    }

    private IngestionBatch queueFile(String fileName, String sqlEngine) {
        String engine = engine(sqlEngine);
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        SourceKind kind = lower.endsWith(".xlsx") || lower.endsWith(".xls") ? SourceKind.EXCEL : SourceKind.CSV;
        return start(kind, null, fileName, engine);
    }

    private IngestionBatch runFile(IngestionBatch batch, InputStreamSource source) {
        lock.lock();
        try {
            progress(batch, "Checking whether this file was loaded before");
            FileDigest digest;
            try {
                digest = digest(source);
            } catch (IOException e) {
                return fail(new Loader(batch), e);
            }
            Optional<SourceFile> known = files.findByContentHash(digest.hash());
            if (known.isPresent()) {
                return reloadOfKnownFile(known.get(), batch, digest);
            }
            batch.setContentHash(digest.hash());
            batch.setFileLoadNumber(1);
            Loader loader = new Loader(batch);
            progress(batch, "Reading rows");
            try (InputStream in = source.getInputStream()) {
                if (batch.getSourceKind() == SourceKind.EXCEL) {
                    fileParser.parseExcel(in, loader);
                } else {
                    fileParser.parseCsv(in, loader);
                }
                IngestionBatch done = finish(loader);
                registerFile(done, batch.getSourceName(), digest);
                return done;
            } catch (IOException | RuntimeException e) {
                if (!(e instanceof CancelledException)) {
                    log.warn("Import of {} failed", batch.getSourceName(), e);
                }
                return fail(loader, e);
            }
        } finally {
            cancelled.remove(batch.getId());
            lock.unlock();
        }
    }

    /** Synchronous pull (tests, scheduled jobs). */
    public IngestionBatch pull(Long sourceId) {
        SourceConnection src = source(sourceId);
        return runPull(start(SourceKind.JDBC, src.getId(), src.getName(), engine(src.getSqlEngine())), src);
    }

    /** Background pull, like {@link #submitFile}. */
    public IngestionBatch submitPull(Long sourceId) {
        SourceConnection src = source(sourceId);
        IngestionBatch batch = start(SourceKind.JDBC, src.getId(), src.getName(), engine(src.getSqlEngine()));
        worker.execute(() -> runPull(batch, src));
        return batch;
    }

    private SourceConnection source(Long sourceId) {
        SourceConnection src = sources.findById(sourceId).orElseThrow(() -> new NotFoundException("Data source", sourceId));
        if (!src.isActive()) {
            throw new IllegalArgumentException("Data source " + src.getName() + " is inactive");
        }
        return src;
    }

    private IngestionBatch runPull(IngestionBatch batch, SourceConnection src) {
        lock.lock();
        try {
            Loader loader = new Loader(batch);
            progress(batch, "Querying " + src.getName());
            try {
                LocalDateTime watermark = puller.pull(src, loader);
                IngestionBatch done = finish(loader);
                tx.executeWithoutResult(s -> sources.findById(src.getId()).ifPresent(f -> f.setLastWatermark(watermark)));
                return done;
            } catch (Exception e) {
                if (!(e instanceof CancelledException)) {
                    log.warn("Pull from data source {} failed", src.getName(), e);
                }
                return fail(loader, e);
            }
        } finally {
            cancelled.remove(batch.getId());
            lock.unlock();
        }
    }

    /** Asks a running import to stop after the current row; rows loaded so far are kept and grouped. */
    public IngestionBatch cancel(Long batchId) {
        IngestionBatch b = batches.findById(batchId).orElseThrow(() -> new NotFoundException("Import", batchId));
        if (b.getStatus() != Status.RUNNING) {
            throw new IllegalArgumentException("Import #" + batchId + " is not running");
        }
        cancelled.put(batchId, CurrentUser.name());
        return b;
    }

    /** Thrown inside the parser loop to stop a cancelled import. */
    static final class CancelledException extends RuntimeException {
        CancelledException(String by) {
            super("Cancelled by " + by);
        }
    }

    private void progress(IngestionBatch batch, String message) {
        batch.setMessage(message);
        tx.executeWithoutResult(s -> batches.save(batch));
    }

    // ------------------------------------------------------------------ identical file re-load

    private IngestionBatch reloadOfKnownFile(SourceFile file, IngestionBatch batch, FileDigest digest) {
        String fileName = batch.getSourceName();
        return tx.execute(s -> {
            LocalDateTime now = LocalDateTime.now();
            int rows = sightings.copyFromBatch(file.getProcessedBatchId(), batch.getId(), now);
            logs.markSeenByBatch(batch.getId(), now);
            SourceFile f = files.findById(file.getId()).orElseThrow();
            f.setLoadCount(f.getLoadCount() + 1);
            f.setLastBatchId(batch.getId());
            f.setLastLoadedAt(now);

            IngestionBatch b = batches.findById(batch.getId()).orElseThrow();
            b.setContentHash(digest.hash());
            b.setSourceFileId(f.getId());
            b.setFileLoadNumber(f.getLoadCount());
            b.setDuplicateOfBatchId(f.getProcessedBatchId());
            b.setRowsRead(rows);
            b.setRowsDuplicate(rows);
            b.setStatus(Status.COMPLETED);
            b.setMessage("Identical file already processed by import #" + f.getProcessedBatchId() + " (load "
                    + f.getLoadCount() + " of this file). Not re-processed; " + rows + " rows marked as seen again.");
            b.setCompletedAt(now);
            log.info("File {} already loaded (hash {}), load #{}", fileName, digest.hash(), f.getLoadCount());
            return b;
        });
    }

    private void registerFile(IngestionBatch batch, String fileName, FileDigest digest) {
        if (batch.getStatus() == Status.FAILED) {
            return; // a failed load may be retried with the same file and must then be parsed again
        }
        tx.executeWithoutResult(s -> {
            SourceFile f = new SourceFile();
            f.setContentHash(digest.hash());
            f.setFileName(Texts.truncate(fileName, 500));
            f.setFileSize(digest.size());
            f.setProcessedBatchId(batch.getId());
            f.setLastBatchId(batch.getId());
            f.setFirstLoadedAt(batch.getStartedAt());
            f.setLastLoadedAt(batch.getStartedAt());
            files.save(f);
            IngestionBatch b = batches.findById(batch.getId()).orElseThrow();
            b.setSourceFileId(f.getId());
            batch.setSourceFileId(f.getId());
        });
    }

    private record FileDigest(String hash, long size) {
    }

    private static FileDigest digest(InputStreamSource source) throws IOException {
        try (InputStream raw = source.getInputStream();
             DigestInputStream in = new DigestInputStream(raw, MessageDigest.getInstance("SHA-256"))) {
            byte[] buf = new byte[64 * 1024];
            long size = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                size += n;
            }
            return new FileDigest(HexFormat.of().formatHex(in.getMessageDigest().digest()), size);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ batch lifecycle

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

    private IngestionBatch finish(Loader loader) {
        loader.flush();
        // rows left ungrouped by an earlier interrupted import are grouped now as well
        loader.keys.addAll(logs.orphanFingerprintKeys());
        progress(loader.batch, "Grouping " + loader.keys.size() + " query patterns");
        int groupsAffected = grouping.recompute(loader.keys);
        IngestionBatch b = loader.batch;
        b.setGroupsAffected(groupsAffected);
        b.setStatus(b.getRowsRejected() > 0 ? Status.COMPLETED_WITH_ERRORS : Status.COMPLETED);
        b.setMessage(Texts.truncate(join(loader.errors, summary(b)), 4000));
        b.setCompletedAt(LocalDateTime.now());
        return tx.execute(s -> batches.save(b));
    }

    private IngestionBatch fail(Loader loader, Exception e) {
        // keep whatever was loaded consistent with its groups
        try {
            loader.flush();
            loader.keys.addAll(logs.orphanFingerprintKeys());
            loader.batch.setGroupsAffected(grouping.recompute(loader.keys));
        } catch (RuntimeException ignored) {
            // original error is more relevant
        }
        IngestionBatch b = loader.batch;
        b.setStatus(Status.FAILED);
        String head = e instanceof CancelledException
                ? e.getMessage() + " after " + b.getRowsRead() + " rows. The " + b.getRowsLoaded()
                        + " new rows loaded so far were kept and grouped; upload the file again to load the rest "
                        + "(rows already loaded are skipped)."
                : e.getMessage();
        b.setMessage(Texts.truncate(join(loader.errors, head), 4000));
        b.setCompletedAt(LocalDateTime.now());
        return tx.execute(s -> batches.save(b));
    }

    /** The database's own message (e.g. ORA-12899 value too large for column ...), without wrapper noise. */
    static String rootMessage(Throwable e) {
        Throwable root = NestedExceptionUtils.getMostSpecificCause(e);
        String m = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage().strip();
        // drop Oracle's help links ("https://docs.oracle.com/error-help/...") and collapse whitespace
        return Texts.truncate(m.replaceAll("https?://\\S+", "").replaceAll("\\s+", " ").strip(), 300);
    }

    private static String summary(IngestionBatch b) {
        return b.getRowsDuplicate() == 0 ? null
                : b.getRowsDuplicate() + " rows were already loaded and were not re-processed.";
    }

    private static String join(List<String> errors, String extra) {
        List<String> all = new ArrayList<>();
        if (!Texts.isBlank(extra)) {
            all.add(extra);
        }
        all.addAll(errors);
        return all.isEmpty() ? null : String.join("\n", all);
    }

    /** Buffers parsed rows; each chunk is split into known rows (sighting only) and new rows (processed). */
    private final class Loader implements LogRowSink {

        private static final int MAX_ERROR_LINES = 50;

        final IngestionBatch batch;
        final Set<FingerprintKey> keys = new LinkedHashSet<>();
        final List<String> errors = new ArrayList<>();
        /** row keys already handled in this load (new or sighted) - repeats inside one file are duplicates */
        private final Set<String> handled = new HashSet<>();
        private final List<QueryLog> buffer = new ArrayList<>();
        /** source row number of each buffered row, for rejection messages */
        private final Map<QueryLog, Long> rowNumbers = new IdentityHashMap<>();

        Loader(IngestionBatch batch) {
            this.batch = batch;
        }

        @Override
        public void accept(RawLogRecord record) {
            String by = cancelled.get(batch.getId());
            if (by != null) {
                throw new CancelledException(by);
            }
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
            q.setRowKey(rowKeys.of(q));
            if (!handled.add(q.getRowKey())) {
                batch.setRowsDuplicate(batch.getRowsDuplicate() + 1);
                return;
            }
            buffer.add(q);
            rowNumbers.put(q, record.rowNumber());
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
            List<QueryLog> inserted;
            int failed = 0;
            try {
                inserted = tx.execute(s -> store(chunk));
            } catch (CancelledException e) {
                throw e;
            } catch (RuntimeException chunkError) {
                // One bad row (e.g. a value too long for an Oracle column) must not stop the import: retry the
                // chunk row by row, reject only the rows the database refuses and keep going.
                log.warn("Import #{}: chunk of {} rows failed ({}); retrying row by row", batch.getId(), chunk.size(),
                        rootMessage(chunkError));
                inserted = new ArrayList<>();
                for (QueryLog q : chunk) {
                    q.setId(null);
                    try {
                        inserted.addAll(tx.execute(s -> store(List.of(q))));
                    } catch (RuntimeException rowError) {
                        failed++;
                        reject(rowNumbers.getOrDefault(q, 0L), "not saved: " + rootMessage(rowError));
                    }
                }
            }
            chunk.forEach(rowNumbers::remove);
            batch.setRowsLoaded(batch.getRowsLoaded() + inserted.size());
            batch.setRowsDuplicate(batch.getRowsDuplicate() + chunk.size() - inserted.size() - failed);
            progress(batch, "Read " + batch.getRowsRead() + " rows");
            for (QueryLog q : inserted) {
                if (q.getFingerprint() != null) {
                    keys.add(new FingerprintKey(q.getSqlEngine(), q.getFingerprint()));
                }
            }
        }

        /** Inserts the new rows of a chunk and records a sighting for every row; returns the new rows. */
        private List<QueryLog> store(List<QueryLog> chunk) {
            LocalDateTime now = LocalDateTime.now();
            Map<String, QueryLog> existing = logs.findByRowKeyIn(chunk.stream().map(QueryLog::getRowKey).toList())
                    .stream().collect(Collectors.toMap(QueryLog::getRowKey, Function.identity()));
            List<QueryLog> fresh = new ArrayList<>();
            List<LogSighting> seen = new ArrayList<>();
            for (QueryLog q : chunk) {
                QueryLog known = existing.get(q.getRowKey());
                if (known != null) {
                    known.setSeenCount(known.getSeenCount() + 1);
                    known.setLastSeenAt(now);
                    known.setLastSeenBatchId(batch.getId());
                    seen.add(sighting(known.getId(), now, false));
                } else {
                    q.setCreatedAt(now);
                    q.setLastSeenAt(now);
                    q.setLastSeenBatchId(batch.getId());
                    grouping.applyFingerprint(q);
                    fresh.add(q);
                }
            }
            logs.saveAll(fresh);
            em.flush();
            fresh.forEach(q -> seen.add(sighting(q.getId(), now, true)));
            sightings.saveAll(seen);
            em.flush();
            em.clear();
            return fresh;
        }

        private LogSighting sighting(Long logId, LocalDateTime now, boolean first) {
            LogSighting s = new LogSighting();
            s.setLogId(logId);
            s.setBatchId(batch.getId());
            s.setSeenAt(now);
            s.setFirst(first);
            return s;
        }
    }
}
