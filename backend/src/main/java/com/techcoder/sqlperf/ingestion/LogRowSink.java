package com.techcoder.sqlperf.ingestion;

/** Receives parsed rows from a {@link LogFileParser} or {@link JdbcLogPuller}. */
public interface LogRowSink {

    void accept(RawLogRecord record);

    /** A row that could not even be read (e.g. malformed CSV quoting). */
    void reject(long rowNumber, String reason);
}
